package com.neobank.module.service;

import com.neobank.module.controller.CoreUnavailableException;
import com.neobank.module.core.CoreAccountView;
import com.neobank.module.dto.DuplicateReportResponse;
import com.neobank.module.dto.DuplicateRow;
import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.model.CoreConfig;
import com.neobank.module.model.DuplicateKind;
import com.neobank.module.repository.AccountRecordRepository;
import com.neobank.module.repository.CoreConfigRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-06 — the Duplicate Report. A live cross-check, recomputed on every visit, reading both
 * sides of the module/core boundary rather than trusting either alone (AC4): the core's own
 * account store (the ground truth for what it actually created — the mock is deliberately
 * non-idempotent) grouped by reference for {@link DuplicateKind#CORE_DUPLICATE}, and this
 * module's own OPENED records cross-checked against that same core data for
 * {@link DuplicateKind#MISSING_AT_CORE} — a case the module believes is opened, but whose
 * accountId the core's own list for that reference doesn't actually contain. Empty is not "no
 * data" — empty is the successful result (AC2/AC3), and either finding is a control failure
 * regardless of what the other side alone would say.
 */
@Service
public class DuplicateReportService {

    private final CoreConfigRepository coreConfigs;
    private final AccountRecordRepository accountRecords;
    private final CoreOpsClient coreClient;

    public DuplicateReportService(CoreConfigRepository coreConfigs, AccountRecordRepository accountRecords,
                                   CoreOpsClient coreClient) {
        this.coreConfigs = coreConfigs;
        this.accountRecords = accountRecords;
        this.coreClient = coreClient;
    }

    @Transactional(readOnly = true)
    public DuplicateReportResponse findDuplicates() {
        CoreConfig config = coreConfigs.findTopByOrderByVersionDesc()
                .orElseThrow(() -> new CoreUnavailableException("cannot verify — no core config"));

        List<CoreAccountView> accounts;
        try {
            accounts = coreClient.listAccounts(config.getCoreBaseUrl());
        } catch (Exception e) {
            throw new CoreUnavailableException("cannot verify — core unreachable");
        }

        Map<String, List<CoreAccountView>> byReference = accounts.stream()
                .collect(Collectors.groupingBy(CoreAccountView::reference));

        List<DuplicateRow> coreDuplicates = byReference.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> coreDuplicateRow(entry.getKey(), entry.getValue()))
                .toList();

        List<DuplicateRow> missingAtCore = accountRecords.findAllByOutcome(AccountOutcome.OPENED).stream()
                .filter(record -> !hasMatchingCoreAccount(record, byReference))
                .map(DuplicateReportService::missingAtCoreRow)
                .toList();

        List<DuplicateRow> duplicates = new ArrayList<>(coreDuplicates);
        duplicates.addAll(missingAtCore);

        return new DuplicateReportResponse(Instant.now(), accounts.size(), duplicates);
    }

    private boolean hasMatchingCoreAccount(AccountRecord record, Map<String, List<CoreAccountView>> byReference) {
        List<CoreAccountView> coreAccounts = byReference.get(record.getApplicationId());
        if (coreAccounts == null) {
            return false;
        }
        return coreAccounts.stream().anyMatch(account -> account.accountId().equals(record.getAccountId()));
    }

    private DuplicateRow coreDuplicateRow(String applicationId, List<CoreAccountView> accounts) {
        String reference = accountRecords.findById(applicationId).map(AccountRecord::getReference).orElse(null);
        List<String> accountIds = accounts.stream().map(CoreAccountView::accountId).toList();
        return new DuplicateRow(applicationId, reference, accountIds, DuplicateKind.CORE_DUPLICATE);
    }

    private static DuplicateRow missingAtCoreRow(AccountRecord record) {
        return new DuplicateRow(record.getApplicationId(), record.getReference(),
                Collections.emptyList(), DuplicateKind.MISSING_AT_CORE);
    }
}
