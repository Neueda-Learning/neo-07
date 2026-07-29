package com.neobank.module.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.module.core.MockCoreStore;
import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountReasonCode;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.model.CoreAttempt;
import com.neobank.module.model.CoreAttemptKind;
import com.neobank.module.model.CoreAttemptResult;
import com.neobank.module.model.CoreConfig;
import com.neobank.module.repository.AccountRecordRepository;
import com.neobank.module.repository.CoreAttemptRepository;
import com.neobank.module.repository.CoreConfigRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * UC-02/UC-04 end to end, over a real HTTP port: the <em>initial</em> account-opening path —
 * intake through {@code AccountOpeningService}/{@code AccountOpeningEngine} against a real mock
 * core with the kill switch on — reaching {@code FAILED}/{@code ACC_CORE_UNAVAILABLE} and landing
 * in the Failed-Opens Queue.
 *
 * <p>This is the one path {@code FailedOpensQueueEndToEndTest} deliberately does not cover: every
 * test there seeds an already-{@code FAILED} row directly and only drives the retry call. Before
 * this test existed, the probe-unguarded-exception bug in {@code AccountOpeningEngine} (fixed in
 * this same change set) could have shipped with every other automated test green — nothing drove
 * a real core outage through the real intake flow. {@code POST /api/v1/applications} answers
 * {@code 202} and does the actual work off-thread, so this test polls for the async outcome
 * instead of asserting inline.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountOpeningEndToEndTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MockCoreStore store;

    @Autowired
    private AccountRecordRepository accountRecords;

    @Autowired
    private CoreAttemptRepository coreAttempts;

    @Autowired
    private CoreConfigRepository coreConfigs;

    @LocalServerPort
    private int port;

    @AfterEach
    void resetDials() {
        store.updateDials(0L, 0.0, false, false);
    }

    /** A CoreConfig version whose base URL is this very test's own running instance. */
    private void pinConfigToSelf(int retryBudget) throws Exception {
        int nextVersion = coreConfigs.findTopByOrderByVersionDesc().map(CoreConfig::getVersion).orElse(0) + 1;
        JsonNode catalogue = new ObjectMapper().readTree("{}");
        coreConfigs.save(new CoreConfig(nextVersion, retryBudget, 500, "http://localhost:" + port, catalogue));
    }

    private String applicationRequestBody(String applicationId) {
        return """
                {
                  "applicationId": "%s",
                  "correlationId": "corr-e2e",
                  "command": "process-application",
                  "application": {
                    "channel": "MOBILE_APP",
                    "applicant": {"fullName": "Maria Nowak"},
                    "product": {"productCode": "CREDIT_CARD_REWARDS", "requestedCreditLimit": 3000}
                  }
                }
                """.formatted(applicationId);
    }

    @Test
    void realCoreOutageDuringIntakeEndsFailedAndLandsInTheQueue() throws Exception {
        pinConfigToSelf(3);
        store.updateDials(0L, 0.0, true, false); // kill switch on: every real core call answers ERROR
        String applicationId = "e2e-intake-core-down";

        mvc.perform(post("/api/v1/applications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(applicationRequestBody(applicationId)))
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            AccountRecord account = accountRecords.findById(applicationId).orElseThrow();
            assertThat(account.getOutcome()).isEqualTo(AccountOutcome.FAILED);
        });

        AccountRecord account = accountRecords.findById(applicationId).orElseThrow();
        assertThat(account.getReasonCode()).isEqualTo(AccountReasonCode.ACC_CORE_UNAVAILABLE);
        assertThat(account.getAccountId()).isNull();

        // 3 cycles x (PROBE + OPEN), every one of them ERROR — exactly AC2's checkpoint shape,
        // and exactly the sequence the original unguarded-probe bug never let a case reach.
        List<CoreAttempt> attempts = coreAttempts.findAllByApplicationIdOrderByOccurredAtAscIdAsc(applicationId);
        assertThat(attempts).hasSize(6);
        for (int cycle = 0; cycle < 3; cycle++) {
            CoreAttempt probeAttempt = attempts.get(cycle * 2);
            CoreAttempt openAttempt = attempts.get(cycle * 2 + 1);
            assertThat(probeAttempt.getKind()).isEqualTo(CoreAttemptKind.PROBE);
            assertThat(probeAttempt.getResult()).isEqualTo(CoreAttemptResult.ERROR);
            assertThat(openAttempt.getKind()).isEqualTo(CoreAttemptKind.OPEN);
            assertThat(openAttempt.getResult()).isEqualTo(CoreAttemptResult.ERROR);
        }

        mvc.perform(get("/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.applicationId=='" + applicationId + "')]").isNotEmpty());
    }
}
