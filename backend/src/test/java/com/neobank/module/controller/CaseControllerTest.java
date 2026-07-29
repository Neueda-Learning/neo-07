package com.neobank.module.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobank.module.dto.ApplicantView;
import com.neobank.module.dto.CaseDetailView;
import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.CaseNotFoundException;
import com.neobank.module.model.CoreAttemptKind;
import com.neobank.module.model.CoreAttemptResult;
import com.neobank.module.service.CaseService;
import com.neobank.module.service.OverrideCaseService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** UC-02/UC-03's HTTP surface: the case detail GET and the applicant proxy GET. */
@WebMvcTest(CaseController.class)
class CaseControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private CaseService cases;

    @MockBean
    private OverrideCaseService overrides;

    @Test
    void getCaseReturnsTheFullContractShape() throws Exception {
        CaseDetailView view = new CaseDetailView(
                AccountOutcome.OPENED, "acc-000123", "CC-0058291", 2800, "agr-000077",
                "CREDIT_CARD_REWARDS", 1, "cus-000101", "crd-000064", 1,
                List.of(
                        new CaseDetailView.AttemptView(1, CoreAttemptKind.PROBE, CoreAttemptResult.MISS, 41),
                        new CaseDetailView.AttemptView(1, CoreAttemptKind.OPEN, CoreAttemptResult.CREATED, 212)),
                List.of());
        when(cases.getCaseDetail("app-1234")).thenReturn(view);

        mvc.perform(get("/cases/app-1234"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("OPENED"))
                .andExpect(jsonPath("$.accountId").value("CC-0058291"))
                .andExpect(jsonPath("$.creditAmount").value(2800))
                .andExpect(jsonPath("$.agreementId").value("agr-000077"))
                .andExpect(jsonPath("$.coreConfigVersion").value(1))
                .andExpect(jsonPath("$.attempts", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(jsonPath("$.attempts[0].cycle").value(1))
                .andExpect(jsonPath("$.attempts[0].kind").value("PROBE"))
                .andExpect(jsonPath("$.attempts[0].result").value("MISS"))
                .andExpect(jsonPath("$.attempts[1].kind").value("OPEN"))
                .andExpect(jsonPath("$.attempts[1].result").value("CREATED"));
    }

    @Test
    void unknownApplicationIdIsA404NotA500() throws Exception {
        when(cases.getCaseDetail("does-not-exist")).thenThrow(new CaseNotFoundException("does-not-exist"));

        mvc.perform(get("/cases/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void getApplicantReturnsTheSidebarSubset() throws Exception {
        when(cases.getApplicant("app-1234")).thenReturn(Optional.of(
                new ApplicantView("Maria Nowak", "1996-04-11", "CREDIT_CARD_REWARDS", 3000, "MOBILE_APP")));

        mvc.perform(get("/cases/app-1234/applicant"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Maria Nowak"))
                .andExpect(jsonPath("$.productCode").value("CREDIT_CARD_REWARDS"))
                .andExpect(jsonPath("$.requestedCreditLimit").value(3000));
    }

    @Test
    void anUnreachableOrchestratorIsA502NotA500() throws Exception {
        when(cases.getApplicant("app-9999")).thenReturn(Optional.empty());

        mvc.perform(get("/cases/app-9999/applicant"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value(502))
                .andExpect(jsonPath("$.message").exists());
    }
}
