package com.neobank.module.model;

/**
 * UC-06's two failure modes, read from opposite sides of the module/core boundary.
 * {@code CORE_DUPLICATE} — the core has more than one account under one reference.
 * {@code MISSING_AT_CORE} — this module's own record believes it holds an account the core
 * does not actually show under that reference; a drift the core-only view cannot see.
 */
public enum DuplicateKind {
    CORE_DUPLICATE,
    MISSING_AT_CORE
}
