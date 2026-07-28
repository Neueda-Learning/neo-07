package com.neobank.module.core;

/** The mock core's admin-controlled behavior, as read by the panel. */
public record MockCoreDials(long latencyMs, double failureRate, boolean killSwitch, boolean timeoutTrap) {

    static MockCoreDials allOff() {
        return new MockCoreDials(0, 0.0, false, false);
    }
}
