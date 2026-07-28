package com.neobank.module.dto;

import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.model.CoreAttempt;
import com.neobank.module.model.CoreAttemptKind;
import com.neobank.module.model.CoreAttemptResult;
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
        List<AttemptView> attempts) {

    /** Field names match the UC-02 contract example exactly: {@code cycle}, not {@code cycleNo}. */
    public record AttemptView(int cycle, CoreAttemptKind kind, CoreAttemptResult result, long latencyMs) {

        public static AttemptView of(CoreAttempt attempt) {
            return new AttemptView(attempt.getCycleNo(), attempt.getKind(), attempt.getResult(),
                    attempt.getLatencyMs());
        }
    }

    public static CaseDetailView of(AccountRecord record, List<CoreAttempt> attempts) {
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
                attempts.stream().map(AttemptView::of).toList());
    }
}
