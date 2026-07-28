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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-00 — Process Application. The durable anchor row, idempotency, and the async hand-off.
 *
 * <p>Deciding anything is out of scope here (that is the engine use case, which runs off-thread
 * <em>after</em> the row this method creates exists) — so is the callback content. Until that
 * engine exists, a case simply stays {@code IN_PROGRESS} once its row is written.</p>
 */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    private final Executor executor;
    private final AccountRecordRepository accountRecords;
    private final ReferenceGenerator referenceGenerator;

    /**
     * {@code applicationTaskExecutor} is the thread pool Spring Boot configures for you. Tune it in
     * {@code application.yml} under {@code spring.task.execution.*} — pool size matters once your
     * logic calls a slow mock, because that is what limits how many applications you can handle at
     * once.
     */
    public ApplicationService(@Qualifier("applicationTaskExecutor") Executor executor,
                              AccountRecordRepository accountRecords,
                              ReferenceGenerator referenceGenerator) {
        this.executor = executor;
        this.accountRecords = accountRecords;
        this.referenceGenerator = referenceGenerator;
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
     * Log receipt, then commit the one durable row this application id gets.
     *
     * <p>Package-private on purpose — the outside world goes through
     * {@link #processApplicationAsync}, and a unit test can call this directly on the test thread,
     * which is what makes it testable without a thread pool.</p>
     *
     * <p><b>Deliberately not {@code @Transactional}.</b> This method does not talk to the
     * orchestrator (UC-00 has nothing to report yet), but the same principle that kept this class's
     * placeholder version free of a wrapping transaction still applies: a slow or failing step
     * downstream of the row commit must never be able to roll the row back. {@link
     * #createAccountRecordIfAbsent} carries its own narrow {@code @Transactional} around the
     * read-then-insert only.</p>
     */
    void processApplication(ApplicationRequest request) {
        String applicationId = request.applicationId();
        log.info("Received {}", request.summary());
        try {
            createAccountRecordIfAbsent(applicationId);
        } catch (RuntimeException e) {
            // A module error here must not crash the executor's worker thread — log it and move
            // on. There is nothing to report to the orchestrator: the callback belongs to the
            // engine use case that decides an outcome, and this method never reaches a decision.
            log.error("failed to persist account_record for {}", applicationId, e);
        }
    }

    /**
     * Exactly one {@code account_record} row per application id (UC-00 AC#2, AC#4).
     *
     * <p>{@code @Transactional} here is safe and narrow: it wraps only the read-then-insert, no
     * I/O, so it does not reintroduce the "orchestrator call inside a transaction" problem the
     * class-level doc above calls out.</p>
     */
    @Transactional
    AccountRecord createAccountRecordIfAbsent(String applicationId) {
        return accountRecords.findById(applicationId)
                .orElseGet(() -> accountRecords.save(
                        new AccountRecord(applicationId, referenceGenerator.next())));
    }

    /** Everything this module has answered, newest first — what its own UI reads. */
    @Transactional(readOnly = true)
    public List<AccountRecordView> findAll() {
        return accountRecords.findAllByOrderByCreatedAtDesc().stream()
                .map(AccountRecordView::of)
                .toList();
    }
}
