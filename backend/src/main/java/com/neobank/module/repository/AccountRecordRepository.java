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
}
