package com.neobank.module.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * UC-08 wire-level coverage. A full {@code @SpringBootTest} rather than a slice: {@code
 * GET /config/versions} needs to see the real seeded v1 row (Liquibase 007) plus whatever this
 * test itself inserts, which is exactly the "current" flagging behavior under test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CoreConfigControllerTest {

    @Autowired
    private MockMvc mvc;

    private static final String VALID_CATALOGUE = """
            {"CREDIT_CARD_STANDARD":{"apr":19.9,"limitMin":250,"limitMax":3000},
             "CREDIT_CARD_REWARDS":{"apr":24.9,"limitMin":500,"limitMax":10000},
             "CREDIT_CARD_STUDENT":{"apr":14.9,"limitMin":100,"limitMax":1500}}
            """;

    @Test
    void validPostInsertsANewVersionAndEchoesIt() throws Exception {
        mvc.perform(post("/config").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"retryBudget":5,"timeoutMs":3000,"coreBaseUrl":"http://mock-core:8090",
                                 "catalogue":%s}
                                """.formatted(VALID_CATALOGUE)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.version").isNumber());
    }

    @Test
    void invalidPostReturns400WithFieldErrors() throws Exception {
        mvc.perform(post("/config").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"retryBudget":0,"timeoutMs":100,"coreBaseUrl":"http://mock-core:8090",
                                 "catalogue":{}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void versionsEndpointListsOldestFirstWithCurrentFlagged() throws Exception {
        mvc.perform(post("/config").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"retryBudget":7,"timeoutMs":4000,"coreBaseUrl":"http://mock-core:8090",
                                 "catalogue":%s}
                                """.formatted(VALID_CATALOGUE)))
                .andExpect(status().isCreated());

        mvc.perform(get("/config/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].version").value(1))
                .andExpect(jsonPath("$[-1:].current").value(org.hamcrest.Matchers.hasItem(true)));
    }
}
