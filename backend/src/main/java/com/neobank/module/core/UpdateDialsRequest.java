package com.neobank.module.core;

/** A partial dial update — {@code null} fields are left unchanged. */
public record UpdateDialsRequest(Long latencyMs, Double failureRate, Boolean killSwitch, Boolean timeoutTrap) {
}
