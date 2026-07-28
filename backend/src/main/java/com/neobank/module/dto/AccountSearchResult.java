package com.neobank.module.dto;

import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountRecord;
import java.time.Instant;

/**
 * One row of the Account Board (UC-01). Deliberately narrow: no applicant field lives here — the
 * schema has no name column to search, so the board's name column is hydrated live by the UI
 * through {@code GET /api/v1/accounts/{applicationId}/applicant}, never returned by search itself.
 */
public record AccountSearchResult(
        String applicationId,
        AccountOutcome outcome,
        Integer creditAmount,
        Instant openedAt) {

    public static AccountSearchResult of(AccountRecord row) {
        return new AccountSearchResult(
                row.getApplicationId(), row.getOutcome(), row.getCreditAmount(), row.getOpenedAt());
    }
}
