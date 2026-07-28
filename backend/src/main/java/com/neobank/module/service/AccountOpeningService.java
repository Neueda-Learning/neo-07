package com.neobank.module.service;

import com.neobank.module.integrations.core.CoreCallResult;
import com.neobank.module.integrations.core.CoreClient;
import com.neobank.module.integrations.orchestrator.ApplicationRequest;
import com.neobank.module.integrations.orchestrator.Application;
import com.neobank.module.integrations.orchestrator.OrchestratorClient;
import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.model.CoreAttempt;
import com.neobank.module.model.CoreAttemptKind;
import com.neobank.module.model.CoreConfig;
import com.neobank.module.model.Decision;
import com.neobank.module.repository.AccountRecordRepository;
import com.neobank.module.repository.CoreAttemptRepository;
import com.neobank.module.service.AccountOpeningEngine.CoreCallException;
import com.neobank.module.service.AccountOpeningEngine.CoreCallOutcome;
import com.neobank.module.service.AccountOpeningEngine.CreditTerms;
import com.neobank.module.service.AccountOpeningEngine.EngineResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-02 — Review Case + Attempt Log: the engine's Spring-wired half.
 *
 * <p>{@link AccountOpeningEngine} decides what to call next; this class is the only thing that
 * actually calls the core, persists a {@code CoreAttempt} row per call, applies the verdict to
 * the {@code AccountRecord}, and reports it to the orchestrator. Per the brief's own build note
 * ("build and unit-test check-then-act before any Spring wiring"), the pure decision and the I/O
 * around it are kept in separate classes on purpose.</p>
 */
@Service
public class AccountOpeningService {

    private static final Logger log = LoggerFactory.getLogger(AccountOpeningService.class);

    private final AccountRecordRepository accountRecords;
    private final CoreAttemptRepository coreAttempts;
    private final CoreConfigService coreConfigService;
    private final CoreClient coreClient;
    private final OrchestratorClient orchestratorClient;

    public AccountOpeningService(AccountRecordRepository accountRecords,
            CoreAttemptRepository coreAttempts,
            CoreConfigService coreConfigService,
            CoreClient coreClient,
            OrchestratorClient orchestratorClient) {
        this.accountRecords = accountRecords;
        this.coreAttempts = coreAttempts;
        this.coreConfigService = coreConfigService;
        this.coreClient = coreClient;
        this.orchestratorClient = orchestratorClient;
    }

    /**
     * Runs the engine for a freshly-inserted row and reports the outcome. Only ever called for
     * an application id whose row this same worker just created — see {@code ApplicationService}.
     */
    public void open(ApplicationRequest request) {
        String applicationId = request.applicationId();
        try {
            CoreConfig config = coreConfigService.current()
                    .orElseThrow(() -> new IllegalStateException("no core_config version exists"));

            AccountRecord account = accountRecords.findById(applicationId)
                    .orElseThrow(() -> new IllegalStateException(
                            "account_record for " + applicationId + " must already exist"));
            account.pinCoreConfig(config.getVersion());
            accountRecords.save(account);

            Application.Product product = request.application() == null ? null : request.application().product();
            Integer requestedCreditLimit = product == null ? null : product.requestedCreditLimit();
            String productCode = product == null ? null : product.productCode();

            EngineResult result = AccountOpeningEngine.run(
                    config.getRetryBudget(),
                    applicationId,
                    requestedCreditLimit,
                    CreditTerms.none(),
                    (id, cycle) -> probe(config, id, cycle),
                    (id, cycle) -> open(config, id, cycle, productCode, requestedCreditLimit));

            apply(account, result, productCode, config.getVersion());

            String comment = result.outcome() == AccountOutcome.OPENED
                    ? "account opened: " + describeReason(result)
                    : "core unreachable after " + config.getRetryBudget() + " cycles";
            orchestratorClient.applicationStatusUpdate(applicationId, toDecision(result.outcome()), comment);
        } catch (RuntimeException e) {
            // A bug in the engine or its I/O must not crash the worker thread — the row is
            // already committed IN_PROGRESS; there is nothing left to roll back.
            log.error("account opening failed for {}", applicationId, e);
        }
    }

    /**
     * A repeated {@code /execute} for an id whose row already exists does zero new core calls
     * (UC-02 AC#4) — it only re-fires the callback with the stored outcome, so the orchestrator's
     * own retry/timeout handling is satisfied even on a pure replay.
     *
     * <p>The row can still be {@code IN_PROGRESS} here — not just on a genuine replay, but on the
     * losing side of a concurrent-insert race (UC-02 AC#5): the loser's own insert failed and it
     * re-read the winner's row before the winner's engine has necessarily finished deciding it.
     * Reporting anything in that state would be a lie (there is no outcome yet to report), so this
     * does nothing — the winner's own {@link #open} call is the one call that will ever report
     * this application id.</p>
     */
    public void replay(AccountRecord existing) {
        if (existing.getOutcome() == AccountOutcome.IN_PROGRESS) {
            return;
        }
        String comment = existing.getOutcome() == AccountOutcome.OPENED
                ? "account opened: replayed stored outcome"
                : "core unreachable (replayed stored outcome)";
        orchestratorClient.applicationStatusUpdate(existing.getApplicationId(), toDecision(existing.getOutcome()),
                comment);
    }

    private CoreCallOutcome probe(CoreConfig config, String applicationId, int cycle) {
        CoreCallResult result = coreClient.probe(config.getCoreBaseUrl(), config.getTimeoutMs(), applicationId);
        record(applicationId, cycle, CoreAttemptKind.PROBE, result);
        return toEngineOutcome(result);
    }

    private CoreCallOutcome open(CoreConfig config, String applicationId, int cycle, String productCode,
            Integer creditAmount) {
        CoreCallResult result = coreClient.open(config.getCoreBaseUrl(), config.getTimeoutMs(), applicationId,
                productCode, creditAmount);
        record(applicationId, cycle, CoreAttemptKind.OPEN, result);
        return toEngineOutcome(result);
    }

    private CoreCallOutcome toEngineOutcome(CoreCallResult result) {
        return switch (result.result()) {
            case TIMEOUT -> throw new CoreCallException(result.result(), "core call timed out");
            case ERROR -> throw new CoreCallException(result.result(), "core call failed");
            default -> new CoreCallOutcome(result.result(), result.accountId(), result.latencyMs());
        };
    }

    @Transactional
    void record(String applicationId, int cycle, CoreAttemptKind kind, CoreCallResult result) {
        coreAttempts.save(new CoreAttempt(applicationId, cycle, kind, result.result(), applicationId,
                result.latencyMs()));
    }

    @Transactional
    void apply(AccountRecord account, EngineResult result, String productCode, Integer configVersion) {
        if (result.outcome() == AccountOutcome.OPENED) {
            account.open(result.accountId(), result.creditAmount(), result.creditAmountFallback(),
                    result.agreementId(), productCode, null, result.reasonCode(), result.openedAt());
        } else {
            account.fail(result.reasonCode());
        }
        accountRecords.save(account);
    }

    private static String describeReason(EngineResult result) {
        return switch (result.reasonCode()) {
            case ACC_OPENED -> "core created a new account";
            case ACC_DUPLICATE_PREVENTED -> "adopted an existing core account after a timeout";
            case ACC_CORE_UNAVAILABLE -> "core unreachable";
        };
    }

    private static Decision toDecision(AccountOutcome outcome) {
        return outcome == AccountOutcome.OPENED ? Decision.ACCEPTED : Decision.REFERRED;
    }
}
