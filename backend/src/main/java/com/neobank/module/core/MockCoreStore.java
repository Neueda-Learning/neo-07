package com.neobank.module.core;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/**
 * The mock core's entire state: every account it has "opened," and the four admin dials.
 *
 * <p>Deliberately in-memory, deliberately a single bean — state resets to empty/all-off only on
 * process restart (UC-05 AC#7), and every request within one running instance sees the same
 * store, which is what makes the control panel's dials actually affect module calls.</p>
 */
@Component
public class MockCoreStore {

    private final ConcurrentHashMap<String, CopyOnWriteArrayList<MockAccount>> accountsByReference =
            new ConcurrentHashMap<>();
    private final AtomicReference<MockCoreDials> dials = new AtomicReference<>(MockCoreDials.allOff());

    /** Always creates a new account — the mock is deliberately non-idempotent (UC-05 AC#2). */
    public MockAccount createAccount(String reference) {
        MockAccount account = new MockAccount(
                "CC-" + UUID.randomUUID().toString().replace("-", "").substring(0, 7).toUpperCase(),
                reference,
                Instant.now());
        accountsByReference
                .computeIfAbsent(reference, key -> new CopyOnWriteArrayList<>())
                .add(account);
        return account;
    }

    /** The first account opened for this reference, if any (UC-05 AC#3). */
    public Optional<MockAccount> probe(String reference) {
        List<MockAccount> accounts = accountsByReference.get(reference);
        return (accounts == null || accounts.isEmpty()) ? Optional.empty() : Optional.of(accounts.get(0));
    }

    /**
     * Every account the core has ever opened, across every reference (UC-06's cross-check).
     * A reference with more than one entry here IS a duplicate — the mock is deliberately
     * non-idempotent, so this list is the ground truth the module's own table is checked against.
     */
    public List<MockAccount> allAccounts() {
        return accountsByReference.values().stream()
                .flatMap(List::stream)
                .toList();
    }

    public MockCoreDials dials() {
        return dials.get();
    }

    /** Only the supplied fields change — the rest keep their current value. */
    public MockCoreDials updateDials(Long latencyMs, Double failureRate, Boolean killSwitch,
                                     Boolean timeoutTrap) {
        return dials.updateAndGet(current -> new MockCoreDials(
                latencyMs != null ? latencyMs : current.latencyMs(),
                failureRate != null ? failureRate : current.failureRate(),
                killSwitch != null ? killSwitch : current.killSwitch(),
                timeoutTrap != null ? timeoutTrap : current.timeoutTrap()));
    }
}
