package com.neobank.module.core;

import jakarta.validation.constraints.NotBlank;

/**
 * What a future open-call (UC-02's engine) will {@code POST} to {@code /core/card-accounts}.
 * No caller exists yet in this batch — {@code reference} is the only field the mock itself
 * needs to record and later probe for.
 */
public record OpenCoreAccountRequest(@NotBlank String reference, String productCode, Integer creditAmount) {
}
