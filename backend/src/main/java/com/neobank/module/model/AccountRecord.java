package com.neobank.module.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * The durable journey anchor: exactly one row per envelope application id.
 *
 * <p>No applicant or raw request fields belong in this entity. The nullable output columns are
 * filled only when their authoritative source is available.</p>
 */
@Entity
@Table(name = "account_record")
public class AccountRecord {

    @Id
    @Column(name = "application_id", nullable = false, length = 64)
    private String applicationId;

    @Column(nullable = false, unique = true, length = 64)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AccountOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", length = 50)
    private AccountReasonCode reasonCode;

    @Column(name = "account_id", length = 64)
    private String accountId;

    @Column(name = "credit_amount")
    private Integer creditAmount;

    @Column(name = "credit_amount_fallback", nullable = false)
    private boolean creditAmountFallback;

    @Column(name = "agreement_id", length = 64)
    private String agreementId;

    @Column(name = "product_code", length = 64)
    private String productCode;

    @Column(name = "product_version")
    private Integer productVersion;

    @Column(name = "customer_id", length = 64)
    private String customerId;

    @Column(name = "card_id", length = 64)
    private String cardId;

    @Column(name = "core_config_version")
    private Integer coreConfigVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "opened_at")
    private Instant openedAt;

    protected AccountRecord() {
        // JPA
    }

    public AccountRecord(String applicationId, String reference) {
        this.applicationId = applicationId;
        this.reference = reference;
        this.outcome = AccountOutcome.IN_PROGRESS;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (outcome == null) {
            outcome = AccountOutcome.IN_PROGRESS;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Pins the configuration before the first core call. */
    public void pinCoreConfig(Integer configVersion) {
        if (coreConfigVersion != null && !coreConfigVersion.equals(configVersion)) {
            throw new IllegalStateException("core config is already pinned");
        }
        coreConfigVersion = configVersion;
    }

    /** UC-02: the engine's decision that an account exists — created or adopted. */
    public void open(String accountId, Integer creditAmount, boolean creditAmountFallback,
            String agreementId, String productCode, Integer productVersion,
            AccountReasonCode reasonCode, Instant openedAt) {
        requireInProgress();
        this.outcome = AccountOutcome.OPENED;
        this.reasonCode = reasonCode;
        this.accountId = accountId;
        this.creditAmount = creditAmount;
        this.creditAmountFallback = creditAmountFallback;
        this.agreementId = agreementId;
        this.productCode = productCode;
        this.productVersion = productVersion;
        this.openedAt = openedAt;
    }

    /** UC-02: the engine's decision that the core is unavailable after exhausting its retry budget. */
    public void fail(AccountReasonCode reasonCode) {
        requireInProgress();
        this.outcome = AccountOutcome.FAILED;
        this.reasonCode = reasonCode;
    }

    private void requireInProgress() {
        if (outcome != AccountOutcome.IN_PROGRESS) {
            throw new IllegalStateException(
                    "account_record " + applicationId + " is already " + outcome + ", not IN_PROGRESS");
        }
    }

    /**
     * UC-04 — the retry engine's decision on an already-{@code FAILED} case: the core confirmed
     * — created or adopted. Never touches {@code creditAmount}/{@code productCode}: the retry
     * that calls this is a re-run of a case the engine above already gave up on, not an edit.
     */
    public void markOpened(String accountId, AccountReasonCode reasonCode) {
        this.outcome = AccountOutcome.OPENED;
        this.reasonCode = reasonCode;
        this.accountId = accountId;
        this.openedAt = Instant.now();
    }

    /** UC-04 — the retry budget was exhausted again — the core is still unreachable. */
    public void markFailed(AccountReasonCode reasonCode) {
        this.outcome = AccountOutcome.FAILED;
        this.reasonCode = reasonCode;
    }

    /**
     * UC-07: a human corrects only the outcome and, when opening, the confirmed account id.
     *
     * <p>The machine's reason code and attempt history remain untouched. The operator's reason is
     * stored separately in {@code override_log}, preserving both versions of the story.</p>
     */
    public void overrideOutcome(AccountOutcome newOutcome, String confirmedAccountId) {
        if (newOutcome != AccountOutcome.OPENED && newOutcome != AccountOutcome.FAILED) {
            throw new IllegalArgumentException("override outcome must be OPENED or FAILED");
        }
        if (newOutcome == AccountOutcome.OPENED
                && (confirmedAccountId == null || confirmedAccountId.isBlank())) {
            throw new IllegalArgumentException("account id is required when overriding to OPENED");
        }
        this.outcome = newOutcome;
        if (newOutcome == AccountOutcome.OPENED) {
            this.accountId = confirmedAccountId;
            this.openedAt = Instant.now();
        } else {
            this.accountId = null;
            this.openedAt = null;
        }
    }

    public String getApplicationId() {
        return applicationId;
    }

    public String getReference() {
        return reference;
    }

    public AccountOutcome getOutcome() {
        return outcome;
    }

    public AccountReasonCode getReasonCode() {
        return reasonCode;
    }

    public String getAccountId() {
        return accountId;
    }

    public Integer getCreditAmount() {
        return creditAmount;
    }

    public boolean isCreditAmountFallback() {
        return creditAmountFallback;
    }

    public String getAgreementId() {
        return agreementId;
    }

    public String getProductCode() {
        return productCode;
    }

    public Integer getProductVersion() {
        return productVersion;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getCardId() {
        return cardId;
    }

    public Integer getCoreConfigVersion() {
        return coreConfigVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }
}
