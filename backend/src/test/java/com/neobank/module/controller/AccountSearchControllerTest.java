package com.neobank.module.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobank.module.dto.AccountSearchResponse;
import com.neobank.module.dto.AccountSearchResult;
import com.neobank.module.integrations.orchestrator.OrchestratorClient;
import com.neobank.module.model.AccountOutcome;
import com.neobank.module.service.AccountSearchService;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** UC-01's HTTP surface: the search endpoint and the per-row applicant proxy. */
@WebMvcTest(AccountSearchController.class)
class AccountSearchControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AccountSearchService search;

    @MockBean
    private OrchestratorClient orchestrator;

    @Test
    void noQueryAnswersTheEmptyBoard() throws Exception {
        when(search.search(isNull())).thenReturn(AccountSearchResponse.EMPTY);

        mvc.perform(get("/api/v1/accounts/search"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.results", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void aQueryReturnsWhateverTheServiceFound() throws Exception {
        when(search.search(eq("Maria"))).thenReturn(new AccountSearchResponse(
                java.util.List.of(new AccountSearchResult("app-1234", AccountOutcome.OPENED, 2800,
                        java.time.Instant.parse("2026-07-22T09:14:00Z"))),
                false));

        mvc.perform(get("/api/v1/accounts/search").param("q", "Maria"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].applicationId").value("app-1234"))
                .andExpect(jsonPath("$.results[0].outcome").value("OPENED"))
                .andExpect(jsonPath("$.results[0].creditAmount").value(2800))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void theApplicantProxyReturnsWhatTheOrchestratorSent() throws Exception {
        when(orchestrator.fetchApplication("app-1234"))
                .thenReturn(Optional.of(Map.of("applicationId", "app-1234")));

        mvc.perform(get("/api/v1/accounts/app-1234/applicant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value("app-1234"));
    }

    @Test
    void anUnreachableOrchestratorIsA502NotA500() throws Exception {
        when(orchestrator.fetchApplication("app-9999")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/accounts/app-9999/applicant"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").exists());
    }
}
