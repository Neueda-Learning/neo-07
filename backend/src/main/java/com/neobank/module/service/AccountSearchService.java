package com.neobank.module.service;

import com.neobank.module.dto.AccountSearchResponse;
import com.neobank.module.dto.AccountSearchResult;
import com.neobank.module.integrations.orchestrator.OrchestratorClient;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.repository.AccountRecordRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * UC-01 — Search Accounts. Finds an account case by application id or applicant name, without this
 * module ever storing applicant data (AC3).
 *
 * <p>Id search reads the local table directly. Name search has no local column to search, so a
 * name resolves to application ids through the orchestrator first ({@link
 * OrchestratorClient#searchApplicationIdsByName}), and only those ids are read back locally — the
 * schema never learns a name (AC3, AC6).</p>
 */
@Service
public class AccountSearchService {

    /** The board's row cap (AC2). */
    static final int LIMIT = 10;

    private final AccountRecordRepository accountRecords;
    private final OrchestratorClient orchestrator;

    public AccountSearchService(AccountRecordRepository accountRecords,
                                OrchestratorClient orchestrator) {
        this.accountRecords = accountRecords;
        this.orchestrator = orchestrator;
    }

    /**
     * Empty by default: no query, no rows fetched (AC1). A blank/whitespace-only query is treated
     * the same as no query at all.
     */
    @Transactional(readOnly = true)
    public AccountSearchResponse search(String q) {
        String needle = q == null ? "" : q.strip();
        if (needle.isEmpty()) {
            return AccountSearchResponse.EMPTY;
        }

        // LinkedHashMap keeps insertion order stable before the final sort; a case matching both
        // by id and by name must still count once.
        Map<String, AccountRecord> byId = new LinkedHashMap<>();
        for (AccountRecord row : accountRecords
                .findTop11ByApplicationIdContainingIgnoreCaseOrderByCreatedAtDesc(needle)) {
            byId.put(row.getApplicationId(), row);
        }

        List<String> nameMatchIds = orchestrator.searchApplicationIdsByName(needle);
        if (!nameMatchIds.isEmpty()) {
            for (AccountRecord row : accountRecords.findByApplicationIdInOrderByCreatedAtDesc(nameMatchIds)) {
                byId.putIfAbsent(row.getApplicationId(), row);
            }
        }

        List<AccountRecord> merged = byId.values().stream()
                .sorted(Comparator.comparing(AccountRecord::getCreatedAt).reversed())
                .toList();

        boolean hasMore = merged.size() > LIMIT;
        List<AccountSearchResult> results = merged.stream()
                .limit(LIMIT)
                .map(AccountSearchResult::of)
                .toList();
        return new AccountSearchResponse(results, hasMore);
    }
}
