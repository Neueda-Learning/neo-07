package com.neobank.module.service;

import com.neobank.module.controller.CoreUnavailableException;
import com.neobank.module.core.CoreAccountView;
import com.neobank.module.dto.DuplicateReportResponse;
import com.neobank.module.dto.DuplicateRow;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.model.CoreConfig;
import com.neobank.module.repository.AccountRecordRepository;
import com.neobank.module.repository.CoreConfigRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-06 — the Duplicate Report. A live cross-check, recomputed on every visit: it reads the
 * core's own account store (the ground truth — the mock is deliberately non-idempotent) and
 * links whatever it finds back to this module's own table. Empty is not "no data" — empty is the
 * successful result (AC2/AC3), and a reference with more than one core account is a control
 * failure regardless of what this module's own row says (AC4).
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

        List<DuplicateRow> duplicates = byReference.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> toRow(entry.getKey(), entry.getValue()))
                .toList();

        return new DuplicateReportResponse(Instant.now(), accounts.size(), duplicates);
    }

    private DuplicateRow toRow(String applicationId, List<CoreAccountView> accounts) {
        String reference = accountRecords.findById(applicationId).map(AccountRecord::getReference).orElse(null);
        List<String> accountIds = accounts.stream().map(CoreAccountView::accountId).toList();
        return new DuplicateRow(applicationId, reference, accountIds);
    }
}
