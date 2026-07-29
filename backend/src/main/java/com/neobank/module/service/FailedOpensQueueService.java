package com.neobank.module.service;

import com.neobank.module.controller.RetryCaseNotFoundException;
import com.neobank.module.controller.InvalidCaseStateException;
import com.neobank.module.dto.FailedQueueRow;
import com.neobank.module.integrations.orchestrator.OrchestratorClient;
import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountReasonCode;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.model.CoreAttempt;
import com.neobank.module.model.CoreAttemptKind;
import com.neobank.module.model.CoreAttemptResult;
import com.neobank.module.model.CoreConfig;
import com.neobank.module.model.Decision;
import com.neobank.module.repository.AccountRecordRepository;
import com.neobank.module.repository.CoreAttemptRepository;
import com.neobank.module.repository.CoreConfigRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-04 — Failed-Opens Queue. A queue of opens parked on {@code FAILED} +
 * {@code ACC_CORE_UNAVAILABLE}, and the one-click retry that re-enters the probe-then-open flow
 * once an operator believes the core is back.
 *
 * <p>The applicant did nothing wrong here — the core was down. {@link #retry} always probes
 * before it opens (a retry that skipped the probe could double-open an account the outage merely
 * hid), appends every core call it makes as a fresh {@code core_attempt} row, and only fires the
 * local-manual callback when the case actually flips to {@code OPENED} (AC#5): a still-down retry
 * changes nothing but the attempt log — the journey is already parked, so there is nothing new to
 * report.</p>
 *
 * <p>Deliberately not wrapped in one {@code @Transactional}: the core calls inside the retry loop
 * are real, possibly slow, HTTP — holding a database connection across them would be exactly the
 * "orchestrator call inside a transaction" mistake this codebase's own services warn against
 * elsewhere. Each repository call below commits on its own.</p>
 */
@Service
public class FailedOpensQueueService {

    private static final Logger log = LoggerFactory.getLogger(FailedOpensQueueService.class);

    private final AccountRecordRepository accountRecords;
    private final CoreAttemptRepository coreAttempts;
    private final CoreConfigRepository coreConfigs;
    private final CoreOpsClient coreClient;
    private final OrchestratorClient orchestrator;

    public FailedOpensQueueService(AccountRecordRepository accountRecords,
                                   CoreAttemptRepository coreAttempts,
                                   CoreConfigRepository coreConfigs,
                                   CoreOpsClient coreClient,
                                   OrchestratorClient orchestrator) {
        this.accountRecords = accountRecords;
        this.coreAttempts = coreAttempts;
        this.coreConfigs = coreConfigs;
        this.coreClient = coreClient;
        this.orchestrator = orchestrator;
    }

    /** Oldest first, capped at 10 (AC#1) — the repository's {@code Top10} does the capping. */
    @Transactional(readOnly = true)
    public List<FailedQueueRow> queue() {
        return accountRecords
                .findTop10ByOutcomeAndReasonCodeOrderByCreatedAtAsc(
                        AccountOutcome.FAILED, AccountReasonCode.ACC_CORE_UNAVAILABLE)
                .stream()
                .map(record -> new FailedQueueRow(
                        record.getApplicationId(),
                        record.getReference(),
                        record.getCoreConfigVersion(),
                        coreAttempts.countByApplicationId(record.getApplicationId()),
                        record.getCreatedAt()))
                .toList();
    }

    /**
     * Re-runs probe-then-open for one parked case under the current config version. Always PROBES
     * before it OPENs, for every cycle the current retry budget allows — "the budget applies
     * anew" (build notes): a case that failed after its original 3 cycles gets the current
     * version's full budget, not zero. The anchor keeps its original config version so the first
     * automated decision remains explainable.
     *
     * @throws RetryCaseNotFoundException   unknown applicationId (AC#6 — 404)
     * @throws InvalidCaseStateException the case is not currently FAILED (AC#6 — 400)
     */
    public void retry(String applicationId) {
        AccountRecord record = accountRecords.findById(applicationId)
                .orElseThrow(() -> new RetryCaseNotFoundException(applicationId));
        if (record.getOutcome() != AccountOutcome.FAILED) {
            throw new InvalidCaseStateException(
                    "case " + applicationId + " is not FAILED — nothing to retry");
        }

        CoreConfig config = coreConfigs.findTopByOrderByVersionDesc()
                .orElseThrow(() -> new IllegalStateException("no current core config exists"));

        int nextCycle = coreAttempts.findAllByApplicationIdOrderByOccurredAtAscIdAsc(applicationId).stream()
                .mapToInt(CoreAttempt::getCycleNo)
                .max()
                .orElse(0) + 1;

        boolean resolved = runCycles(record, config, applicationId, nextCycle);

        if (resolved) {
            accountRecords.save(record);
            orchestrator.applicationStatusUpdate(applicationId, Decision.ACCEPTED,
                    "manual retry: core reachable again — " + record.getReasonCode());
            log.info("RETRY resolved {} -> OPENED ({})", applicationId, record.getReasonCode());
        } else {
            log.info("RETRY {} — core still unreachable, case stays FAILED", applicationId);
        }
    }

    /** @return true once the case has flipped to OPENED (adopted or newly created). */
    private boolean runCycles(AccountRecord record, CoreConfig config, String applicationId, int startCycle) {
        for (int cycle = startCycle; cycle < startCycle + config.getRetryBudget(); cycle++) {
            CoreOpsClient.CoreCallOutcome probe =
                    coreClient.probe(config.getCoreBaseUrl(), applicationId, config.getTimeoutMs());
            saveAttempt(applicationId, cycle, CoreAttemptKind.PROBE, probe);

            if (probe.result() == CoreAttemptResult.HIT) {
                record.markOpened(probe.accountId(), AccountReasonCode.ACC_DUPLICATE_PREVENTED);
                return true;
            }

            // MISS, ERROR or TIMEOUT on the probe still attempts the open this cycle — a probe
            // failure does not prove the core is down for writes too, and the budget's job is to
            // spend whole cycles, not to guess which half of one failed.
            CoreOpsClient.CoreCallOutcome open = coreClient.open(config.getCoreBaseUrl(), applicationId,
                    record.getProductCode(), record.getCreditAmount(), config.getTimeoutMs());
            saveAttempt(applicationId, cycle, CoreAttemptKind.OPEN, open);

            if (open.result() == CoreAttemptResult.CREATED) {
                record.markOpened(open.accountId(), AccountReasonCode.ACC_OPENED);
                return true;
            }
            if (open.result() == CoreAttemptResult.TIMEOUT) {
                // The outage may have hidden a created account — probe again before giving up on
                // this cycle (UC-02 build notes: "on timeout probe again").
                CoreOpsClient.CoreCallOutcome recovery =
                        coreClient.probe(config.getCoreBaseUrl(), applicationId, config.getTimeoutMs());
                saveAttempt(applicationId, cycle, CoreAttemptKind.PROBE, recovery);
                if (recovery.result() == CoreAttemptResult.HIT) {
                    record.markOpened(recovery.accountId(), AccountReasonCode.ACC_DUPLICATE_PREVENTED);
                    return true;
                }
            }
            // ERROR, or a timeout the recovery probe could not resolve — try the next cycle.
        }
        return false;
    }

    private void saveAttempt(String applicationId, int cycle, CoreAttemptKind kind,
                             CoreOpsClient.CoreCallOutcome outcome) {
        coreAttempts.save(new CoreAttempt(applicationId, cycle, kind, outcome.result(), applicationId,
                outcome.latencyMs()));
    }
}
