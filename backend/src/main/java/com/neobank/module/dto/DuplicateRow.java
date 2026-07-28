package com.neobank.module.dto;

import java.util.List;

/**
 * One reference with more than one core account. {@code applicationId} is the reference the
 * core call was made with — always this module's applicationId (UC-04's {@code coreReference}
 * convention). {@code reference} is the module's own case reference, null when the duplicate has
 * no matching row here at all (the cross-check reads both sides — a duplicate the module never
 * even recorded is still a control failure).
 */
public record DuplicateRow(String applicationId, String reference, List<String> coreAccountIds) {
}
