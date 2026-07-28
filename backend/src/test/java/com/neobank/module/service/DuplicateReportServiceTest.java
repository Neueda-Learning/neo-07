package com.neobank.module.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.neobank.module.controller.CoreUnavailableException;
import com.neobank.module.core.CoreAccountView;
import com.neobank.module.dto.DuplicateReportResponse;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.model.CoreConfig;
import com.neobank.module.repository.AccountRecordRepository;
import com.neobank.module.repository.CoreConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** UC-06 — the Duplicate Report's core-crossing logic, mocked at the CoreClient boundary. */
class DuplicateReportServiceTest {

    private CoreConfigRepository coreConfigs;
    private AccountRecordRepository accountRecords;
    private CoreClient coreClient;
    private DuplicateReportService service;

    private static CoreConfig config() {
        try {
            return new CoreConfig(1, 3, 2000, "http://core", new ObjectMapper().readTree("{}"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @BeforeEach
    void setUp() {
        coreConfigs = mock(CoreConfigRepository.class);
        accountRecords = mock(AccountRecordRepository.class);
        coreClient = mock(CoreClient.class);
        service = new DuplicateReportService(coreConfigs, accountRecords, coreClient);
        when(coreConfigs.findTopByOrderByVersionDesc()).thenReturn(Optional.of(config()));
    }

    @Test
    void noAccountsShareAReferenceMeansAnEmptyReport() {
        when(coreClient.listAccounts("http://core")).thenReturn(List.of(
                new CoreAccountView("CC-1", "app-1", Instant.now()),
                new CoreAccountView("CC-2", "app-2", Instant.now())));

        DuplicateReportResponse report = service.findDuplicates();

        assertThat(report.coreAccountsScanned()).isEqualTo(2);
        assertThat(report.duplicates()).isEmpty();
        assertThat(report.checkedAt()).isNotNull();
    }

    @Test
    void aReferenceWithTwoCoreAccountsIsADuplicateLinkedToItsCase() {
        when(coreClient.listAccounts("http://core")).thenReturn(List.of(
                new CoreAccountView("CC-1", "app-dupe", Instant.now()),
                new CoreAccountView("CC-2", "app-dupe", Instant.now()),
                new CoreAccountView("CC-3", "app-solo", Instant.now())));
        AccountRecord record = new AccountRecord("app-dupe", "acc-000900");
        when(accountRecords.findById("app-dupe")).thenReturn(Optional.of(record));

        DuplicateReportResponse report = service.findDuplicates();

        assertThat(report.coreAccountsScanned()).isEqualTo(3);
        assertThat(report.duplicates()).hasSize(1);
        var row = report.duplicates().get(0);
        assertThat(row.applicationId()).isEqualTo("app-dupe");
        assertThat(row.reference()).isEqualTo("acc-000900");
        assertThat(row.coreAccountIds()).containsExactlyInAnyOrder("CC-1", "CC-2");
    }

    @Test
    void aDuplicateWithNoModuleRecordStillAppearsWithANullLink() {
        when(coreClient.listAccounts("http://core")).thenReturn(List.of(
                new CoreAccountView("CC-1", "app-orphan", Instant.now()),
                new CoreAccountView("CC-2", "app-orphan", Instant.now())));
        when(accountRecords.findById("app-orphan")).thenReturn(Optional.empty());

        DuplicateReportResponse report = service.findDuplicates();

        assertThat(report.duplicates()).hasSize(1);
        assertThat(report.duplicates().get(0).reference()).isNull();
    }

    @Test
    void coreUnreachableIsAnAlarmNeverASilentlyEmptyReport() {
        when(coreClient.listAccounts("http://core")).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.findDuplicates())
                .isInstanceOf(CoreUnavailableException.class)
                .hasMessageContaining("cannot verify");
    }

    @Test
    void noCoreConfigAtAllIsAlsoUnverifiable() {
        when(coreConfigs.findTopByOrderByVersionDesc()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findDuplicates())
                .isInstanceOf(CoreUnavailableException.class)
                .hasMessageContaining("cannot verify");
    }
}
