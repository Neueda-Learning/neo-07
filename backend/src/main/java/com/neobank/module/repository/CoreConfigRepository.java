package com.neobank.module.repository;

import com.neobank.module.model.CoreConfig;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Insert-only configuration history; the greatest version is current. */
public interface CoreConfigRepository extends JpaRepository<CoreConfig, Integer> {

    Optional<CoreConfig> findTopByOrderByVersionDesc();
}
