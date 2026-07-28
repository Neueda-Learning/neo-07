package com.neobank.module.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.model.CoreAttempt;
import com.neobank.module.model.CoreAttemptKind;
import com.neobank.module.model.CoreAttemptResult;
import com.neobank.module.model.CoreConfig;
import com.neobank.module.model.OverrideLog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves that Liquibase 002 and the four JPA mappings agree on the deployed database family.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class AccountSchemaRepositoryIT {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("neo_07");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired
    AccountRecordRepository accounts;

    @Autowired
    CoreConfigRepository configs;

    @Autowired
    CoreAttemptRepository attempts;

    @Autowired
    OverrideLogRepository overrides;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void schemaStartsWithoutInventingAnInvalidCatalogueSeed() {
        assertThat(accounts.findAll()).isEmpty();
        assertThat(configs.findAll()).isEmpty();
        assertThat(attempts.findAll()).isEmpty();
        assertThat(overrides.findAll()).isEmpty();
    }

    @Test
    void domainRowsAndAuditChildrenRoundTrip() throws Exception {
        configs.saveAndFlush(new CoreConfig(
                1,
                3,
                2000,
                "http://mock-core:8090",
                objectMapper.readTree("""
                        {"CREDIT_CARD_REWARDS":{"apr":24.9,"limitMin":500,"limitMax":10000}}
                        """)));

        AccountRecord account = new AccountRecord("APP-1", "acc-APP-1");
        account.pinCoreConfig(1);
        accounts.saveAndFlush(account);

        attempts.saveAndFlush(new CoreAttempt(
                "APP-1",
                1,
                CoreAttemptKind.PROBE,
                CoreAttemptResult.MISS,
                "APP-1",
                41L));

        overrides.saveAndFlush(new OverrideLog(
                "APP-1",
                AccountOutcome.IN_PROGRESS,
                AccountOutcome.FAILED,
                null,
                "core team confirmed the outage",
                "operator-1"));

        AccountRecord reloaded = accounts.findById("APP-1").orElseThrow();
        assertThat(reloaded.getOutcome()).isEqualTo(AccountOutcome.IN_PROGRESS);
        assertThat(reloaded.getCoreConfigVersion()).isEqualTo(1);
        assertThat(reloaded.getCreatedAt()).isNotNull();

        assertThat(attempts.findAllByApplicationIdOrderByOccurredAtAscIdAsc("APP-1"))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.getKind()).isEqualTo(CoreAttemptKind.PROBE);
                    assertThat(attempt.getResult()).isEqualTo(CoreAttemptResult.MISS);
                });

        assertThat(overrides.findAllByApplicationIdOrderByOverriddenAtAscIdAsc("APP-1"))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.getNewOutcome()).isEqualTo(AccountOutcome.FAILED);
                    assertThat(entry.getOperator()).isEqualTo("operator-1");
                });
    }

    @Test
    void referenceIsUniqueAcrossApplicationIds() {
        accounts.saveAndFlush(new AccountRecord("APP-2", "acc-shared01"));

        AccountRecord duplicate = new AccountRecord("APP-3", "acc-shared01");
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () -> accounts.saveAndFlush(duplicate));
    }

    @Test
    void findByIdIsTheIdempotencyCheckUc00ReliesOn() {
        accounts.saveAndFlush(new AccountRecord("APP-4", "acc-idem0001"));

        assertThat(accounts.findById("APP-4")).isPresent();
        // A second /execute for the same id must see the existing row rather than needing a
        // second insert — this is the exact check ApplicationService.createAccountRecordIfAbsent
        // makes before saving.
        assertThat(accounts.findAll()).hasSize(1);
    }
}
