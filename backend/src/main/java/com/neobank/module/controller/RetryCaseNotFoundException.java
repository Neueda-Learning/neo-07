package com.neobank.module.controller;

/** {@code POST /cases/{id}/retry} on an applicationId the failed-opens queue has never heard of. */
public class RetryCaseNotFoundException extends RuntimeException {

    public RetryCaseNotFoundException(String applicationId) {
        super("no case found for applicationId " + applicationId);
    }
}
