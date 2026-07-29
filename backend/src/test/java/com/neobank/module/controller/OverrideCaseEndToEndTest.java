package com.neobank.module.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobank.module.integrations.orchestrator.OrchestratorClient;
import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountReasonCode;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.model.CoreAttempt;
import com.neobank.module.model.CoreAttemptKind;
import com.neobank.module.model.CoreAttemptResult;
import com.neobank.module.model.Decision;
import com.neobank.module.repository.AccountRecordRepository;
import com.neobank.module.repository.CoreAttemptRepository;
import com.neobank.module.repository.OverrideLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** UC-07 through the real MVC, transaction, repositories and callback boundary. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class OverrideCaseEndToEndTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AccountRecordRepository accounts;

    @Autowired
    private CoreAttemptRepository attempts;

    @Autowired
    private OverrideLogRepository overrides;

    @MockBean
    private OrchestratorClient orchestrator;

    @Test
    void overrideUpdatesAuditsCallbacksOnceAndNeverTouchesAttempts() throws Exception {
        AccountRecord account = new AccountRecord("override-e2e", "acc-override-e2e");
        account.fail(AccountReasonCode.ACC_CORE_UNAVAILABLE);
        accounts.save(account);
        attempts.save(new CoreAttempt(
                "override-e2e",
                1,
                CoreAttemptKind.PROBE,
                CoreAttemptResult.ERROR,
                "override-e2e",
                31L));

        String body = """
                {
                  "newOutcome": "OPENED",
                  "accountId": "CC-CONFIRMED",
                  "reason": "account confirmed by Core team ticket CORE-4411",
                  "operator": "b.dimovski"
                }
                """;

        mvc.perform(post("/cases/override-e2e/override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("OPENED"))
                .andExpect(jsonPath("$.accountId").value("CC-CONFIRMED"))
                .andExpect(jsonPath("$.attempts.length()").value(1))
                .andExpect(jsonPath("$.overrides.length()").value(1))
                .andExpect(jsonPath("$.overrides[0].oldOutcome").value("FAILED"))
                .andExpect(jsonPath("$.overrides[0].newOutcome").value("OPENED"))
                .andExpect(jsonPath("$.overrides[0].operator").value("b.dimovski"));

        assertThat(attempts.countByApplicationId("override-e2e")).isEqualTo(1);
        assertThat(overrides.findAllByApplicationIdOrderByOverriddenAtAscIdAsc("override-e2e"))
                .hasSize(1);
        verify(orchestrator).applicationStatusUpdate(
                eq("override-e2e"),
                eq(Decision.ACCEPTED),
                contains("manual override"));

        mvc.perform(post("/cases/override-e2e/override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overrides.length()").value(1));

        assertThat(attempts.countByApplicationId("override-e2e")).isEqualTo(1);
        assertThat(overrides.findAllByApplicationIdOrderByOverriddenAtAscIdAsc("override-e2e"))
                .hasSize(1);
        verify(orchestrator, times(1)).applicationStatusUpdate(
                eq("override-e2e"),
                eq(Decision.ACCEPTED),
                contains("manual override"));
    }

    @Test
    void missingMandatoryFieldsIsBadRequest() throws Exception {
        mvc.perform(post("/cases/override-e2e/override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newOutcome\":\"FAILED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("reason")));
    }

    @Test
    void openedWithoutAccountIdIsBadRequest() throws Exception {
        AccountRecord account = new AccountRecord("override-no-account", "acc-override-no-account");
        account.fail(AccountReasonCode.ACC_CORE_UNAVAILABLE);
        accounts.save(account);

        mvc.perform(post("/cases/override-no-account/override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "newOutcome": "OPENED",
                                  "reason": "confirmed",
                                  "operator": "operator-1"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("accountId")));
    }

    @Test
    void unknownCaseIsNotFound() throws Exception {
        mvc.perform(post("/cases/does-not-exist/override")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "newOutcome": "FAILED",
                                  "reason": "confirmed",
                                  "operator": "operator-1"
                                }
                                """))
                .andExpect(status().isNotFound());
    }
}
