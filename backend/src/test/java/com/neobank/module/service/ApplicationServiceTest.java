package com.neobank.module.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neobank.module.integrations.orchestrator.Application;
import com.neobank.module.integrations.orchestrator.ApplicationRequest;
import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.repository.AccountRecordRepository;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * UC-00: the durable row, idempotency, and the failure guard. UC-02: the fresh-vs-replay hand-off
 * into the engine and the concurrent-insert race guard.
 *
 * <p>No Spring, no database — the service takes a request and calls its collaborators, so the
 * test is a handful of lines.</p>
 */
class ApplicationServiceTest {

    private AccountRecordRepository accountRecords;
    private ReferenceGenerator referenceGenerator;
    private AccountOpeningService accountOpeningService;
    private ApplicationService service;

    @BeforeEach
    void setUp() {
        accountRecords = mock(AccountRecordRepository.class);
        referenceGenerator = mock(ReferenceGenerator.class);
        accountOpeningService = mock(AccountOpeningService.class);
        when(referenceGenerator.next()).thenReturn("acc-00000001");
        when(accountRecords.save(any(AccountRecord.class))).thenAnswer(call -> call.getArgument(0));
        // Runnable::run — the work happens inline, so there is nothing to wait for.
        service = new ApplicationService(Runnable::run, accountRecords, referenceGenerator, accountOpeningService);
    }

    private static ApplicationRequest request(String id) {
        Application application = new Application(
                id, "MOBILE_APP", "2026-07-25T09:14:00Z",
                new Application.Applicant("Maria Nowak", "1996-04-11", null, null, null, null,
                        null, null, null, null, null),
                null, null, null,
                new Application.Product("CREDIT_CARD_REWARDS", 3000),
                null, null);
        return new ApplicationRequest(id, "corr-1", "process-application", application);
    }

    @Test
    void firstReceiptInsertsExactlyOneInProgressRowAndOpensTheEngine() {
        when(accountRecords.findById("SIM-01")).thenReturn(Optional.empty());

        service.processApplication(request("SIM-01"));

        ArgumentCaptor<AccountRecord> saved = ArgumentCaptor.forClass(AccountRecord.class);
        verify(accountRecords).save(saved.capture());
        assertThat(saved.getValue().getApplicationId()).isEqualTo("SIM-01");
        assertThat(saved.getValue().getReference()).isEqualTo("acc-00000001");
        assertThat(saved.getValue().getOutcome()).isEqualTo(AccountOutcome.IN_PROGRESS);
        verify(accountOpeningService).open(any(ApplicationRequest.class));
        verify(accountOpeningService, never()).replay(any(AccountRecord.class));
    }

    @Test
    void repeatedReceiptForTheSameIdDoesNotInsertASecondRowAndReplaysInstead() {
        AccountRecord existing = new AccountRecord("SIM-02", "acc-existing1");
        when(accountRecords.findById("SIM-02")).thenReturn(Optional.of(existing));

        service.processApplication(request("SIM-02"));

        verify(accountRecords, never()).save(any(AccountRecord.class));
        verify(accountOpeningService).replay(existing);
        verify(accountOpeningService, never()).open(any(ApplicationRequest.class));
    }

    @Test
    void aConcurrentInsertRaceFallsBackToTheWinnersRowAndNeverInvokesTheEngineTwice() {
        // This thread sees no row, but loses the actual insert race to another thread.
        AccountRecord winnersRow = new AccountRecord("SIM-05", "acc-winner01");
        when(accountRecords.findById("SIM-05"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnersRow));
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .when(accountRecords).save(any(AccountRecord.class));

        service.processApplication(request("SIM-05"));

        // The loser never treats itself as fresh — it replays the winner's row instead.
        verify(accountOpeningService).replay(winnersRow);
        verify(accountOpeningService, never()).open(any(ApplicationRequest.class));
    }

    @Test
    void theAsyncEntryPointDoesTheSameWorkThroughTheExecutor() {
        when(accountRecords.findById("SIM-03")).thenReturn(Optional.empty());

        service.processApplicationAsync(request("SIM-03"));

        verify(accountRecords).save(any(AccountRecord.class));
    }

    @Test
    void theRowIsCommittedBeforeTheEngineRunsNotAfter() {
        // A non-inline executor that only captures the submitted task — proves the row insert
        // happens on the calling thread, before the engine's task is ever run, not inside it
        // (UC-00 AC#2: "before the 202 is sent... a crash right after the ack loses nothing").
        AtomicReference<Runnable> captured = new AtomicReference<>();
        ApplicationService capturingService = new ApplicationService(
                captured::set, accountRecords, referenceGenerator, accountOpeningService);
        when(accountRecords.findById("SIM-06")).thenReturn(Optional.empty());

        capturingService.processApplicationAsync(request("SIM-06"));

        // The row is already saved even though the captured task hasn't run yet.
        verify(accountRecords).save(any(AccountRecord.class));
        verifyNoInteractions(accountOpeningService);

        captured.get().run();

        verify(accountOpeningService).open(any(ApplicationRequest.class));
    }

    @Test
    void anUnexpectedPersistenceFailurePropagatesRatherThanFalselyAckingA202() {
        // UC-00 AC#2: the row must be committed before the ack. A genuine failure to commit it
        // (not the expected concurrent-insert race, which createAccountRecordIfAbsent already
        // handles internally) must surface as an error, not a silent log line behind a 202 that
        // would otherwise lie about a row existing that was never actually written.
        when(accountRecords.findById("SIM-04")).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("database on fire"))
                .when(accountRecords).save(any(AccountRecord.class));

        assertThatThrownBy(() -> service.processApplication(request("SIM-04")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database on fire");
        verifyNoInteractions(accountOpeningService);
    }

    @Test
    void anEngineFailureAfterTheRowIsCommittedIsLoggedNotThrown() {
        // A module error here must not crash the executor's worker thread — but only once the
        // row is already safely committed, which is the whole point of Fix 0.
        AccountRecord existing = new AccountRecord("SIM-04", "acc-existing1");
        when(accountRecords.findById("SIM-04")).thenReturn(Optional.of(existing));
        doThrow(new IllegalStateException("core on fire")).when(accountOpeningService).replay(existing);

        service.processApplication(request("SIM-04"));

        // No exception propagates out of processApplication — reaching this line is the assertion.
        verify(accountOpeningService).replay(existing);
    }

    @Test
    void theBoardShowsWhatWasStored() {
        AccountRecord row = new AccountRecord("SIM-01", "acc-00000001");
        when(accountRecords.findAllByOrderByCreatedAtDesc()).thenReturn(java.util.List.of(row));

        assertThat(service.findAll())
                .singleElement()
                .satisfies(view -> {
                    assertThat(view.applicationId()).isEqualTo("SIM-01");
                    assertThat(view.reference()).isEqualTo("acc-00000001");
                    assertThat(view.outcome()).isEqualTo(AccountOutcome.IN_PROGRESS);
                });
    }
}
