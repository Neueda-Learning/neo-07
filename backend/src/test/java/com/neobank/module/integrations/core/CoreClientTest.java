package com.neobank.module.integrations.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobank.module.core.MockCoreStore;
import com.neobank.module.model.CoreAttemptResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Real HTTP round-trip against the actual mock core (UC-05), not a mocked collaborator — proves
 * {@link CoreClient} translates real wire semantics (404/200/201/timeout/500) into the
 * {@link CoreAttemptResult} the engine expects. {@code webEnvironment = RANDOM_PORT} because
 * {@code CoreClient} builds its own {@code RestClient} against a base URL, unlike
 * {@code OrchestratorClient}'s injected bean — there is nothing here for {@code MockMvc} to bind to.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class CoreClientTest {

    @LocalServerPort
    private int port;

    @Autowired
    private CoreClient client;

    @Autowired
    private MockCoreStore store;

    @Value("${mock-core.timeout-trap-silence-ms}")
    private long timeoutTrapSilenceMs;

    private String baseUrl() {
        return "http://localhost:" + port;
    }

    @AfterEach
    void resetDials() {
        store.updateDials(0L, 0.0, false, false);
    }

    @Test
    void probeMissesWhenNoAccountExistsForTheReference() {
        CoreCallResult result = client.probe(baseUrl(), 2000, "core-client-test-miss");

        assertThat(result.result()).isEqualTo(CoreAttemptResult.MISS);
    }

    @Test
    void openCreatesAnAccountAndProbeThenHits() {
        CoreCallResult opened = client.open(baseUrl(), 2000, "core-client-test-hit", "CREDIT_CARD_REWARDS", 3000);
        assertThat(opened.result()).isEqualTo(CoreAttemptResult.CREATED);
        assertThat(opened.accountId()).isNotBlank();

        CoreCallResult probed = client.probe(baseUrl(), 2000, "core-client-test-hit");
        assertThat(probed.result()).isEqualTo(CoreAttemptResult.HIT);
        assertThat(probed.accountId()).isEqualTo(opened.accountId());
    }

    @Test
    void killSwitchTranslatesToError() {
        store.updateDials(0L, 0.0, true, false);

        CoreCallResult result = client.probe(baseUrl(), 2000, "core-client-test-killed");

        assertThat(result.result()).isEqualTo(CoreAttemptResult.ERROR);
    }

    @Test
    void timeoutTrapTranslatesToTimeoutOnOpen() {
        store.updateDials(0L, 0.0, false, true);
        // The client's own read timeout must be shorter than the mock's silence window, or this
        // test would just wait out the real sleep.
        assertThat(timeoutTrapSilenceMs).isLessThan(2000);

        CoreCallResult result = client.open(baseUrl(), 200, "core-client-test-timeout", "CREDIT_CARD_REWARDS", 3000);

        assertThat(result.result()).isEqualTo(CoreAttemptResult.TIMEOUT);
    }
}
