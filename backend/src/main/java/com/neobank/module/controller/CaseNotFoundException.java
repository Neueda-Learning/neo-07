package com.neobank.module.controller;

/** {@code POST /cases/{id}/retry} or a future case lookup on an id nothing has ever heard of. */
public class CaseNotFoundException extends RuntimeException {

    public CaseNotFoundException(String applicationId) {
        super("no case found for applicationId " + applicationId);
    }
}
