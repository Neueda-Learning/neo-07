package com.neobank.module.model;

/** UC-02 AC#8 — an unknown applicationId, caught by {@code GlobalExceptionHandler} for a 404. */
public class CaseNotFoundException extends RuntimeException {

    public CaseNotFoundException(String applicationId) {
        super("no case found for application id " + applicationId);
    }
}
