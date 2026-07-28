package com.neobank.module.dto;

import java.time.Instant;

/**
 * One row of UC-04's Failed-Opens Queue — a case parked on {@code FAILED} +
 * {@code ACC_CORE_UNAVAILABLE}, oldest first. The applicant name is hydrated live by the UI
 * through the existing {@code GET /api/v1/accounts/{applicationId}/applicant} proxy, exactly like
 * the Account Board — this row carries no applicant field on purpose.
 */
public record FailedQueueRow(
        String applicationId,
        String reference,
        Integer coreConfigVersion,
        long attemptCount,
        Instant createdAt) {
}
