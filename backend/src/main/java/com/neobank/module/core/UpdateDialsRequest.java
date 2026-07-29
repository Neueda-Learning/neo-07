package com.neobank.module.core;

import java.util.ArrayList;
import java.util.List;

/** A partial dial update — {@code null} fields are left unchanged. */
public record UpdateDialsRequest(Long latencyMs, Double failureRate, Boolean killSwitch, Boolean timeoutTrap) {

    /**
     * Field-level error messages for whichever fields are present; empty means the request is
     * valid. {@code null} fields are never errors — they mean "leave unchanged."
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        if (latencyMs != null && latencyMs < 0) {
            errors.add("latencyMs must not be negative");
        }
        if (failureRate != null && (failureRate < 0.0 || failureRate > 1.0)) {
            errors.add("failureRate must be between 0 and 1");
        }
        return errors;
    }
}
