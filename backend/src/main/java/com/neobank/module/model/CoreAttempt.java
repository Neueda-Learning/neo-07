package com.neobank.module.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** Append-only evidence of one HTTP call made to the core. */
@Entity
@Table(name = "core_attempt")
public class CoreAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, length = 64)
    private String applicationId;

    @Column(name = "cycle_no", nullable = false)
    private Integer cycleNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CoreAttemptKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CoreAttemptResult result;

    @Column(name = "core_reference", nullable = false, length = 64)
    private String coreReference;

    @Column(name = "latency_ms", nullable = false)
    private Long latencyMs;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    protected CoreAttempt() {
        // JPA
    }

    public CoreAttempt(String applicationId, Integer cycleNo, CoreAttemptKind kind,
                       CoreAttemptResult result, String coreReference, Long latencyMs) {
        this.applicationId = applicationId;
        this.cycleNo = cycleNo;
        this.kind = kind;
        this.result = result;
        this.coreReference = coreReference;
        this.latencyMs = latencyMs;
    }

    @PrePersist
    void onCreate() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public Integer getCycleNo() {
        return cycleNo;
    }

    public CoreAttemptKind getKind() {
        return kind;
    }

    public CoreAttemptResult getResult() {
        return result;
    }

    public String getCoreReference() {
        return coreReference;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
