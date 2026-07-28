package com.neobank.module.repository;

import com.neobank.module.model.CoreAttempt;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only core call history for a case. */
public interface CoreAttemptRepository extends JpaRepository<CoreAttempt, Long> {

    List<CoreAttempt> findAllByApplicationIdOrderByOccurredAtAscIdAsc(String applicationId);
}
