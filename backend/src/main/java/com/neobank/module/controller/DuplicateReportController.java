package com.neobank.module.controller;

import com.neobank.module.dto.DuplicateReportResponse;
import com.neobank.module.service.DuplicateReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** UC-06 — the Duplicate Report's HTTP surface. Read-only; module-internal, unprefixed. */
@RestController
public class DuplicateReportController {

    private final DuplicateReportService service;

    public DuplicateReportController(DuplicateReportService service) {
        this.service = service;
    }

    @GetMapping("/reports/duplicates")
    public DuplicateReportResponse duplicates() {
        return service.findDuplicates();
    }
}
