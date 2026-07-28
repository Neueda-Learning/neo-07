package com.neobank.module.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.neobank.module.dto.FailedQueueRow;
import com.neobank.module.service.FailedOpensQueueService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** UC-04 wire-level coverage: the queue's JSON shape and the retry endpoint's status codes. */
@WebMvcTest(FailedOpensQueueController.class)
class FailedOpensQueueControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private FailedOpensQueueService queue;

    @Test
    void listsTheQueueOldestFirst() throws Exception {
        org.mockito.Mockito.when(queue.queue()).thenReturn(List.of(
                new FailedQueueRow("app-1240", "acc-000900", 1, 6, Instant.parse("2026-07-20T10:00:00Z"))));

        mvc.perform(get("/queue"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].applicationId").value("app-1240"))
                .andExpect(jsonPath("$[0].attemptCount").value(6));
    }

    @Test
    void retryAnswers202WithRetryingStatus() throws Exception {
        mvc.perform(post("/cases/app-1240/retry"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("retrying"));

        verify(queue).retry("app-1240");
    }

    @Test
    void retryOnAnUnknownCaseIs404() throws Exception {
        doThrow(new CaseNotFoundException("ghost")).when(queue).retry("ghost");

        mvc.perform(post("/cases/ghost/retry"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ghost")));
    }

    @Test
    void retryOnANonFailedCaseIs400() throws Exception {
        doThrow(new InvalidCaseStateException("case app-9 is not FAILED — nothing to retry"))
                .when(queue).retry(eq("app-9"));

        mvc.perform(post("/cases/app-9/retry"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("not FAILED")));
    }
}
