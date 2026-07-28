package com.neobank.module.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.module.controller.CaseNotFoundException;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * UC-04 — Failed-Opens Queue service. No Spring, no database: {@link CoreClient} and the
 * repositories are mocked so every probe/open outcome the build notes describe can be pinned
 * exactly ("probe miss, open timeout, probe hit -> adopted" reads straight off the mock's
 * {@code when(...)} chain).
 */
class FailedOpensQueueServiceTest {

    private static final int CORE_CONFIG_VERSION = 1;

    private AccountRecordRepository accountRecords;
    private CoreAttemptRepository coreAttempts;
    private CoreConfigRepository coreConfigs;
    private CoreClient coreClient;
    private OrchestratorClient orchestrator;
    private FailedOpensQueueService service;

    @BeforeEach
    void setUp() {
        accountRecords = mock(AccountRecordRepository.class);
        coreAttempts = mock(CoreAttemptRepository.class);
        coreConfigs = mock(CoreConfigRepository.class);
        coreClient = mock(CoreClient.class);
        orchestrator = mock(OrchestratorClient.class);
        service = new FailedOpensQueueService(accountRecords, coreAttempts, coreConfigs, coreClient, orchestrator);

        when(accountRecords.save(any(AccountRecord.class))).thenAnswer(call -> call.getArgument(0));
        when(coreConfigs.findById(CORE_CONFIG_VERSION))
                .thenReturn(Optional.of(config(3, 2000)));
    }

