package com.neobank.module.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neobank.module.dto.CaseDetailView;
import com.neobank.module.dto.OverrideCaseRequest;
import com.neobank.module.integrations.orchestrator.OrchestratorClient;
import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.Decision;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OverrideCaseServiceTest {

    private OverridePersistenceService persistence;
    private CaseService cases;
    private OrchestratorClient orchestrator;
    private OverrideCaseService service;

    @BeforeEach
    void setUp() {
        persistence = mock(OverridePersistenceService.class);
        cases = mock(CaseService.class);
        orchestrator = mock(OrchestratorClient.class);
        service = new OverrideCaseService(persistence, cases, orchestrator);
    }

    @Test
    void openedOverrideFiresAcceptedCallbackAfterPersistence() {
        OverrideCaseRequest request =
                new OverrideCaseRequest(AccountOutcome.OPENED, "confirmed", "operator-1", "CC-1");
        CaseDetailView updated = new CaseDetailView(
                AccountOutcome.OPENED,
                "acc-1",
                "CC-1",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of());
        when(persistence.apply("app-1", request))
                .thenReturn(new OverridePersistenceService.OverrideResult(AccountOutcome.OPENED, true));
        when(cases.getCaseDetail("app-1")).thenReturn(updated);

        assertThat(service.override("app-1", request)).isSameAs(updated);

        verify(orchestrator).applicationStatusUpdate(
                "app-1", Decision.ACCEPTED, "manual override by operator-1: confirmed");
    }

    @Test
    void failedOverrideFiresReferredCallback() {
        OverrideCaseRequest request =
                new OverrideCaseRequest(AccountOutcome.FAILED, "not the customers account", "operator-2", null);
        when(persistence.apply("app-2", request))
                .thenReturn(new OverridePersistenceService.OverrideResult(AccountOutcome.FAILED, true));

        service.override("app-2", request);

        verify(orchestrator).applicationStatusUpdate(
                "app-2", Decision.REFERRED, "manual override by operator-2: not the customers account");
    }

    @Test
    void exactReplayDoesNotFireASecondCallback() {
        OverrideCaseRequest request =
                new OverrideCaseRequest(AccountOutcome.OPENED, "confirmed", "operator-1", "CC-1");
        when(persistence.apply("app-1", request))
                .thenReturn(new OverridePersistenceService.OverrideResult(AccountOutcome.OPENED, false));

        service.override("app-1", request);

        verify(orchestrator, never()).applicationStatusUpdate(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                contains("manual override"));
    }
}
