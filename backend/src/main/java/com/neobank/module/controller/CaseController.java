package com.neobank.module.controller;

import com.neobank.module.dto.ApplicantView;
import com.neobank.module.dto.CaseDetailView;
import com.neobank.module.service.CaseService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * UC-02 — Review Case + Attempt Log, UC-03 — View Applicant. The Account Detail screen's backend.
 */
@RestController
@RequestMapping("/cases")
public class CaseController {

    private final CaseService cases;

    public CaseController(CaseService cases) {
        this.cases = cases;
    }

    /** {@code GET /cases/{applicationId}}: the anchor + its ordered attempt log (UC-02 AC#1, AC#8). */
    @GetMapping("/{applicationId}")
    public CaseDetailView getCase(@PathVariable String applicationId) {
        return cases.getCaseDetail(applicationId);
    }

    /**
     * {@code GET /cases/{applicationId}/applicant}: the sidebar proxy (UC-03 AC#1). Never a 500 —
     * an unreachable orchestrator answers {@code 502} with a JSON body so the UI can show a
     * retryable state instead (AC#4), matching {@code AccountSearchController}'s own convention.
     */
    @GetMapping("/{applicationId}/applicant")
    public ResponseEntity<Object> getApplicant(@PathVariable String applicationId) {
        return cases.getApplicant(applicationId)
                .<ResponseEntity<Object>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiErrorBody.of(
                        HttpStatus.BAD_GATEWAY,
                        "applicant lookup unavailable — the orchestrator did not answer")));
    }
}
