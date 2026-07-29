package com.neobank.module.dto;

import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.model.CoreAttempt;
import com.neobank.module.model.CoreAttemptKind;
import com.neobank.module.model.CoreAttemptResult;
import com.neobank.module.model.OverrideLog;
import java.time.Instant;
import java.util.List;

/** UC-02 AC#1 — {@code GET /cases/{applicationId}}: the anchor record plus its ordered attempt log. */
public record CaseDetailView(
        AccountOutcome outcome,
        String reference,
        String accountId,
        Integer creditAmount,
        String agreementId,
        String productCode,
        Integer productVersion,
        String customerId,
        String cardId,
        Integer coreConfigVersion,
        List<AttemptView> attempts,
        List<OverrideView> overrides) {

    /** Field names match the UC-02 contract example exactly: {@code cycle}, not {@code cycleNo}. */
    public record AttemptView(int cycle, CoreAttemptKind kind, CoreAttemptResult result, long latencyMs) {

        public static AttemptView of(CoreAttempt attempt) {
            return new AttemptView(attempt.getCycleNo(), attempt.getKind(), attempt.getResult(),
                    attempt.getLatencyMs());
        }
    }

    /** UC-07's permanent human-decision history, ordered oldest first. */
    public record OverrideView(
            AccountOutcome oldOutcome,
            AccountOutcome newOutcome,
            String accountId,
            String reason,
            String operator,
            Instant overriddenAt) {

        public static OverrideView of(OverrideLog override) {
            return new OverrideView(
                    override.getOldOutcome(),
                    override.getNewOutcome(),
                    override.getAccountId(),
                    override.getReason(),
                    override.getOperator(),
                    override.getOverriddenAt());
        }
    }

    public static CaseDetailView of(AccountRecord record, List<CoreAttempt> attempts,
                                    List<OverrideLog> overrides) {
        return new CaseDetailView(
                record.getOutcome(),
                record.getReference(),
                record.getAccountId(),
                record.getCreditAmount(),
                record.getAgreementId(),
                record.getProductCode(),
                record.getProductVersion(),
                record.getCustomerId(),
                record.getCardId(),
                record.getCoreConfigVersion(),
                attempts.stream().map(AttemptView::of).toList(),
                overrides.stream().map(OverrideView::of).toList());
    }
}
