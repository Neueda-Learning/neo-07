package com.neobank.module.controller;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** The one JSON error shape every controller in this module answers with — never a stack trace. */
final class ApiErrorBody {

    private ApiErrorBody() {
    }

    static Map<String, Object> of(HttpStatus status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return body;
    }
}
