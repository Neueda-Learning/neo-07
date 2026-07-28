package com.neobank.module.dto;

import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountRecord;
import java.time.Instant;

/**
 * What {@code GET /api/v1/applications} returns — this module's own API, not the
 * orchestrator's. Read by this module's own UI; the orchestrator never calls it.
 */
public record AccountRecordView(
        String applicationId,
        String reference,
        AccountOutcome outcome,
        Instant createdAt) {

    public static AccountRecordView of(AccountRecord row) {
        return new AccountRecordView(row.getApplicationId(), row.getReference(), row.getOutcome(),
                row.getCreatedAt());
    }
}
