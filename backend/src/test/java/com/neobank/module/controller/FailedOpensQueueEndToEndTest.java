package com.neobank.module.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.module.core.MockCoreStore;
import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountReasonCode;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.model.CoreConfig;
import com.neobank.module.repository.AccountRecordRepository;
import com.neobank.module.repository.CoreConfigRepository;
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
 * UC-04 end to end, over a real HTTP port: this module's own {@link com.neobank.module.service.CoreOpsClient}
 * calling the real mock core, exactly the way it will over the wire in the running app. A real
 * {@code @SpringBootTest} on a random port rather than a slice — {@code CoreOpsClient} makes genuine
 * socket calls, which a pure {@code MockMvc} test cannot exercise.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FailedOpensQueueEndToEndTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MockCoreStore store;

    @Autowired
    private AccountRecordRepository accountRecords;

    @Autowired
    private CoreConfigRepository coreConfigs;

    @LocalServerPort
    private int port;

    @AfterEach
    void resetDials() {
        store.updateDials(0L, 0.0, false, false);
    }

    /** A CoreConfig version whose base URL is this very test's own running instance. */
    private Integer pinConfigToSelf(int retryBudget) throws Exception {
        int nextVersion = coreConfigs.findTopByOrderByVersionDesc().map(CoreConfig::getVersion).orElse(0) + 1;
        JsonNode catalogue = new ObjectMapper().readTree("{}");
        CoreConfig config = new CoreConfig(nextVersion, retryBudget, 500, "http://localhost:" + port, catalogue);
        coreConfigs.save(config);
        return nextVersion;
    }

    private AccountRecord seedFailedCase(String applicationId, Integer configVersion) {
        AccountRecord record = new AccountRecord(applicationId, "acc-" + applicationId);
        record.pinCoreConfig(configVersion);
        record.markFailed(AccountReasonCode.ACC_CORE_UNAVAILABLE);
        return accountRecords.save(record);
    }

    @Test
    void retryOverRealHttpOpensTheAccountAndDropsTheCaseFromTheQueue() throws Exception {
        Integer configVersion = pinConfigToSelf(3);
        seedFailedCase("e2e-retry-success", configVersion);

        mvc.perform(get("/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.applicationId=='e2e-retry-success')]").isNotEmpty());

        mvc.perform(post("/cases/e2e-retry-success/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("retrying"));

        AccountRecord after = accountRecords.findById("e2e-retry-success").orElseThrow();
        assertThat(after.getOutcome()).isEqualTo(AccountOutcome.OPENED);
        assertThat(after.getReasonCode()).isEqualTo(AccountReasonCode.ACC_OPENED);
        assertThat(after.getAccountId()).isNotBlank();

        mvc.perform(get("/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.applicationId=='e2e-retry-success')]").isEmpty());
    }

    @Test
    void retryWhileTheCoreIsStillDownStaysFailedAndAppendsAttempts() throws Exception {
        Integer configVersion = pinConfigToSelf(2);
        seedFailedCase("e2e-retry-still-down", configVersion);

        mvc.perform(put("/core/admin/dials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"killSwitch\":true}"))
                .andExpect(status().isOk());

        mvc.perform(post("/cases/e2e-retry-still-down/retry"))
                .andExpect(status().isAccepted());

        AccountRecord after = accountRecords.findById("e2e-retry-still-down").orElseThrow();
        assertThat(after.getOutcome()).isEqualTo(AccountOutcome.FAILED);
        assertThat(after.getReasonCode()).isEqualTo(AccountReasonCode.ACC_CORE_UNAVAILABLE);

        mvc.perform(get("/queue"))
                .andExpect(jsonPath("$[?(@.applicationId=='e2e-retry-still-down')].attemptCount")
                        .value(org.hamcrest.Matchers.contains(4)));
    }

    @Test
    void retryOnUnknownCaseIs404OverRealMvc() throws Exception {
        mvc.perform(post("/cases/does-not-exist/retry")).andExpect(status().isNotFound());
    }

    @Test
    void retryOnAnAlreadyOpenedCaseIs400() throws Exception {
        Integer configVersion = pinConfigToSelf(3);
        AccountRecord record = new AccountRecord("e2e-already-opened", "acc-e2e-already-opened");
        record.pinCoreConfig(configVersion);
        record.markOpened("CC-EXISTING", AccountReasonCode.ACC_OPENED);
        accountRecords.save(record);

        mvc.perform(post("/cases/e2e-already-opened/retry")).andExpect(status().isBadRequest());
    }
}
