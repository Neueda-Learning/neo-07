package com.neobank.module.service;

import com.neobank.module.dto.AccountRecordView;
import com.neobank.module.integrations.orchestrator.ApplicationRequest;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.repository.AccountRecordRepository;
import java.util.List;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-00 — Process Application. The durable anchor row, idempotency, and the async hand-off.
 *
 * <p>UC-02's engine ({@link AccountOpeningService}) runs off-thread <em>after</em> the row this
 * class creates exists — but only when this call is the one that <em>freshly</em> created it. A
 * repeated {@code /execute} for an id whose row already exists must do zero new core calls
 * (UC-02 AC#4); it only re-fires the callback with the row's already-stored outcome.</p>
 */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    private final Executor executor;
    private final AccountRecordRepository accountRecords;
    private final ReferenceGenerator referenceGenerator;
    private final AccountOpeningService accountOpeningService;

    /**
     * {@code applicationTaskExecutor} is the thread pool Spring Boot configures for you. Tune it in
     * {@code application.yml} under {@code spring.task.execution.*} — pool size matters once your
     * logic calls a slow mock, because that is what limits how many applications you can handle at
     * once.
     */
    public ApplicationService(@Qualifier("applicationTaskExecutor") Executor executor,
                              AccountRecordRepository accountRecords,
                              ReferenceGenerator referenceGenerator,
                              AccountOpeningService accountOpeningService) {
        this.executor = executor;
        this.accountRecords = accountRecords;
        this.referenceGenerator = referenceGenerator;
        this.accountOpeningService = accountOpeningService;
    }

    /**
     * Hand the work to the pool and return immediately.
     *
     * <p>The controller calls this and then writes the {@code 202}. <b>Nothing here may block:</b>
     * the orchestrator is holding a connection open, and a module that does its work on the request
     * thread turns a fast journey into a slow one.</p>
     */
    public void processApplicationAsync(ApplicationRequest request) {
        executor.execute(() -> processApplication(request));
    }

    /**
     * Log receipt, commit the one durable row this application id gets, then hand off to the
     * engine — but only when this call is the one that freshly created the row.
     *
     * <p>Package-private on purpose — the outside world goes through
     * {@link #processApplicationAsync}, and a unit test can call this directly on the test thread,
     * which is what makes it testable without a thread pool.</p>
     *
     * <p><b>Deliberately not {@code @Transactional}.</b> The engine call below does real I/O
     * (the mock core, the orchestrator callback) and must never run inside a transaction wrapping
     * the row commit. {@link #createAccountRecordIfAbsent} carries its own narrow
     * {@code @Transactional} around the read-then-insert only.</p>
     */
    void processApplication(ApplicationRequest request) {
        String applicationId = request.applicationId();
        log.info("Received {}", request.summary());
        try {
            Insertion insertion = createAccountRecordIfAbsent(applicationId);
            if (insertion.fresh()) {
                accountOpeningService.open(request);
            } else {
                accountOpeningService.replay(insertion.account());
            }
        } catch (RuntimeException e) {
            // A module error here must not crash the executor's worker thread.
            log.error("failed to process application {}", applicationId, e);
        }
    }

    /** The row this call obtained, and whether this call is the one that inserted it. */
    record Insertion(AccountRecord account, boolean fresh) {
    }

    /**
     * Exactly one {@code account_record} row per application id (UC-00 AC#2, AC#4).
     *
     * <p>{@code @Transactional} here is safe and narrow: it wraps only the read-then-insert, no
     * I/O, so it does not reintroduce the "orchestrator call inside a transaction" problem the
     * class-level doc above calls out. The {@link DataIntegrityViolationException} catch handles
     * two concurrent callers racing to insert the same id (UC-02 AC#5) — the loser's own {@code
     * save} throws, so it re-reads the winner's row and reports itself as not-fresh; only the
     * thread whose own insert actually succeeded invokes the engine, so it runs exactly once
     * regardless of which thread wins the race.</p>
     */
    @Transactional
    Insertion createAccountRecordIfAbsent(String applicationId) {
        return accountRecords.findById(applicationId)
                .map(existing -> new Insertion(existing, false))
                .orElseGet(() -> {
                    try {
                        AccountRecord created = accountRecords.save(
                                new AccountRecord(applicationId, referenceGenerator.next()));
                        return new Insertion(created, true);
                    } catch (DataIntegrityViolationException e) {
                        return new Insertion(accountRecords.findById(applicationId).orElseThrow(), false);
                    }
                });
    }

    /** Everything this module has answered, newest first — what its own UI reads. */
    @Transactional(readOnly = true)
    public List<AccountRecordView> findAll() {
        return accountRecords.findAllByOrderByCreatedAtDesc().stream()
                .map(AccountRecordView::of)
                .toList();
    }
}
