package com.neobank.module.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neobank.module.integrations.orchestrator.Application;
import com.neobank.module.integrations.orchestrator.ApplicationRequest;
import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.repository.AccountRecordRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * UC-00: the durable row, idempotency, and the failure guard.
 *
 * <p>No Spring, no database — the service takes a request and calls one collaborator, so the
 * test is a handful of lines.</p>
 */
class ApplicationServiceTest {

    private AccountRecordRepository accountRecords;
    private ReferenceGenerator referenceGenerator;
    private ApplicationService service;

    @BeforeEach
    void setUp() {
        accountRecords = mock(AccountRecordRepository.class);
        referenceGenerator = mock(ReferenceGenerator.class);
        when(referenceGenerator.next()).thenReturn("acc-00000001");
        when(accountRecords.save(any(AccountRecord.class))).thenAnswer(call -> call.getArgument(0));
        // Runnable::run — the work happens inline, so there is nothing to wait for.
        service = new ApplicationService(Runnable::run, accountRecords, referenceGenerator);
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
    void firstReceiptInsertsExactlyOneInProgressRow() {
        when(accountRecords.findById("SIM-01")).thenReturn(Optional.empty());

        service.processApplication(request("SIM-01"));

        ArgumentCaptor<AccountRecord> saved = ArgumentCaptor.forClass(AccountRecord.class);
        verify(accountRecords).save(saved.capture());
        assertThat(saved.getValue().getApplicationId()).isEqualTo("SIM-01");
        assertThat(saved.getValue().getReference()).isEqualTo("acc-00000001");
        assertThat(saved.getValue().getOutcome()).isEqualTo(AccountOutcome.IN_PROGRESS);
    }

    @Test
    void repeatedReceiptForTheSameIdDoesNotInsertASecondRow() {
        AccountRecord existing = new AccountRecord("SIM-02", "acc-existing1");
        when(accountRecords.findById("SIM-02")).thenReturn(Optional.of(existing));

        service.processApplication(request("SIM-02"));

        verify(accountRecords, never()).save(any(AccountRecord.class));
    }

    @Test
    void theAsyncEntryPointDoesTheSameWorkThroughTheExecutor() {
        when(accountRecords.findById("SIM-03")).thenReturn(Optional.empty());

        service.processApplicationAsync(request("SIM-03"));

        verify(accountRecords).save(any(AccountRecord.class));
    }

    @Test
    void aPersistenceFailureIsLoggedNotThrown() {
        // A module error here must not crash the executor's worker thread. There is nothing to
        // report to the orchestrator yet — UC-00 never reaches a decision.
        when(accountRecords.findById("SIM-04")).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("database on fire"))
                .when(accountRecords).save(any(AccountRecord.class));

        service.processApplication(request("SIM-04"));

        // No exception propagates out of processApplication — reaching this line is the assertion.
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
