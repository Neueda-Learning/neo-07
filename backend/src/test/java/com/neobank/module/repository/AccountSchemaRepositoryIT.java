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
 * Proves that Liquibase 002-007 and the four JPA mappings agree on the deployed database
 * family.
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
    void schemaStartsWithOnlyTheSeededCoreConfigVersion() {
        assertThat(accounts.findAll()).isEmpty();
        assertThat(attempts.findAll()).isEmpty();
        assertThat(overrides.findAll()).isEmpty();
        assertThat(configs.findAll()).hasSize(1);
    }

    @Test
    void coreConfigV1IsSeededOnFirstBoot() {
        // UC-08 AC#2 checkpoint.
        CoreConfig seed = configs.findTopByOrderByVersionDesc().orElseThrow();

        assertThat(seed.getVersion()).isEqualTo(1);
        assertThat(seed.getRetryBudget()).isEqualTo(3);
        assertThat(seed.getTimeoutMs()).isEqualTo(2000);
        assertThat(seed.getCatalogue().has("CREDIT_CARD_STANDARD")).isTrue();
        assertThat(seed.getCatalogue().has("CREDIT_CARD_REWARDS")).isTrue();
        assertThat(seed.getCatalogue().has("CREDIT_CARD_STUDENT")).isTrue();
    }

    @Test
    void domainRowsAndAuditChildrenRoundTrip() throws Exception {
        // version 2: the seeded 007 changeset already occupies version 1 on a fresh schema.
        configs.saveAndFlush(new CoreConfig(
                2,
                3,
                2000,
                "http://mock-core:8090",
                objectMapper.readTree("""
                        {"CREDIT_CARD_REWARDS":{"apr":24.9,"limitMin":500,"limitMax":10000}}
                        """)));

        AccountRecord account = new AccountRecord("APP-1", "acc-APP-1");
        account.pinCoreConfig(2);
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
        assertThat(reloaded.getCoreConfigVersion()).isEqualTo(2);
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

    // UC-02 AC#5's actual proof — two concurrent inserts of the same applicationId hitting the
    // real PRIMARY KEY constraint — lives in AccountOpeningConcurrencyIT, which drives the real
    // service path (accountRecords.save(new AccountRecord(...)) inside
    // createAccountRecordIfAbsent). A same-applicationId round trip through this repository
    // directly cannot be used to prove it: with a manually-assigned, non-generated @Id, Spring
    // Data JPA's new-vs-existing detection falls back to merge (SELECT-then-write) rather than a
    // blind INSERT, so a second saveAndFlush for the same id silently updates instead of
    // violating the constraint — a JPA test-mechanics quirk, not a gap in the real guard.
}