    private static CoreConfig config(int retryBudget, int timeoutMs) throws RuntimeException {
        try {
            JsonNode catalogue = new ObjectMapper().readTree("{}");
            return new CoreConfig(CORE_CONFIG_VERSION, retryBudget, timeoutMs, "http://localhost:8080", catalogue);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static AccountRecord failedRecord(String applicationId) {
        AccountRecord record = new AccountRecord(applicationId, "acc-" + applicationId);
        record.pinCoreConfig(CORE_CONFIG_VERSION);
        record.markFailed(AccountReasonCode.ACC_CORE_UNAVAILABLE);
        return record;
    }

    // ---- queue() -----------------------------------------------------------------------------

    @Test
    void queueListsOnlyFailedCoreUnavailableCasesWithTheirAttemptCount() {
        AccountRecord row = failedRecord("app-1240");
        when(accountRecords.findTop10ByOutcomeAndReasonCodeOrderByCreatedAtAsc(
                AccountOutcome.FAILED, AccountReasonCode.ACC_CORE_UNAVAILABLE))
                .thenReturn(List.of(row));
        when(coreAttempts.countByApplicationId("app-1240")).thenReturn(6L);

        List<FailedQueueRow> queue = service.queue();

        assertThat(queue).singleElement().satisfies(r -> {
            assertThat(r.applicationId()).isEqualTo("app-1240");
            assertThat(r.attemptCount()).isEqualTo(6L);
            assertThat(r.coreConfigVersion()).isEqualTo(CORE_CONFIG_VERSION);
        });
    }

    // ---- retry() guards ------------------------------------------------------------------------

    @Test
    void retryOnAnUnknownIdIs404() {
        when(accountRecords.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retry("ghost")).isInstanceOf(CaseNotFoundException.class);
    }

    @Test
    void retryOnANonFailedCaseIs400AndMakesNoCoreCalls() {
        AccountRecord opened = new AccountRecord("app-9", "acc-9");
        opened.pinCoreConfig(CORE_CONFIG_VERSION);
        opened.markOpened("CC-1", AccountReasonCode.ACC_OPENED);
        when(accountRecords.findById("app-9")).thenReturn(Optional.of(opened));

        assertThatThrownBy(() -> service.retry("app-9")).isInstanceOf(InvalidCaseStateException.class);

        verify(coreClient, never()).probe(anyString(), anyString(), anyInt());
        verify(coreClient, never()).open(anyString(), anyString(), any(), any(), anyInt());
    }

    // ---- retry() success paths ------------------------------------------------------------------

    @Test
    void retrySucceedsViaProbeMissThenOpenCreatedAndFiresExactlyOneCallback() {
        AccountRecord record = failedRecord("app-1234");
        when(accountRecords.findById("app-1234")).thenReturn(Optional.of(record));
        when(coreAttempts.findAllByApplicationIdOrderByOccurredAtAscIdAsc("app-1234")).thenReturn(List.of());
        when(coreClient.probe("http://localhost:8080", "app-1234", 2000))
                .thenReturn(new CoreClient.CoreCallOutcome(CoreAttemptResult.MISS, 10, null));
        when(coreClient.open("http://localhost:8080", "app-1234", null, null, 2000))
                .thenReturn(new CoreClient.CoreCallOutcome(CoreAttemptResult.CREATED, 20, "CC-0058291"));

        service.retry("app-1234");

        assertThat(record.getOutcome()).isEqualTo(AccountOutcome.OPENED);
        assertThat(record.getReasonCode()).isEqualTo(AccountReasonCode.ACC_OPENED);
        assertThat(record.getAccountId()).isEqualTo("CC-0058291");

        ArgumentCaptor<CoreAttempt> attempts = ArgumentCaptor.forClass(CoreAttempt.class);
        verify(coreAttempts, org.mockito.Mockito.times(2)).save(attempts.capture());
        assertThat(attempts.getAllValues()).extracting(CoreAttempt::getKind)
                .containsExactly(CoreAttemptKind.PROBE, CoreAttemptKind.OPEN);
        assertThat(attempts.getAllValues()).allMatch(a -> a.getCycleNo() == 1);

        verify(orchestrator).applicationStatusUpdate(eq("app-1234"), eq(Decision.ACCEPTED), anyString());
        verify(accountRecords).save(record);
    }

    @Test
    void retryAdoptsViaProbeHitDuplicatePrevented() {
        AccountRecord record = failedRecord("app-adopt");
        when(accountRecords.findById("app-adopt")).thenReturn(Optional.of(record));
        when(coreAttempts.findAllByApplicationIdOrderByOccurredAtAscIdAsc("app-adopt")).thenReturn(List.of());
        when(coreClient.probe("http://localhost:8080", "app-adopt", 2000))
                .thenReturn(new CoreClient.CoreCallOutcome(CoreAttemptResult.HIT, 15, "CC-EXISTING"));

        service.retry("app-adopt");

        assertThat(record.getOutcome()).isEqualTo(AccountOutcome.OPENED);
        assertThat(record.getReasonCode()).isEqualTo(AccountReasonCode.ACC_DUPLICATE_PREVENTED);
        assertThat(record.getAccountId()).isEqualTo("CC-EXISTING");
        verify(coreClient, never()).open(anyString(), anyString(), any(), any(), anyInt());
        verify(orchestrator).applicationStatusUpdate(eq("app-adopt"), eq(Decision.ACCEPTED), anyString());
    }

    @Test
    void openTimeoutThenRecoveryProbeHitAdoptsWithThreeAttemptsInOneCycle() {
        AccountRecord record = failedRecord("app-timeout");
        when(accountRecords.findById("app-timeout")).thenReturn(Optional.of(record));
        when(coreAttempts.findAllByApplicationIdOrderByOccurredAtAscIdAsc("app-timeout")).thenReturn(List.of());
        when(coreClient.probe("http://localhost:8080", "app-timeout", 2000))
                .thenReturn(new CoreClient.CoreCallOutcome(CoreAttemptResult.MISS, 10, null))
                .thenReturn(new CoreClient.CoreCallOutcome(CoreAttemptResult.HIT, 12, "CC-RECOVERED"));
        when(coreClient.open("http://localhost:8080", "app-timeout", null, null, 2000))
                .thenReturn(new CoreClient.CoreCallOutcome(CoreAttemptResult.TIMEOUT, 2000, null));

        service.retry("app-timeout");

        assertThat(record.getOutcome()).isEqualTo(AccountOutcome.OPENED);
        assertThat(record.getReasonCode()).isEqualTo(AccountReasonCode.ACC_DUPLICATE_PREVENTED);
        assertThat(record.getAccountId()).isEqualTo("CC-RECOVERED");

        ArgumentCaptor<CoreAttempt> attempts = ArgumentCaptor.forClass(CoreAttempt.class);
        verify(coreAttempts, org.mockito.Mockito.times(3)).save(attempts.capture());
        assertThat(attempts.getAllValues()).extracting(CoreAttempt::getKind)
                .containsExactly(CoreAttemptKind.PROBE, CoreAttemptKind.OPEN, CoreAttemptKind.PROBE);
        assertThat(attempts.getAllValues()).extracting(CoreAttempt::getResult)
                .containsExactly(CoreAttemptResult.MISS, CoreAttemptResult.TIMEOUT, CoreAttemptResult.HIT);
        assertThat(attempts.getAllValues()).allMatch(a -> a.getCycleNo() == 1);
    }

    // ---- retry() still-down path -----------------------------------------------------------------

    @Test
    void retryWhileCoreStillDownStaysFailedAppendsAttemptsAndFiresNoCallback() {
        AccountRecord record = failedRecord("app-1240");
        // Already has one prior failed cycle on record (cycle 1) — retry must continue at cycle 2.
        CoreAttempt priorProbe = new CoreAttempt("app-1240", 1, CoreAttemptKind.PROBE, CoreAttemptResult.MISS, "app-1240", 5L);
        CoreAttempt priorOpen = new CoreAttempt("app-1240", 1, CoreAttemptKind.OPEN, CoreAttemptResult.ERROR, "app-1240", 5L);
        when(accountRecords.findById("app-1240")).thenReturn(Optional.of(record));
        when(coreAttempts.findAllByApplicationIdOrderByOccurredAtAscIdAsc("app-1240"))
                .thenReturn(List.of(priorProbe, priorOpen));
        when(coreConfigs.findById(CORE_CONFIG_VERSION)).thenReturn(Optional.of(config(3, 2000)));
        when(coreClient.probe("http://localhost:8080", "app-1240", 2000))
                .thenReturn(new CoreClient.CoreCallOutcome(CoreAttemptResult.MISS, 5, null));
        when(coreClient.open("http://localhost:8080", "app-1240", null, null, 2000))
                .thenReturn(new CoreClient.CoreCallOutcome(CoreAttemptResult.ERROR, 5, null));

        service.retry("app-1240");

        assertThat(record.getOutcome()).isEqualTo(AccountOutcome.FAILED);
        assertThat(record.getReasonCode()).isEqualTo(AccountReasonCode.ACC_CORE_UNAVAILABLE);

        // Fresh budget of 3 cycles, 2 attempts each, continuing from cycle 2 -> cycles 2,3,4.
        ArgumentCaptor<CoreAttempt> attempts = ArgumentCaptor.forClass(CoreAttempt.class);
        verify(coreAttempts, org.mockito.Mockito.times(6)).save(attempts.capture());
        assertThat(attempts.getAllValues()).extracting(CoreAttempt::getCycleNo)
                .containsExactly(2, 2, 3, 3, 4, 4);

        verify(orchestrator, never()).applicationStatusUpdate(anyString(), any(), anyString());
        verify(accountRecords, never()).save(any(AccountRecord.class));
    }
}
