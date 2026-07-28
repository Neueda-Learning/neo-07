package com.neobank.module.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One immutable version of the policy used to call the core.
 *
 * <p>Current means the greatest {@link #version}. Existing rows are never edited or deleted;
 * changing policy inserts the next version.</p>
 */
@Entity
@Table(name = "core_config")
public class CoreConfig {

    @Id
    private Integer version;

    @Column(name = "retry_budget", nullable = false)
    private Integer retryBudget;

    @Column(name = "timeout_ms", nullable = false)
    private Integer timeoutMs;

    @Column(name = "core_base_url", nullable = false, length = 255)
    private String coreBaseUrl;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "json")
    private JsonNode catalogue;

    @Column(name = "effective_from", nullable = false, updatable = false)
    private Instant effectiveFrom;

    protected CoreConfig() {
        // JPA
    }

    public CoreConfig(Integer version, Integer retryBudget, Integer timeoutMs,
                      String coreBaseUrl, JsonNode catalogue) {
        this.version = version;
        this.retryBudget = retryBudget;
        this.timeoutMs = timeoutMs;
        this.coreBaseUrl = coreBaseUrl;
        this.catalogue = catalogue;
    }

    @PrePersist
    void onCreate() {
        if (effectiveFrom == null) {
            effectiveFrom = Instant.now();
        }
    }

    public Integer getVersion() {
        return version;
    }

    public Integer getRetryBudget() {
        return retryBudget;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public String getCoreBaseUrl() {
        return coreBaseUrl;
    }

    public JsonNode getCatalogue() {
        return catalogue;
    }

    public Instant getEffectiveFrom() {
        return effectiveFrom;
    }
}
