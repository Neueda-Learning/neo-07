package com.neobank.module.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobank.module.core.MockCoreStore;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.model.CoreConfig;
import com.neobank.module.repository.AccountRecordRepository;
import com.neobank.module.repository.CoreConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * UC-06 end to end, over the real mock core: plants a duplicate straight into
 * {@link MockCoreStore} the way AC4 describes — bypassing this module's own write path entirely
 * — and proves the report's {@link com.neobank.module.service.CoreOpsClient} call surfaces it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DuplicateReportEndToEndTest {

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

    @BeforeEach
    void pinCurrentConfigToThisInstance() throws Exception {
        // findTopByOrderByVersionDesc() must resolve to THIS running instance, not whatever
        // coreBaseUrl the seed row (or another test's context) happens to carry.
        int nextVersion = coreConfigs.findTopByOrderByVersionDesc().map(CoreConfig::getVersion).orElse(0) + 1;
        coreConfigs.save(new CoreConfig(nextVersion, 3, 2000, "http://localhost:" + port,
                new ObjectMapper().readTree("{}")));
    }

    @AfterEach
    void resetDials() {
        store.updateDials(0L, 0.0, false, false);
    }

    private void plantAccount(String applicationId) throws Exception {
        mvc.perform(post("/core/card-accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"" + applicationId
                                + "\",\"productCode\":\"CREDIT_CARD_REWARDS\",\"creditAmount\":1000}"))
                .andExpect(status().isCreated());
    }

    @Test
    void aReferenceOpenedTwiceAtTheCoreSurfacesAsADuplicateLinkedToItsCase() throws Exception {
        String applicationId = "e2e-dup-" + UUID.randomUUID();
        plantAccount(applicationId);
        plantAccount(applicationId); // bypassing the module — the mock is non-idempotent

        AccountRecord record = new AccountRecord(applicationId, "acc-" + applicationId);
        accountRecords.save(record);

        mvc.perform(get("/reports/duplicates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicates[?(@.applicationId=='" + applicationId + "')].reference")
                        .value("acc-" + applicationId))
                .andExpect(jsonPath("$.duplicates[?(@.applicationId=='" + applicationId + "')].coreAccountIds[0]")
                        .exists());
    }

    @Test
    void aReferenceOpenedOnceIsNeverReportedAsADuplicate() throws Exception {
        String applicationId = "e2e-solo-" + UUID.randomUUID();
        plantAccount(applicationId);

        mvc.perform(get("/reports/duplicates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicates[?(@.applicationId=='" + applicationId + "')]").isEmpty());
    }

    @Test
    void aDuplicateWithNoModuleRecordStillSurfacesWithANullReference() throws Exception {
        String applicationId = "e2e-orphan-" + UUID.randomUUID();
        plantAccount(applicationId);
        plantAccount(applicationId);

        mvc.perform(get("/reports/duplicates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicates[?(@.applicationId=='" + applicationId + "')].reference")
                        .value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void coreUnreachableIs503NeverASilentlyEmptyReport() throws Exception {
        mvc.perform(put("/core/admin/dials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"killSwitch\":true}"))
                .andExpect(status().isOk());

        mvc.perform(get("/reports/duplicates"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cannot verify")));
    }
}
