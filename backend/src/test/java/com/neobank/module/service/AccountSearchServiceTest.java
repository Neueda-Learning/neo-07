package com.neobank.module.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.neobank.module.dto.AccountSearchResponse;
import com.neobank.module.integrations.orchestrator.OrchestratorClient;
import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.repository.AccountRecordRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * UC-01: empty by default, id search, name search via the orchestrator, the 10-row cap and its
 * "more" flag — all without a database or an HTTP call.
 */
class AccountSearchServiceTest {

    private AccountRecordRepository accountRecords;
    private OrchestratorClient orchestrator;
    private AccountSearchService service;

    @BeforeEach
    void setUp() {
        accountRecords = mock(AccountRecordRepository.class);
        orchestrator = mock(OrchestratorClient.class);
        service = new AccountSearchService(accountRecords, orchestrator);
    }

    private static AccountRecord row(String applicationId, AccountOutcome outcome,
                                     Instant createdAt) {
        AccountRecord row = new AccountRecord(applicationId, "acc-" + applicationId);
        ReflectionTestUtils.setField(row, "outcome", outcome);
        ReflectionTestUtils.setField(row, "createdAt", createdAt);
        return row;
    }

    @Test
    void aBlankQueryNeverFetchesAnything() {
        AccountSearchResponse response = service.search("   ");

        assertThat(response).isEqualTo(AccountSearchResponse.EMPTY);
        verifyNoInteractions(accountRecords, orchestrator);
    }

    @Test
    void aMissingQueryNeverFetchesAnything() {
        AccountSearchResponse response = service.search(null);

        assertThat(response).isEqualTo(AccountSearchResponse.EMPTY);
        verifyNoInteractions(accountRecords, orchestrator);
    }

    @Test
    void searchesLocallyByApplicationId() {
        when(accountRecords.findTop11ByApplicationIdContainingIgnoreCaseOrderByCreatedAtDesc("app-1234"))
                .thenReturn(List.of(row("app-1234", AccountOutcome.OPENED, Instant.now())));

        AccountSearchResponse response = service.search("app-1234");

        assertThat(response.hasMore()).isFalse();
        assertThat(response.results()).singleElement()
                .satisfies(r -> assertThat(r.applicationId()).isEqualTo("app-1234"));
        verifyNoInteractions(orchestrator);
    }

    @Test
    void resolvesANameToIdsThroughTheOrchestratorThenReadsThemLocally() {
        when(accountRecords.findTop11ByApplicationIdContainingIgnoreCaseOrderByCreatedAtDesc("Maria"))
                .thenReturn(List.of());
        when(orchestrator.searchApplicationIdsByName("Maria")).thenReturn(List.of("app-1234"));
        when(accountRecords.findByApplicationIdInOrderByCreatedAtDesc(List.of("app-1234")))
                .thenReturn(List.of(row("app-1234", AccountOutcome.OPENED, Instant.now())));

        AccountSearchResponse response = service.search("Maria");

        assertThat(response.results()).singleElement()
                .satisfies(r -> {
                    assertThat(r.applicationId()).isEqualTo("app-1234");
                    assertThat(r.outcome()).isEqualTo(AccountOutcome.OPENED);
                });
    }

    @Test
    void aLocalIdMatchDoesNotFallThroughToNameSearch() {
        AccountRecord shared = row("app-1234", AccountOutcome.OPENED, Instant.now());
        when(accountRecords.findTop11ByApplicationIdContainingIgnoreCaseOrderByCreatedAtDesc(anyString()))
                .thenReturn(List.of(shared));

        AccountSearchResponse response = service.search("app-1234");

        assertThat(response.results()).hasSize(1);
        verifyNoInteractions(orchestrator);
    }

    @Test
    void doesNotCallTheOrchestratorWhenLocalIdSearchAlreadyOverflows() {
        when(accountRecords.findTop11ByApplicationIdContainingIgnoreCaseOrderByCreatedAtDesc(anyString()))
                .thenReturn(elevenRows());

        AccountSearchResponse response = service.search("app-");

        assertThat(response.hasMore()).isTrue();
        assertThat(response.results()).hasSize(10);
        verifyNoInteractions(orchestrator);
    }

    @Test
    void noMatchesAnywhereIsAnEmptyListNotAnError() {
        when(accountRecords.findTop11ByApplicationIdContainingIgnoreCaseOrderByCreatedAtDesc(anyString()))
                .thenReturn(List.of());
        when(orchestrator.searchApplicationIdsByName(anyString())).thenReturn(List.of());

        AccountSearchResponse response = service.search("nobody");

        assertThat(response.results()).isEmpty();
        assertThat(response.hasMore()).isFalse();
    }

    private static List<AccountRecord> elevenRows() {
        Instant now = Instant.now();
        return java.util.stream.IntStream.range(0, 11)
                .mapToObj(i -> row("app-" + i, AccountOutcome.OPENED, now.minusSeconds(i)))
                .toList();
    }
}
