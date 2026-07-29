package com.neobank.module.dto;

import com.neobank.module.model.AccountOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** UC-07 request body for the one permitted operator correction of a case outcome. */
public record OverrideCaseRequest(
        @NotNull AccountOutcome newOutcome,
        @NotBlank @Size(max = 1000) String reason,
        @NotBlank @Size(max = 255) String operator,
        @Size(max = 64) String accountId) {

    public OverrideCaseRequest {
        reason = normalize(reason);
        operator = normalize(operator);
        accountId = normalize(accountId);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isEmpty() ? null : normalized;
    }
}
