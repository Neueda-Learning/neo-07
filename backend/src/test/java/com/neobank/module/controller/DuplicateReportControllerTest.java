package com.neobank.module.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobank.module.dto.DuplicateReportResponse;
import com.neobank.module.dto.DuplicateRow;
import com.neobank.module.service.DuplicateReportService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** UC-06 wire-level coverage: the report's JSON shape and the unreachable-core status code. */
@WebMvcTest(DuplicateReportController.class)
class DuplicateReportControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private DuplicateReportService service;

    @Test
    void anEmptyReportIs200WithZeroDuplicates() throws Exception {
        when(service.findDuplicates()).thenReturn(
                new DuplicateReportResponse(Instant.parse("2026-07-22T10:00:00Z"), 147, List.of()));

        mvc.perform(get("/reports/duplicates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coreAccountsScanned").value(147))
                .andExpect(jsonPath("$.duplicates").isEmpty());
    }

    @Test
    void aDuplicateRowIsReturnedWithBothAccountIds() throws Exception {
        when(service.findDuplicates()).thenReturn(new DuplicateReportResponse(
                Instant.now(), 3,
                List.of(new DuplicateRow("app-dupe", "acc-000900", List.of("CC-1", "CC-2")))));

        mvc.perform(get("/reports/duplicates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicates[0].applicationId").value("app-dupe"))
                .andExpect(jsonPath("$.duplicates[0].coreAccountIds", org.hamcrest.Matchers.hasSize(2)));
    }

    @Test
    void coreUnreachableIs503() throws Exception {
        when(service.findDuplicates()).thenThrow(new CoreUnavailableException("cannot verify — core unreachable"));

        mvc.perform(get("/reports/duplicates"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cannot verify")));
    }
}
