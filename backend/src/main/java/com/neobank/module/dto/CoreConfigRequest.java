package com.neobank.module.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** {@code POST /config} body — UC-08. */
public record CoreConfigRequest(Integer retryBudget, Integer timeoutMs, String coreBaseUrl, JsonNode catalogue) {
}
