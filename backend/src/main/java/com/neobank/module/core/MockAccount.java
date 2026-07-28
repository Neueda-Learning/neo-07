package com.neobank.module.core;

import java.time.Instant;

/**
 * One account the mock core "opened." Lives only in {@link MockCoreStore}'s in-memory map —
 * this is a fake external system, not a table in this module's own schema.
 */
public record MockAccount(String accountId, String reference, Instant createdAt) {
}
