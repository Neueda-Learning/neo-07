package com.neobank.module.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.neobank.module.model.CoreConfig;
import java.time.Instant;

/** What {@code POST /config} and {@code GET /config/versions} return — UC-08. */
public record CoreConfigView(
        Integer version,
        Integer retryBudget,
        Integer timeoutMs,
        String coreBaseUrl,
        JsonNode catalogue,
        Instant effectiveFrom,
        boolean current) {

    public static CoreConfigView of(CoreConfig config, boolean current) {
        return new CoreConfigView(config.getVersion(), config.getRetryBudget(), config.getTimeoutMs(),
                config.getCoreBaseUrl(), config.getCatalogue(), config.getEffectiveFrom(), current);
    }
}
