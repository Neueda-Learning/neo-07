package com.neobank.module.controller;

/** A syntactically valid UC-07 request that violates an override business rule. */
public class InvalidOverrideException extends RuntimeException {

    public InvalidOverrideException(String message) {
        super(message);
    }
}
