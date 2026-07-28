package com.neobank.module.model;

/** The observable result of one call to the core. */
public enum CoreAttemptResult {
    MISS,
    HIT,
    CREATED,
    TIMEOUT,
    ERROR
}
