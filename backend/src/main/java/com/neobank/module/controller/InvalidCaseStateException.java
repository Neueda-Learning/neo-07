package com.neobank.module.controller;

/** UC-04 AC#6 — retrying a case that is not {@code FAILED} is a client error, not a no-op. */
public class InvalidCaseStateException extends RuntimeException {

    public InvalidCaseStateException(String message) {
        super(message);
    }
}
