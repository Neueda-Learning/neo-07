package com.neobank.module.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.module.integrations.core.CoreCallResult;
import com.neobank.module.integrations.core.CoreClient;
import com.neobank.module.integrations.orchestrator.Application;
import com.neobank.module.integrations.orchestrator.ApplicationRequest;
import com.neobank.module.integrations.orchestrator.OrchestratorClient;
import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.model.CoreAttemptKind;
import com.neobank.module.model.CoreAttemptResult;
import com.neobank.module.model.CoreConfig;
import com.neobank.module.model.Decision;
import com.neobank.module.repository.AccountRecordRepository;
import com.neobank.module.repository.CoreAttemptRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Mockito unit test, no Spring — mirrors {@code ApplicationServiceTest}'s established style.
 * Proves the Spring-wired half of UC-02: attempts persisted in order, the record mutated
 * correctly, the right callback fired, config pinned before any core call, and the replay path
 * making zero core calls.
 */
class AccountOpeningServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AccountRecordRepository accountRecords;
    private CoreAttemptRepository coreAttempts;
    private CoreConfigService coreConfigService;
    private CoreClient coreClient;
    private OrchestratorClient orchestratorClient;
    private AccountOpeningService service;

    @BeforeEach
    void setUp() {
        accountRecords = mock(AccountRecordRepository.class);
        coreAttempts = mock(CoreAttemptRepository.class);
        coreConfigService = mock(CoreConfigService.class);
        coreClient = mock(CoreClient.class);
        orchestratorClient = mock(OrchestratorClient.class);
        service = new AccountOpeningService(accountRecords, coreAttempts, coreConfigService, coreClient,
                orchestratorClient);
        when(accountRecords.save(any(AccountRecord.class))).thenAnswer(call -> call.getArgument(0));
    }

    private static CoreConfig config() throws Exception {
        JsonNode catalogue = MAPPER.readTree("{}");
        return new CoreConfig(1, 3, 2000, "http://localhost:8080", catalogue);
    }

    private static ApplicationRequest request(String id, int requestedCreditLimit) {
        Application application = new Application(
                id, "MOBILE_APP", "2026-07-25T09:14:00Z",
                new Application.Applicant("Maria Nowak", "1996-04-11", null, null, null, null,
                        null, null, null, null, null),
                null, null, null,
                new Application.Product("CREDIT_CARD_REWARDS", requestedCreditLimit),
                null, null);
        return new ApplicationRequest(id, "corr-1", "process-application", application);
    }

    @Test
    void cleanPathPersistsAttemptsInOrderOpensTheRecordAndReportsAccepted() throws Exception {
        AccountRecord account = new AccountRecord("app-1234", "acc-000123");
        when(coreConfigService.current()).thenReturn(Optional.of(config()));
        when(accountRecords.findById("app-1234")).thenReturn(Optional.of(account));
        when(coreClient.probe(eq("http://localhost:8080"), eq(2000), eq("app-1234")))
                .thenReturn(new CoreCallResult(CoreAttemptResult.MISS, null, 41));
        when(coreClient.open(eq("http://localhost:8080"), eq(2000), eq("app-1234"), any(), any()))
                .thenReturn(new CoreCallResult(CoreAttemptResult.CREATED, "CC-0058291", 212));

        service.open(request("app-1234", 3000));

        ArgumentCaptor<com.neobank.module.model.CoreAttempt> attemptCaptor =
                ArgumentCaptor.forClass(com.neobank.module.model.CoreAttempt.class);
        verify(coreAttempts, times(2)).save(attemptCaptor.capture());
        assertThat(attemptCaptor.getAllValues().get(0).getKind()).isEqualTo(CoreAttemptKind.PROBE);
        assertThat(attemptCaptor.getAllValues().get(0).getResult()).isEqualTo(CoreAttemptResult.MISS);
        assertThat(attemptCaptor.getAllValues().get(1).getKind()).isEqualTo(CoreAttemptKind.OPEN);
        assertThat(attemptCaptor.getAllValues().get(1).getResult()).isEqualTo(CoreAttemptResult.CREATED);

        assertThat(account.getOutcome()).isEqualTo(AccountOutcome.OPENED);
        assertThat(account.getAccountId()).isEqualTo("CC-0058291");
        assertThat(account.getProductVersion()).isEqualTo(1);
        assertThat(account.getCoreConfigVersion()).isEqualTo(1);

        verify(orchestratorClient).applicationStatusUpdate(eq("app-1234"), eq(Decision.ACCEPTED), anyString());
    }

    @Test
    void adoptOnTimeoutPersistsAllThreeAttemptsAndReportsAccepted() throws Exception {
        AccountRecord account = new AccountRecord("app-1", "acc-1");
        when(coreConfigService.current()).thenReturn(Optional.of(config()));
        when(accountRecords.findById("app-1")).thenReturn(Optional.of(account));
        when(coreClient.probe(anyString(), anyInt(), eq("app-1")))
                .thenReturn(new CoreCallResult(CoreAttemptResult.MISS, null, 8))
                .thenReturn(new CoreCallResult(CoreAttemptResult.HIT, "CC-adopted1", 4));
        when(coreClient.open(anyString(), anyInt(), eq("app-1"), any(), any()))
                .thenReturn(new CoreCallResult(CoreAttemptResult.TIMEOUT, null, 2000));

        service.open(request("app-1", 3000));

        verify(coreAttempts, times(3)).save(any());
        assertThat(account.getOutcome()).isEqualTo(AccountOutcome.OPENED);
        assertThat(account.getAccountId()).isEqualTo("CC-adopted1");
        verify(orchestratorClient).applicationStatusUpdate(eq("app-1"), eq(Decision.ACCEPTED), anyString());
    }

    @Test
    void budgetExhaustedFailsTheRecordAndReportsReferred() throws Exception {
        AccountRecord account = new AccountRecord("app-1240", "acc-1240");
        when(coreConfigService.current()).thenReturn(Optional.of(config()));
        when(accountRecords.findById("app-1240")).thenReturn(Optional.of(account));
        when(coreClient.probe(anyString(), anyInt(), eq("app-1240")))
                .thenReturn(new CoreCallResult(CoreAttemptResult.MISS, null, 5));
        when(coreClient.open(anyString(), anyInt(), eq("app-1240"), any(), any()))
                .thenReturn(new CoreCallResult(CoreAttemptResult.ERROR, null, 5));

        service.open(request("app-1240", 3000));

        assertThat(account.getOutcome()).isEqualTo(AccountOutcome.FAILED);
        assertThat(account.getAccountId()).isNull();
        verify(orchestratorClient).applicationStatusUpdate(eq("app-1240"), eq(Decision.REFERRED), anyString());
    }

    @Test
    void configIsPinnedBeforeAnyCoreCall() throws Exception {
        AccountRecord account = mock(AccountRecord.class);
        when(account.getApplicationId()).thenReturn("app-1");
        when(coreConfigService.current()).thenReturn(Optional.of(config()));
        when(accountRecords.findById("app-1")).thenReturn(Optional.of(account));
        when(coreClient.probe(anyString(), anyInt(), anyString()))
                .thenReturn(new CoreCallResult(CoreAttemptResult.HIT, "CC-1", 1));

        service.open(request("app-1", 3000));

        verify(account).pinCoreConfig(1);
        verify(coreClient).probe(anyString(), anyInt(), anyString());
    }

    @Test
    void replayMakesZeroCoreCallsAndReportsTheStoredOutcome() {
        AccountRecord opened = new AccountRecord("app-1", "acc-1");
        opened.open("CC-1", 2800, false, "agr-1", "CREDIT_CARD_REWARDS", null,
                com.neobank.module.model.AccountReasonCode.ACC_OPENED, java.time.Instant.now());

        service.replay(opened);

        verifyNoInteractions(coreClient);
        verifyNoInteractions(coreConfigService);
        verify(coreAttempts, never()).save(any());
        verify(orchestratorClient).applicationStatusUpdate(eq("app-1"), eq(Decision.ACCEPTED), anyString());
    }

    @Test
    void replayOfAFailedCaseReportsReferred() {
        AccountRecord failed = new AccountRecord("app-2", "acc-2");
        failed.fail(com.neobank.module.model.AccountReasonCode.ACC_CORE_UNAVAILABLE);

        service.replay(failed);

        verifyNoInteractions(coreClient);
        verify(orchestratorClient).applicationStatusUpdate(eq("app-2"), eq(Decision.REFERRED), anyString());
    }

    @Test
    void replayOfAStillInProgressRowReportsNothing() {
        // The concurrent-insert race loser (UC-02 AC#5): its own insert failed, it re-read the
        // winner's row, but the winner's engine may not have decided it yet. There is no outcome
        // to report — the winner's own `open` call is the only one that will ever report this id.
        AccountRecord stillDeciding = new AccountRecord("app-3", "acc-3");

        service.replay(stillDeciding);

        verifyNoInteractions(coreClient);
        verifyNoInteractions(orchestratorClient);
    }
}
