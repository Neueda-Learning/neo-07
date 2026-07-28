package com.neobank.module.service;

import com.neobank.module.dto.ApplicantView;
import com.neobank.module.dto.CaseDetailView;
import com.neobank.module.integrations.orchestrator.OrchestratorClient;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.model.CaseNotFoundException;
import com.neobank.module.repository.AccountRecordRepository;
import com.neobank.module.repository.CoreAttemptRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-02 — Review Case + Attempt Log (read side) and UC-03 — View Applicant.
 *
 * <p>{@code getCaseDetail} replays what {@link AccountOpeningService} already wrote — it never
 * talks to the core, and it never re-decides. {@code getApplicant} reuses UC-01's own orchestrator
 * proxy ({@link OrchestratorClient#fetchApplication}) rather than adding a second client for the
 * same call; nothing it returns is ever stored (AC#3).</p>
 */
@Service
public class CaseService {

    private final AccountRecordRepository accountRecords;
    private final CoreAttemptRepository coreAttempts;
    private final OrchestratorClient orchestratorClient;

    public CaseService(AccountRecordRepository accountRecords, CoreAttemptRepository coreAttempts,
            OrchestratorClient orchestratorClient) {
        this.accountRecords = accountRecords;
        this.coreAttempts = coreAttempts;
        this.orchestratorClient = orchestratorClient;
    }

    @Transactional(readOnly = true)
    public CaseDetailView getCaseDetail(String applicationId) {
        AccountRecord account = accountRecords.findById(applicationId)
                .orElseThrow(() -> new CaseNotFoundException(applicationId));
        return CaseDetailView.of(account, coreAttempts.findAllByApplicationIdOrderByOccurredAtAscIdAsc(applicationId));
    }

    /** Empty means the orchestrator did not answer — the controller renders that as a 502. */
    public Optional<ApplicantView> getApplicant(String applicationId) {
        return orchestratorClient.fetchApplication(applicationId).map(ApplicantView::of);
    }
}
