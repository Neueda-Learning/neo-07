package com.neobank.module.service;

import com.neobank.module.controller.InvalidOverrideException;
import com.neobank.module.dto.OverrideCaseRequest;
import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.model.CaseNotFoundException;
import com.neobank.module.model.OverrideLog;
import com.neobank.module.repository.AccountRecordRepository;
import com.neobank.module.repository.OverrideLogRepository;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-07's narrow write transaction: update the anchor and append its audit entry together.
 *
 * <p>No Core or orchestrator client belongs here. The callback happens only after this method
 * returns, which means both database writes have committed before the journey is resumed.</p>
 */
@Service
public class OverridePersistenceService {

    private final AccountRecordRepository accountRecords;
    private final OverrideLogRepository overrides;

    public OverridePersistenceService(AccountRecordRepository accountRecords,
                                      OverrideLogRepository overrides) {
        this.accountRecords = accountRecords;
        this.overrides = overrides;
    }

    @Transactional
    public OverrideResult apply(String applicationId, OverrideCaseRequest request) {
        validate(request);

        AccountRecord account = accountRecords.findByApplicationIdForUpdate(applicationId)
                .orElseThrow(() -> new CaseNotFoundException(applicationId));
        String auditAccountId = request.newOutcome() == AccountOutcome.OPENED
                ? request.accountId()
                : null;

        if (isExactReplay(account, request, auditAccountId)) {
            return new OverrideResult(account.getOutcome(), false);
        }
        if (account.getOutcome() == request.newOutcome()) {
            throw new InvalidOverrideException(
                    "newOutcome must differ from the current outcome unless replaying the same override");
        }

        AccountOutcome oldOutcome = account.getOutcome();
        account.overrideOutcome(request.newOutcome(), auditAccountId);
        accountRecords.save(account);
        overrides.save(new OverrideLog(
                applicationId,
                oldOutcome,
                request.newOutcome(),
                auditAccountId,
                request.reason(),
                request.operator()));
        return new OverrideResult(account.getOutcome(), true);
    }

    private void validate(OverrideCaseRequest request) {
        if (request.newOutcome() != AccountOutcome.OPENED
                && request.newOutcome() != AccountOutcome.FAILED) {
            throw new InvalidOverrideException("newOutcome must be OPENED or FAILED");
        }
        if (request.newOutcome() == AccountOutcome.OPENED && request.accountId() == null) {
            throw new InvalidOverrideException("accountId is required when newOutcome is OPENED");
        }
    }

    private boolean isExactReplay(AccountRecord account, OverrideCaseRequest request,
                                  String auditAccountId) {
        if (account.getOutcome() != request.newOutcome()) {
            return false;
        }
        if (request.newOutcome() == AccountOutcome.OPENED
                && !Objects.equals(account.getAccountId(), auditAccountId)) {
            return false;
        }
        return overrides.findTopByApplicationIdOrderByOverriddenAtDescIdDesc(
                        account.getApplicationId())
                .filter(latest -> latest.getNewOutcome() == request.newOutcome())
                .filter(latest -> Objects.equals(latest.getAccountId(), auditAccountId))
                .filter(latest -> latest.getReason().equals(request.reason()))
                .filter(latest -> latest.getOperator().equals(request.operator()))
                .isPresent();
    }

    public record OverrideResult(AccountOutcome outcome, boolean changed) {
    }
}
