package com.neobank.module.core;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** UC-05's Core Control Panel backend, and UC-06's read of every account the core has opened. */
@RestController
@RequestMapping("/core/admin")
public class MockCoreAdminController {

    private final MockCoreStore store;

    public MockCoreAdminController(MockCoreStore store) {
        this.store = store;
    }

    @GetMapping("/dials")
    public MockCoreDials get() {
        return store.dials();
    }

    /**
     * Partial update — only the fields present in the body change (UC-05's own example:
     * {@code {"timeoutTrap": true}}). Out-of-range values (negative {@code latencyMs}, a
     * {@code failureRate} outside {@code 0..1}) are rejected {@code 400} before touching the
     * store — an unbounded {@code failureRate} would fail every core call forever, and a
     * negative {@code latencyMs} throws out of {@code Thread.sleep} on the very next call.
     */
    @PutMapping("/dials")
    public ResponseEntity<Object> update(@RequestBody UpdateDialsRequest request) {
        List<String> errors = request.validate();
        if (!errors.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(errors));
        }
        MockCoreDials updated = store.updateDials(request.latencyMs(), request.failureRate(),
                request.killSwitch(), request.timeoutTrap());
        return ResponseEntity.ok(updated);
    }

    private Map<String, Object> errorBody(List<String> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", HttpStatus.BAD_REQUEST.getReasonPhrase());
        body.put("message", String.join("; ", errors));
        return body;
    }

    /**
     * UC-06's cross-check reads this, never the module's own table alone — every account the
     * core has opened, across every reference, exactly like the real core would answer. Behind
     * the kill switch like every other core endpoint: an unreachable core cannot verify anything.
     */
    @GetMapping("/accounts")
    public List<CoreAccountView> accounts() {
        if (store.dials().killSwitch()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "core kill switch is on");
        }
        return store.allAccounts().stream().map(CoreAccountView::of).toList();
    }
}

