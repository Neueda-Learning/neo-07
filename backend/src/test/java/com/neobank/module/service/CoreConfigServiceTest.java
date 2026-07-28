package com.neobank.module.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.module.dto.CoreConfigRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link CoreConfigService#validate} boundary tests — pure, no Spring, no database, mirroring
 * this codebase's own prescribed "keep the rules in a method of their own" pattern.
 */
class CoreConfigServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode validCatalogue() throws Exception {
        return MAPPER.readTree("""
                {"CREDIT_CARD_STANDARD":{"apr":19.9,"limitMin":250,"limitMax":3000},
                 "CREDIT_CARD_REWARDS":{"apr":24.9,"limitMin":500,"limitMax":10000},
                 "CREDIT_CARD_STUDENT":{"apr":14.9,"limitMin":100,"limitMax":1500}}
                """);
    }

    private static CoreConfigRequest request(Integer retryBudget, Integer timeoutMs, JsonNode catalogue) {
        return new CoreConfigRequest(retryBudget, timeoutMs, "http://mock-core:8090", catalogue);
    }

    @Test
    void aFullyValidPayloadHasNoErrors() throws Exception {
        assertThat(CoreConfigService.validate(request(3, 2000, validCatalogue()))).isEmpty();
    }

    @Test
    void retryBudgetBelowOneIsRejected() throws Exception {
        assertThat(CoreConfigService.validate(request(0, 2000, validCatalogue())))
                .anyMatch(error -> error.contains("retryBudget"));
    }

    @Test
    void retryBudgetOfOneIsAccepted() throws Exception {
        assertThat(CoreConfigService.validate(request(1, 2000, validCatalogue()))).isEmpty();
    }

    @Test
    void retryBudgetOfTenIsAccepted() throws Exception {
        assertThat(CoreConfigService.validate(request(10, 2000, validCatalogue()))).isEmpty();
    }

    @Test
    void retryBudgetAboveTenIsRejected() throws Exception {
        assertThat(CoreConfigService.validate(request(11, 2000, validCatalogue())))
                .anyMatch(error -> error.contains("retryBudget"));
    }

    @Test
    void timeoutBelow200IsRejected() throws Exception {
        assertThat(CoreConfigService.validate(request(3, 199, validCatalogue())))
                .anyMatch(error -> error.contains("timeoutMs"));
    }

    @Test
    void timeoutOf200IsAccepted() throws Exception {
        assertThat(CoreConfigService.validate(request(3, 200, validCatalogue()))).isEmpty();
    }

    @Test
    void timeoutOf30000IsAccepted() throws Exception {
        assertThat(CoreConfigService.validate(request(3, 30000, validCatalogue()))).isEmpty();
    }

    @Test
    void timeoutAbove30000IsRejected() throws Exception {
        assertThat(CoreConfigService.validate(request(3, 30001, validCatalogue())))
                .anyMatch(error -> error.contains("timeoutMs"));
    }

    @Test
    void catalogueMissingAProductCodeIsRejected() throws Exception {
        JsonNode incomplete = MAPPER.readTree("""
                {"CREDIT_CARD_REWARDS":{"apr":24.9,"limitMin":500,"limitMax":10000}}
                """);

        assertThat(CoreConfigService.validate(request(3, 2000, incomplete)))
                .anyMatch(error -> error.contains("CREDIT_CARD_STANDARD"));
    }

    @Test
    void limitMinEqualToLimitMaxIsRejected() throws Exception {
        JsonNode catalogue = MAPPER.readTree("""
                {"CREDIT_CARD_STANDARD":{"apr":19.9,"limitMin":1000,"limitMax":1000},
                 "CREDIT_CARD_REWARDS":{"apr":24.9,"limitMin":500,"limitMax":10000},
                 "CREDIT_CARD_STUDENT":{"apr":14.9,"limitMin":100,"limitMax":1500}}
                """);

        assertThat(CoreConfigService.validate(request(3, 2000, catalogue)))
                .anyMatch(error -> error.contains("CREDIT_CARD_STANDARD"));
    }

    @Test
    void limitMinGreaterThanLimitMaxIsRejected() throws Exception {
        JsonNode catalogue = MAPPER.readTree("""
                {"CREDIT_CARD_STANDARD":{"apr":19.9,"limitMin":5000,"limitMax":1000},
                 "CREDIT_CARD_REWARDS":{"apr":24.9,"limitMin":500,"limitMax":10000},
                 "CREDIT_CARD_STUDENT":{"apr":14.9,"limitMin":100,"limitMax":1500}}
                """);

        assertThat(CoreConfigService.validate(request(3, 2000, catalogue)))
                .anyMatch(error -> error.contains("CREDIT_CARD_STANDARD"));
    }

    @Test
    void missingCatalogueIsRejected() {
        List<String> errors = CoreConfigService.validate(request(3, 2000, null));

        assertThat(errors).anyMatch(error -> error.contains("catalogue"));
    }
}
