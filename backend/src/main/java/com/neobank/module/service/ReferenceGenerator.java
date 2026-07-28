package com.neobank.module.service;

import com.neobank.module.repository.AccountRecordRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Generates the human-facing {@code account_record.reference} (e.g. {@code acc-a1b2c3d4}).
 *
 * <p>Not specified by the platform contract or the v5 briefs — {@code
 * docs/contract-and-data-decisions.md} defers the format and allocation strategy to this
 * module. A random 8-character suffix keeps collisions astronomically unlikely, and the
 * retry-on-collision loop is what actually makes the "collision-safe" claim true rather than
 * assumed, given {@code reference} carries a {@code UNIQUE} constraint.</p>
 */
@Component
public class ReferenceGenerator {

    private static final int MAX_ATTEMPTS = 5;

    private final AccountRecordRepository accountRecords;

    public ReferenceGenerator(AccountRecordRepository accountRecords) {
        this.accountRecords = accountRecords;
    }

    /** A reference not currently used by any {@code account_record} row. */
    public String next() {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = "acc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
            if (accountRecords.findByReference(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not generate a unique reference after " + MAX_ATTEMPTS + " attempts");
    }
}
