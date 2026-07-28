package com.neobank.module.controller;

import com.neobank.module.dto.CoreConfigRequest;
import com.neobank.module.dto.CoreConfigView;
import com.neobank.module.model.CoreConfig;
import com.neobank.module.service.CoreConfigService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** UC-08 — Edit Core Config: the operator screen's backend. */
@RestController
@RequestMapping("/config")
public class CoreConfigController {

    private final CoreConfigService configs;

    public CoreConfigController(CoreConfigService configs) {
        this.configs = configs;
    }

    @PostMapping
    public ResponseEntity<Object> create(@RequestBody CoreConfigRequest request) {
        List<String> errors = CoreConfigService.validate(request);
        if (!errors.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(errors));
        }
        CoreConfig created = configs.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("version", created.getVersion()));
    }

    @GetMapping("/versions")
    public List<CoreConfigView> versions() {
        Integer currentVersion = configs.current().map(CoreConfig::getVersion).orElse(null);
        return configs.listVersionsOldestFirst().stream()
                .map(config -> CoreConfigView.of(config, config.getVersion().equals(currentVersion)))
                .toList();
    }

    private Map<String, Object> errorBody(List<String> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.BAD_REQUEST.value());
        body.put("error", HttpStatus.BAD_REQUEST.getReasonPhrase());
        body.put("message", String.join("; ", errors));
        return body;
    }
}
