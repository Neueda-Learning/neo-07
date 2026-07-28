package com.neobank.module.repository;

import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountReasonCode;
import com.neobank.module.model.AccountRecord;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence access for the one durable account-opening anchor per application. */
public interface AccountRecordRepository extends JpaRepository<AccountRecord, String> {

    Optional<AccountRecord> findByReference(String reference);

    List<AccountRecord> findAllByOrderByCreatedAtDesc();

    List<AccountRecord> findTop10ByApplicationIdContainingIgnoreCaseOrderByCreatedAtDesc(
            String applicationId);

    List<AccountRecord> findTop10ByOutcomeAndReasonCodeOrderByCreatedAtAsc(
            AccountOutcome outcome, AccountReasonCode reasonCode);

    /**
     * UC-01 id search. Fetches one row past the 10-row cap so the service can tell "exactly 10"
     * from "more exist" (AC2's "more — refine your search" flag) without a separate count query.
     */
    List<AccountRecord> findTop11ByApplicationIdContainingIgnoreCaseOrderByCreatedAtDesc(
            String applicationId);

    /** UC-01 name search's second half: the ids the orchestrator resolved, read back locally. */
    List<AccountRecord> findByApplicationIdInOrderByCreatedAtDesc(List<String> applicationIds);
}
