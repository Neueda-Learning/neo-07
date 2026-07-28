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

/** Append-only audit entry for one operator correction. */
@Entity
@Table(name = "override_log")
public class OverrideLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "application_id", nullable = false, length = 64)
    private String applicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "old_outcome", nullable = false, length = 20)
    private AccountOutcome oldOutcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_outcome", nullable = false, length = 20)
    private AccountOutcome newOutcome;

    @Column(name = "account_id", length = 64)
    private String accountId;

    @Column(nullable = false, length = 1000)
    private String reason;

    @Column(name = "operator_name", nullable = false, length = 255)
    private String operator;

    @Column(name = "overridden_at", nullable = false, updatable = false)
    private Instant overriddenAt;

    protected OverrideLog() {
        // JPA
    }

    public OverrideLog(String applicationId, AccountOutcome oldOutcome,
                       AccountOutcome newOutcome, String accountId,
                       String reason, String operator) {
        this.applicationId = applicationId;
        this.oldOutcome = oldOutcome;
        this.newOutcome = newOutcome;
        this.accountId = accountId;
        this.reason = reason;
        this.operator = operator;
    }

    @PrePersist
    void onCreate() {
        if (overriddenAt == null) {
            overriddenAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public AccountOutcome getOldOutcome() {
        return oldOutcome;
    }

    public AccountOutcome getNewOutcome() {
        return newOutcome;
    }

    public String getAccountId() {
        return accountId;
    }

    public String getReason() {
        return reason;
    }

    public String getOperator() {
        return operator;
    }

    public Instant getOverriddenAt() {
        return overriddenAt;
    }
}
