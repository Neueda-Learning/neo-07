package com.neobank.module.dto;

import com.neobank.module.model.DuplicateKind;
import java.util.List;

/**
 * One finding from either side of the module/core cross-check. {@code applicationId} is the
 * reference the core call was made with — always this module's applicationId (UC-04's
 * {@code coreReference} convention). {@code reference} is the module's own case reference, null
 * when the duplicate has no matching row here at all. {@code kind} distinguishes the two failure
 * modes: {@link DuplicateKind#CORE_DUPLICATE} (the core itself shows more than one account for
 * this reference) from {@link DuplicateKind#MISSING_AT_CORE} (this module's own OPENED record
 * doesn't actually appear in the core's account list for its reference — a duplicate the core
 * side alone cannot see). {@code coreAccountIds} is empty for a {@code MISSING_AT_CORE} row.
 */
public record DuplicateRow(String applicationId, String reference, List<String> coreAccountIds, DuplicateKind kind) {
}
