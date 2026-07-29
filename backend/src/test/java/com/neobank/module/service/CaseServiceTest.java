package com.neobank.module.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.neobank.module.dto.ApplicantView;
import com.neobank.module.dto.CaseDetailView;
import com.neobank.module.integrations.orchestrator.OrchestratorClient;
import com.neobank.module.model.AccountReasonCode;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.model.CaseNotFoundException;
import com.neobank.module.model.CoreAttempt;
import com.neobank.module.model.CoreAttemptKind;
import com.neobank.module.model.CoreAttemptResult;
import com.neobank.module.repository.AccountRecordRepository;
import com.neobank.module.repository.CoreAttemptRepository;
import com.neobank.module.repository.OverrideLogRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaseServiceTest {

    private AccountRecordRepository accountRecords;
    private CoreAttemptRepository coreAttempts;
    private OverrideLogRepository overrides;
    private OrchestratorClient orchestratorClient;
    private CaseService service;

    @BeforeEach
    void setUp() {
        accountRecords = mock(AccountRecordRepository.class);
        coreAttempts = mock(CoreAttemptRepository.class);
        overrides = mock(OverrideLogRepository.class);
        orchestratorClient = mock(OrchestratorClient.class);
        service = new CaseService(accountRecords, coreAttempts, overrides, orchestratorClient);
    }

    @Test
    void getCaseDetailMapsTheRecordAndItsOrderedAttempts() {
        AccountRecord account = new AccountRecord("app-1234", "acc-000123");
        account.open("CC-0058291", 2800, false, "agr-000077", "CREDIT_CARD_REWARDS", 1,
                AccountReasonCode.ACC_OPENED, Instant.parse("2026-07-22T10:00:00Z"));
        when(accountRecords.findById("app-1234")).thenReturn(Optional.of(account));
        when(coreAttempts.findAllByApplicationIdOrderByOccurredAtAscIdAsc("app-1234")).thenReturn(List.of(
                new CoreAttempt("app-1234", 1, CoreAttemptKind.PROBE, CoreAttemptResult.MISS, "app-1234", 41L),
                new CoreAttempt("app-1234", 1, CoreAttemptKind.OPEN, CoreAttemptResult.CREATED, "app-1234", 212L)));
        when(overrides.findAllByApplicationIdOrderByOverriddenAtAscIdAsc("app-1234"))
                .thenReturn(List.of());

        CaseDetailView view = service.getCaseDetail("app-1234");

        assertThat(view.accountId()).isEqualTo("CC-0058291");
        assertThat(view.creditAmount()).isEqualTo(2800);
        assertThat(view.agreementId()).isEqualTo("agr-000077");
        assertThat(view.attempts()).hasSize(2);
        assertThat(view.attempts().get(0).cycle()).isEqualTo(1);
        assertThat(view.attempts().get(0).kind()).isEqualTo(CoreAttemptKind.PROBE);
        assertThat(view.attempts().get(0).result()).isEqualTo(CoreAttemptResult.MISS);
        assertThat(view.attempts().get(1).kind()).isEqualTo(CoreAttemptKind.OPEN);
        assertThat(view.attempts().get(1).result()).isEqualTo(CoreAttemptResult.CREATED);
    }

    @Test
    void unknownApplicationIdThrowsCaseNotFound() {
        when(accountRecords.findById("does-not-exist")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getCaseDetail("does-not-exist"))
                .isInstanceOf(CaseNotFoundException.class);
    }

    @Test
    void getApplicantMapsTheOrchestratorsRawApplicationToTheSubsetView() {
        when(orchestratorClient.fetchApplication("app-1234")).thenReturn(Optional.of(Map.of(
                "channel", "MOBILE_APP",
                "applicant", Map.of("fullName", "Maria Nowak", "dateOfBirth", "1996-04-11"),
                "product", Map.of("productCode", "CREDIT_CARD_REWARDS", "requestedCreditLimit", 3000))));

        Optional<ApplicantView> view = service.getApplicant("app-1234");

        assertThat(view).isPresent();
        assertThat(view.get().fullName()).isEqualTo("Maria Nowak");
        assertThat(view.get().dateOfBirth()).isEqualTo("1996-04-11");
        assertThat(view.get().productCode()).isEqualTo("CREDIT_CARD_REWARDS");
        assertThat(view.get().requestedCreditLimit()).isEqualTo(3000);
        assertThat(view.get().channel()).isEqualTo("MOBILE_APP");
    }

    @Test
    void getApplicantPropagatesAnUnreachableOrchestratorAsEmpty() {
        when(orchestratorClient.fetchApplication("app-9999")).thenReturn(Optional.empty());

        assertThat(service.getApplicant("app-9999")).isEmpty();
    }
}
