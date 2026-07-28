package com.neobank.module.repository;

import com.neobank.module.model.OverrideLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only operator correction history for a case. */
public interface OverrideLogRepository extends JpaRepository<OverrideLog, Long> {

    List<OverrideLog> findAllByApplicationIdOrderByOverriddenAtAscIdAsc(String applicationId);
}
