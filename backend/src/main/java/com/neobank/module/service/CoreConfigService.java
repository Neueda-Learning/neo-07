package com.neobank.module.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.neobank.module.dto.CoreConfigRequest;
import com.neobank.module.model.CoreConfig;
import com.neobank.module.repository.CoreConfigRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-08 — Edit Core Config. Insert-only, versioned retry policy and catalogue.
 *
 * <p>{@link #validate} is a pure function from a request to a list of field errors — no Spring,
 * no database — so the rule can be tested and read on its own, the same pattern this codebase's
 * own service javadoc prescribes elsewhere.</p>
 */
@Service
public class CoreConfigService {

    private static final int MIN_RETRY_BUDGET = 1;
    private static final int MAX_RETRY_BUDGET = 10;
    private static final int MIN_TIMEOUT_MS = 200;
    private static final int MAX_TIMEOUT_MS = 30000;
    private static final List<String> REQUIRED_PRODUCT_CODES =
            List.of("CREDIT_CARD_STANDARD", "CREDIT_CARD_REWARDS", "CREDIT_CARD_STUDENT");

    private final CoreConfigRepository configs;

    public CoreConfigService(CoreConfigRepository configs) {
        this.configs = configs;
    }

    /** Field-level error messages; empty means the request is valid (UC-08 AC#3). */
    public static List<String> validate(CoreConfigRequest request) {
        List<String> errors = new ArrayList<>();

        if (request.retryBudget() == null
                || request.retryBudget() < MIN_RETRY_BUDGET || request.retryBudget() > MAX_RETRY_BUDGET) {
            errors.add("retryBudget must be between " + MIN_RETRY_BUDGET + " and " + MAX_RETRY_BUDGET);
        }
        if (request.timeoutMs() == null
                || request.timeoutMs() < MIN_TIMEOUT_MS || request.timeoutMs() > MAX_TIMEOUT_MS) {
            errors.add("timeoutMs must be between " + MIN_TIMEOUT_MS + " and " + MAX_TIMEOUT_MS);
        }
        if (request.coreBaseUrl() == null || request.coreBaseUrl().isBlank()) {
            errors.add("coreBaseUrl is required");
        }
        errors.addAll(validateCatalogue(request.catalogue()));

        return errors;
    }

    private static List<String> validateCatalogue(JsonNode catalogue) {
        List<String> errors = new ArrayList<>();
        if (catalogue == null || !catalogue.isObject()) {
            errors.add("catalogue is required and must be an object keyed by productCode");
            return errors;
        }
        for (String productCode : REQUIRED_PRODUCT_CODES) {
            JsonNode entry = catalogue.get(productCode);
            if (entry == null) {
                errors.add("catalogue is missing " + productCode);
                continue;
            }
            JsonNode apr = entry.get("apr");
            JsonNode limitMin = entry.get("limitMin");
            JsonNode limitMax = entry.get("limitMax");
            if (apr == null || !apr.isNumber()) {
                errors.add(productCode + " apr is required and must be a number");
            }
            if (limitMin == null || limitMax == null) {
                errors.add(productCode + " must have limitMin and limitMax");
            } else if (!limitMin.isIntegralNumber() || !limitMax.isIntegralNumber()) {
                errors.add(productCode + " limitMin and limitMax must be integers");
            } else if (limitMin.asInt() >= limitMax.asInt()) {
                errors.add(productCode + " limitMin must be less than limitMax");
            }
        }
        catalogue.fieldNames().forEachRemaining(productCode -> {
            if (!REQUIRED_PRODUCT_CODES.contains(productCode)) {
                errors.add("catalogue contains unknown product code: " + productCode);
            }
        });
        return errors;
    }

    /** Inserts the next version — {@code validate} must be checked by the caller first. */
    @Transactional
    public CoreConfig create(CoreConfigRequest request) {
        int nextVersion = configs.findTopByOrderByVersionDesc()
                .map(CoreConfig::getVersion)
                .map(version -> version + 1)
                .orElse(1);
        return configs.save(new CoreConfig(nextVersion, request.retryBudget(), request.timeoutMs(),
                request.coreBaseUrl(), request.catalogue()));
    }

    @Transactional(readOnly = true)
    public List<CoreConfig> listVersionsOldestFirst() {
        return configs.findAll().stream()
                .sorted((a, b) -> Integer.compare(a.getVersion(), b.getVersion()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<CoreConfig> current() {
        return configs.findTopByOrderByVersionDesc();
    }
}
