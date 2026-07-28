package com.neobank.module.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The store's own behavior, tested directly and fast — no HTTP, no sleeping. The controller
 * tests below cover the HTTP-level dial wiring; this covers the data structure the UC-05
 * checkpoints actually depend on.
 */
class MockCoreStoreTest {

    @Test
    void openingTheSameReferenceTwiceCreatesTwoDistinctAccounts() {
        // UC-05 AC#2 checkpoint: deliberately non-idempotent.
        MockCoreStore store = new MockCoreStore();

        MockAccount first = store.createAccount("acc-shared");
        MockAccount second = store.createAccount("acc-shared");

        assertThat(first.accountId()).isNotEqualTo(second.accountId());
    }

    @Test
    void probeFindsTheFirstAccountOpenedForAReference() {
        MockCoreStore store = new MockCoreStore();
        MockAccount first = store.createAccount("acc-probe");
        store.createAccount("acc-probe");

        assertThat(store.probe("acc-probe")).contains(first);
    }

    @Test
    void probeMissesAnUnknownReference() {
        MockCoreStore store = new MockCoreStore();

        assertThat(store.probe("never-opened")).isEmpty();
    }

    @Test
    void dialsDefaultToAllOffOnAFreshStore() {
        MockCoreStore store = new MockCoreStore();

        MockCoreDials dials = store.dials();

        assertThat(dials.latencyMs()).isZero();
        assertThat(dials.failureRate()).isZero();
        assertThat(dials.killSwitch()).isFalse();
        assertThat(dials.timeoutTrap()).isFalse();
    }

    @Test
    void updateDialsOnlyChangesSuppliedFields() {
        MockCoreStore store = new MockCoreStore();
        store.updateDials(100L, 0.5, true, false);

        MockCoreDials updated = store.updateDials(null, null, null, true);

        assertThat(updated.latencyMs()).isEqualTo(100L);
        assertThat(updated.failureRate()).isEqualTo(0.5);
        assertThat(updated.killSwitch()).isTrue();
        assertThat(updated.timeoutTrap()).isTrue();
    }
}
