package com.neobank.module.core;

import jakarta.validation.Valid;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * UC-05 — the mock "core banking" system itself: a fake external service this module calls over
 * real HTTP, exactly the way it would call the genuine core.
 *
 * <p>{@code POST /core/card-accounts} is deliberately non-idempotent (UC-05 AC#2) — calling it
 * twice with the same reference opens two accounts, on purpose, because the real core the spec
 * is modelling behaves the same way. That is why the caller must always probe before it opens.</p>
 */
@RestController
@RequestMapping("/core")
public class MockCoreController {

    private final MockCoreStore store;

    /**
     * How long the mock keeps a trapped caller waiting after it has already committed the
     * account. Only needs to outlast any timeout the module's own caller will configure
     * (seeded {@code core_config.timeout_ms} tops out at 30000ms) — this just has to be
     * comfortably longer, not infinite. Overridable ({@code mock-core.timeout-trap-silence-ms})
     * so a test can prove the trap's ordering without waiting out the real 2-minute default.
     */
    private final long timeoutTrapSilenceMs;

    public MockCoreController(MockCoreStore store,
                              @Value("${mock-core.timeout-trap-silence-ms:120000}") long timeoutTrapSilenceMs) {
        this.store = store;
        this.timeoutTrapSilenceMs = timeoutTrapSilenceMs;
    }

    @PostMapping("/card-accounts")
    public ResponseEntity<CoreAccountView> open(@Valid @RequestBody OpenCoreAccountRequest request)
            throws InterruptedException {
        applyDials();
        MockCoreDials dials = store.dials();

        MockAccount account = store.createAccount(request.reference());
        if (dials.timeoutTrap()) {
            // The account is already committed — the caller must find it on its next probe.
            // Sleeping here only simulates the core going silent after having done the work.
            Thread.sleep(timeoutTrapSilenceMs);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(CoreAccountView.of(account));
    }

    @GetMapping("/card-accounts")
    public ResponseEntity<CoreAccountView> probe(@RequestParam String reference) throws InterruptedException {
        applyDials();
        Optional<MockAccount> account = store.probe(reference);
        return account.map(CoreAccountView::of)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Kill switch and latency/failure dials apply to every call, probe or open alike. */
    private void applyDials() throws InterruptedException {
        MockCoreDials dials = store.dials();
        if (dials.killSwitch()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "core kill switch is on");
        }
        if (dials.latencyMs() > 0) {
            Thread.sleep(dials.latencyMs());
        }
        if (dials.failureRate() > 0 && ThreadLocalRandom.current().nextDouble() < dials.failureRate()) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "core simulated failure");
        }
    }
}
