package com.neobank.module.controller;

/**
 * UC-06 AC#5 — the core could not be reached to verify the report. Never render a silently empty
 * report in this case: an unverifiable control is not a passing control.
 */
public class CoreUnavailableException extends RuntimeException {

    public CoreUnavailableException(String message) {
        super(message);
    }
}
