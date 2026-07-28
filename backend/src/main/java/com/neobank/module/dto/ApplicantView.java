package com.neobank.module.dto;

import java.util.Map;

/**
 * UC-03 — View Applicant sidebar subset. Built from {@code
 * OrchestratorClient.fetchApplication}'s raw map (the same proxy UC-01's board already uses) —
 * nothing here is ever persisted (AC#3).
 */
public record ApplicantView(String fullName, String dateOfBirth, String productCode,
        Integer requestedCreditLimit, String channel) {

    @SuppressWarnings("unchecked")
    public static ApplicantView of(Map<String, Object> application) {
        Map<String, Object> applicant = (Map<String, Object>) application.get("applicant");
        Map<String, Object> product = (Map<String, Object>) application.get("product");
        return new ApplicantView(
                applicant == null ? null : (String) applicant.get("fullName"),
                applicant == null ? null : (String) applicant.get("dateOfBirth"),
                product == null ? null : (String) product.get("productCode"),
                product == null ? null : asInteger(product.get("requestedCreditLimit")),
                (String) application.get("channel"));
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
