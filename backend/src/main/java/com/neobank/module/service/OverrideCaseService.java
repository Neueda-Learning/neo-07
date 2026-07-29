package com.neobank.module.service;

import com.neobank.module.dto.CaseDetailView;
import com.neobank.module.dto.OverrideCaseRequest;
import com.neobank.module.integrations.orchestrator.OrchestratorClient;
import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.Decision;
import org.springframework.stereotype.Service;

/** UC-07 orchestration: commit the correction, notify the orchestrator, return the updated case. */
@Service
public class OverrideCaseService {

    private final OverridePersistenceService persistence;
    private final CaseService cases;
    private final OrchestratorClient orchestrator;

    public OverrideCaseService(OverridePersistenceService persistence, CaseService cases,
                               OrchestratorClient orchestrator) {
        this.persistence = persistence;
        this.cases = cases;
        this.orchestrator = orchestrator;
    }

    public CaseDetailView override(String applicationId, OverrideCaseRequest request) {
        OverridePersistenceService.OverrideResult result = persistence.apply(applicationId, request);
        if (result.changed()) {
            Decision decision = result.outcome() == AccountOutcome.OPENED
                    ? Decision.ACCEPTED
                    : Decision.REFERRED;
            orchestrator.applicationStatusUpdate(
                    applicationId,
                    decision,
                    "manual override by " + request.operator() + ": " + request.reason());
        }
        return cases.getCaseDetail(applicationId);
    }
}
