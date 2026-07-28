package com.neobank.module.dto;

import java.time.Instant;
import java.util.List;

/** UC-06's response shape — empty {@code duplicates} is the correct, successful result. */
public record DuplicateReportResponse(Instant checkedAt, long coreAccountsScanned, List<DuplicateRow> duplicates) {
}
