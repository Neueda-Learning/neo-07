package com.neobank.module.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobank.module.integrations.orchestrator.Application;
import com.neobank.module.integrations.orchestrator.ApplicationRequest;
import com.neobank.module.model.AccountOutcome;
import com.neobank.module.repository.AccountRecordRepository;
import com.neobank.module.repository.CoreAttemptRepository;
import com.neobank.module.support.CoreConfigTestSupport;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * UC-02 AC#5's own required concurrency test, against real MySQL — proves the DB-level unique
 * constraint on {@code application_id}, not a Java-level lock, is what resolves the race: two
 * threads racing {@link ApplicationService#processApplication} for the same id must produce
 * exactly one {@code account_record} row and run the engine exactly once.
 *
 * <p>{@code webEnvironment = RANDOM_PORT}: the engine makes a real HTTP call to the mock core, so
 * a real server must be bound. See {@link CoreConfigTestSupport}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class AccountOpeningConcurrencyIT {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("neo_07");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private AccountRecordRepository accountRecords;

    @Autowired
    private CoreAttemptRepository coreAttempts;

    @BeforeEach
    void pointCoreConfigAtThisPort() {
        CoreConfigTestSupport.pointCoreBaseUrlAt(dataSource, port);
    }

    private static ApplicationRequest request(String id) {
        Application application = new Application(
                id, "MOBILE_APP", "2026-07-25T09:14:00Z",
                new Application.Applicant("Maria Nowak", "1996-04-11", null, null, null, null,
                        null, null, null, null, null),
                null, null, null,
                new Application.Product("CREDIT_CARD_REWARDS", 3000),
                null, null);
        return new ApplicationRequest(id, "corr-1", "process-application", application);
    }

    @Test
    void twoConcurrentExecutesForTheSameIdProduceExactlyOneRowAndOneCoreAccount() throws Exception {
        // Unique per run: the mock core's in-memory store is a singleton shared across every test
        // in this JVM, so a fixed id would probe-HIT against a leftover account from an earlier
        // test and short-circuit the clean 2-attempt path this test asserts.
        String applicationId = "APP-CONCURRENCY-" + java.util.UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);

        Runnable task = () -> {
            ready.countDown();
            try {
                go.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            applicationService.processApplication(request(applicationId));
        };

        Thread t1 = new Thread(task);
        Thread t2 = new Thread(task);
        t1.start();
        t2.start();
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        t1.join(10_000);
        t2.join(10_000);

        assertThat(accountRecords.findAllByOrderByCreatedAtDesc().stream()
                .filter(a -> a.getApplicationId().equals(applicationId))
                .count()).isEqualTo(1);

        var account = accountRecords.findById(applicationId).orElseThrow();
        assertThat(account.getOutcome()).isEqualTo(AccountOutcome.OPENED);

        // Exactly one thread's engine ran: a clean probe-miss+open-created cycle is 2 attempts,
        // not 4 — if both threads had run the engine, the core would show two accounts and this
        // module would show two attempt sequences for the one row.
        assertThat(coreAttempts.findAllByApplicationIdOrderByOccurredAtAscIdAsc(applicationId)).hasSize(2);
    }
}
