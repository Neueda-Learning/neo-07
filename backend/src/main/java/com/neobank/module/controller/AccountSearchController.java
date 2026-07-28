package com.neobank.module.controller;

import com.neobank.module.dto.AccountSearchResponse;
import com.neobank.module.integrations.orchestrator.OrchestratorClient;
import com.neobank.module.service.AccountSearchService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * UC-01 — Search Accounts: the Account Board's backend.
 *
 * <p>Separate from {@link ApplicationController} on purpose — that one is the fixed contract
 * surface the orchestrator calls; this one is this module's own operator screen, named after the
 * board it feeds rather than the inbound envelope.</p>
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountSearchController {

    private final AccountSearchService search;
    private final OrchestratorClient orchestrator;

    public AccountSearchController(AccountSearchService search, OrchestratorClient orchestrator) {
        this.search = search;
        this.orchestrator = orchestrator;
    }

    /**
     * {@code GET /api/v1/accounts/search?q=}. Empty/absent {@code q} answers the empty board (AC1)
     * rather than an error — a blank query is a valid, if uninteresting, one.
     */
    @GetMapping("/search")
    public AccountSearchResponse search(@RequestParam(required = false) String q) {
        return search.search(q);
    }

    /**
     * Hydrates one row's applicant name, live, for the board (AC4) — the same proxy UC-03's
     * sidebar will use. Never a 500: an unreachable orchestrator answers {@code 502} with a JSON
     * body so the UI can show a retryable placeholder instead of breaking the row (AC6).
     */
    @GetMapping("/{applicationId}/applicant")
    public ResponseEntity<Object> applicant(@PathVariable String applicationId) {
        return orchestrator.fetchApplication(applicationId)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(errorBody(
                        HttpStatus.BAD_GATEWAY,
                        "applicant lookup unavailable — the orchestrator did not answer")));
    }

    private Map<String, Object> errorBody(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return body;
    }
}
