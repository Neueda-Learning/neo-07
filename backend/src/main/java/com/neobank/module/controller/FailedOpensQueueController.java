package com.neobank.module.controller;

import com.neobank.module.dto.FailedQueueRow;
import com.neobank.module.service.FailedOpensQueueService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * UC-04 — Failed-Opens Queue: this module's own operator screen, named after the v5 brief's own
 * paths rather than under {@code /api/v1} — same convention {@link CoreConfigController} and the
 * mock core's admin endpoints already use for this module's non-orchestrator surface.
 */
@RestController
public class FailedOpensQueueController {

    private final FailedOpensQueueService queue;

    public FailedOpensQueueController(FailedOpensQueueService queue) {
        this.queue = queue;
    }

    /** {@code GET /queue} — oldest first, max 10 rows (AC#1). */
    @GetMapping("/queue")
    public List<FailedQueueRow> queue() {
        return queue.queue();
    }

    /**
     * {@code POST /cases/{id}/retry} — {@code 202 {"status":"retrying"}} whether this attempt
     * resolves the case or not (AC#3, AC#5); unknown id is {@code 404}, a non-FAILED case is
     * {@code 400} (AC#6), both via {@link GlobalExceptionHandler}.
     */
    @PostMapping("/cases/{applicationId}/retry")
    public ResponseEntity<Map<String, Object>> retry(@PathVariable String applicationId) {
        queue.retry(applicationId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "retrying");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }
}
