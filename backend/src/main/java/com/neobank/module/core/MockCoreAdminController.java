package com.neobank.module.core;

import java.util.List;
import org.springframework.http.HttpStatus;
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

    /** Partial update — only the fields present in the body change (UC-05's own example: {@code {"timeoutTrap": true}}). */
    @PutMapping("/dials")
    public MockCoreDials update(@RequestBody UpdateDialsRequest request) {
        return store.updateDials(request.latencyMs(), request.failureRate(), request.killSwitch(),
                request.timeoutTrap());
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

