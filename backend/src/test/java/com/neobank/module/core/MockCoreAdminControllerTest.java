package com.neobank.module.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** UC-05 — the Core Control Panel's backend: GET reflects PUT, and updates are partial. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MockCoreAdminControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private MockCoreStore store;

    @AfterEach
    void resetDials() {
        store.updateDials(0L, 0.0, false, false);
    }

    @Test
    void freshStoreDialsAreAllOff() {
        // UC-05 AC#7 — proven on a bean constructed directly rather than by restarting the app.
        MockCoreDials fresh = new MockCoreStore().dials();

        assertThat(fresh.latencyMs()).isZero();
        assertThat(fresh.failureRate()).isZero();
        assertThat(fresh.killSwitch()).isFalse();
        assertThat(fresh.timeoutTrap()).isFalse();
    }

    @Test
    void getReflectsWhatWasPut() throws Exception {
        mvc.perform(put("/core/admin/dials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latencyMs\":250,\"failureRate\":0.25,\"killSwitch\":true,\"timeoutTrap\":true}"))
                .andExpect(status().isOk());

        mvc.perform(get("/core/admin/dials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latencyMs").value(250))
                .andExpect(jsonPath("$.failureRate").value(0.25))
                .andExpect(jsonPath("$.killSwitch").value(true))
                .andExpect(jsonPath("$.timeoutTrap").value(true));
    }

    @Test
    void partialUpdateOnlyChangesTheSuppliedFields() throws Exception {
        mvc.perform(put("/core/admin/dials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latencyMs\":100,\"failureRate\":0.5}"))
                .andExpect(status().isOk());

        mvc.perform(put("/core/admin/dials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"timeoutTrap\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latencyMs").value(100))
                .andExpect(jsonPath("$.failureRate").value(0.5))
                .andExpect(jsonPath("$.killSwitch").value(false))
                .andExpect(jsonPath("$.timeoutTrap").value(true));
    }

    @Test
    void failureRateOfOneIsAccepted() throws Exception {
        mvc.perform(put("/core/admin/dials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"failureRate\":1.0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureRate").value(1.0));
    }

    @Test
    void failureRateAboveOneIsRejected() throws Exception {
        mvc.perform(put("/core/admin/dials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"failureRate\":1.1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("failureRate")));

        // Rejected request must not have mutated the store.
        mvc.perform(get("/core/admin/dials"))
                .andExpect(jsonPath("$.failureRate").value(0.0));
    }

    @Test
    void negativeFailureRateIsRejected() throws Exception {
        mvc.perform(put("/core/admin/dials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"failureRate\":-0.1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("failureRate")));
    }

    @Test
    void negativeLatencyMsIsRejected() throws Exception {
        mvc.perform(put("/core/admin/dials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latencyMs\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("latencyMs")));

        mvc.perform(get("/core/admin/dials"))
                .andExpect(jsonPath("$.latencyMs").value(0));
    }

    @Test
    void latencyMsOfZeroIsAccepted() throws Exception {
        mvc.perform(put("/core/admin/dials").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latencyMs\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latencyMs").value(0));
    }
}
