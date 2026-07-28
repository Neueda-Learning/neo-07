package com.neobank.module.core;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** UC-05 — the Core Control Panel's backend: read and flip the mock's four dials. */
@RestController
@RequestMapping("/core/admin/dials")
public class MockCoreAdminController {

    private final MockCoreStore store;

    public MockCoreAdminController(MockCoreStore store) {
        this.store = store;
    }

    @GetMapping
    public MockCoreDials get() {
        return store.dials();
    }

    /** Partial update — only the fields present in the body change (UC-05's own example: {@code {"timeoutTrap": true}}). */
    @PutMapping
    public MockCoreDials update(@RequestBody UpdateDialsRequest request) {
        return store.updateDials(request.latencyMs(), request.failureRate(), request.killSwitch(),
                request.timeoutTrap());
    }
}
