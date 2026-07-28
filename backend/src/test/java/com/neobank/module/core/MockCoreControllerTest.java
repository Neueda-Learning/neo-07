package com.neobank.module.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level coverage of the mock core: non-idempotent open, probe hit/miss, and the dials that
 * apply to every call. A real {@code @SpringBootTest} rather than a slice, because the
 * non-idempotency checkpoint (UC-05 AC#2) means hitting the same singleton {@link MockCoreStore}
 * bean across two real requests, not a mocked collaborator.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MockCoreControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MockCoreStore store;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void resetDials() {
        // Tests share the singleton store bean across the Spring context — always leave the
        // dials as a fresh mock would have them (UC-05 AC#7), so test order cannot matter.
        store.updateDials(0L, 0.0, false, false);
    }

    @Test
    void openingTheSameReferenceTwiceCreatesTwoAccountsOverHttp() throws Exception {
        String body = objectMapper.writeValueAsString(new OpenCoreAccountRequest("acc-http-1", "CREDIT_CARD_REWARDS", 3000));

        String firstId = firstAccountId(body);
        String secondId = firstAccountId(body);

        assertThat(firstId).isNotEqualTo(secondId);
    }

    private String firstAccountId(String body) throws Exception {
        return objectMapper.readTree(
                mvc.perform(post("/core/card-accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString())
                .get("accountId").asText();
    }

    @Test
    void probeMissesAnUnknownReference() throws Exception {
        mvc.perform(get("/core/card-accounts").param("reference", "never-opened-http"))
                .andExpect(status().isNotFound());
    }

    @Test
    void probeHitsAfterAnAccountIsOpened() throws Exception {
        String body = objectMapper.writeValueAsString(new OpenCoreAccountRequest("acc-http-probe", null, null));
        mvc.perform(post("/core/card-accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mvc.perform(get("/core/card-accounts").param("reference", "acc-http-probe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value("acc-http-probe"));
    }

    @Test
    void killSwitchOnMakesOpenAndProbeFail() throws Exception {
        mvc.perform(put("/core/admin/dials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"killSwitch\":true}"))
                .andExpect(status().isOk());

        String body = objectMapper.writeValueAsString(new OpenCoreAccountRequest("acc-killswitch", null, null));
        mvc.perform(post("/core/card-accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(get("/core/card-accounts").param("reference", "acc-killswitch"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void failureRateOneMakesOpenFailDeterministically() throws Exception {
        mvc.perform(put("/core/admin/dials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"failureRate\":1.0}"))
                .andExpect(status().isOk());

        String body = objectMapper.writeValueAsString(new OpenCoreAccountRequest("acc-failrate", null, null));
        mvc.perform(post("/core/card-accounts").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void timeoutTrapCommitsTheAccountBeforeGoingQuiet() throws Exception {
        // UC-05 AC#6 checkpoint, exercised over real HTTP: the trapped POST is fired on a
        // background thread (it will not return until mock-core.timeout-trap-silence-ms
        // elapses, set to 300ms for this test profile) while the test polls for the account to
        // already exist in the store — proving the write happens before the silence, not after.
        mvc.perform(put("/core/admin/dials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeoutTrap\":true}"))
                .andExpect(status().isOk());

        String body = objectMapper.writeValueAsString(new OpenCoreAccountRequest("acc-trap", null, null));
        Thread requestThread = new Thread(() -> {
            try {
                mvc.perform(post("/core/card-accounts").contentType(MediaType.APPLICATION_JSON).content(body));
            } catch (Exception ignored) {
                // The assertions below are what this test checks; a client-side hiccup on a
                // thread we do not wait for is not this test's concern.
            }
        });
        requestThread.start();
        try {
            boolean committedBeforeResponse = pollForAccount("acc-trap", 200);
            assertThat(committedBeforeResponse).isTrue();
        } finally {
            requestThread.join(TimeUnit.SECONDS.toMillis(5));
        }
    }

    private boolean pollForAccount(String reference, long withinMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + withinMillis;
        while (System.currentTimeMillis() < deadline) {
            if (store.probe(reference).isPresent()) {
                return true;
            }
            Thread.sleep(10);
        }
        return store.probe(reference).isPresent();
    }
}
