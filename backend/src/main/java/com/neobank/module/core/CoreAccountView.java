package com.neobank.module.core;

import java.time.Instant;

/** The wire shape for a mock core account — what {@code POST}/{@code GET /core/card-accounts} return. */
public record CoreAccountView(String accountId, String reference, Instant createdAt) {

    public static CoreAccountView of(MockAccount account) {
        return new CoreAccountView(account.accountId(), account.reference(), account.createdAt());
    }
}
