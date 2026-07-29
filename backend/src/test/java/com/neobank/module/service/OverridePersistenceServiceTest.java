package com.neobank.module.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neobank.module.controller.InvalidOverrideException;
import com.neobank.module.dto.OverrideCaseRequest;
import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountReasonCode;
import com.neobank.module.model.AccountRecord;
import com.neobank.module.model.CaseNotFoundException;
import com.neobank.module.model.OverrideLog;
import com.neobank.module.repository.AccountRecordRepository;
import com.neobank.module.repository.OverrideLogRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OverridePersistenceServiceTest {

    private AccountRecordRepository accounts;
    private OverrideLogRepository overrides;
    private OverridePersistenceService service;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRecordRepository.class);
        overrides = mock(OverrideLogRepository.class);
        service = new OverridePersistenceService(accounts, overrides);
    }

    @Test
    void opensAFailedCaseAndAppendsThePermanentAuditEntry() {
        AccountRecord account = failedAccount();
        when(accounts.findByApplicationIdForUpdate("app-1234")).thenReturn(Optional.of(account));
        when(overrides.findTopByApplicationIdOrderByOverriddenAtDescIdDesc("app-1234"))
                .thenReturn(Optional.empty());

        OverridePersistenceService.OverrideResult result = service.apply(
                "app-1234",
                new OverrideCaseRequest(
                        AccountOutcome.OPENED,
                        "account confirmed by Core team ticket CORE-4411",
                        "b.dimovski",
                        "CC-0058291"));

        assertThat(result.changed()).isTrue();
        assertThat(result.outcome()).isEqualTo(AccountOutcome.OPENED);
        assertThat(account.getOutcome()).isEqualTo(AccountOutcome.OPENED);
        assertThat(account.getAccountId()).isEqualTo("CC-0058291");
        assertThat(account.getOpenedAt()).isNotNull();
        assertThat(account.getReasonCode()).isEqualTo(AccountReasonCode.ACC_CORE_UNAVAILABLE);

        ArgumentCaptor<OverrideLog> audit = ArgumentCaptor.forClass(OverrideLog.class);
        verify(overrides).save(audit.capture());
        assertThat(audit.getValue().getOldOutcome()).isEqualTo(AccountOutcome.FAILED);
        assertThat(audit.getValue().getNewOutcome()).isEqualTo(AccountOutcome.OPENED);
        assertThat(audit.getValue().getReason()).contains("CORE-4411");
        assertThat(audit.getValue().getOperator()).isEqualTo("b.dimovski");
    }

    @Test
    void exactReplayDoesNotWriteASecondAuditEntry() {
        AccountRecord account = failedAccount();
        account.overrideOutcome(AccountOutcome.OPENED, "CC-0058291");
        OverrideLog latest = new OverrideLog(
                "app-1234",
                AccountOutcome.FAILED,
                AccountOutcome.OPENED,
                "CC-0058291",
                "confirmed",
                "operator-1");
        when(accounts.findByApplicationIdForUpdate("app-1234")).thenReturn(Optional.of(account));
        when(overrides.findTopByApplicationIdOrderByOverriddenAtDescIdDesc("app-1234"))
                .thenReturn(Optional.of(latest));

        OverridePersistenceService.OverrideResult result = service.apply(
                "app-1234",
                new OverrideCaseRequest(
                        AccountOutcome.OPENED,
                        "confirmed",
                        "operator-1",
                        "CC-0058291"));

        assertThat(result.changed()).isFalse();
        verify(accounts, never()).save(any());
        verify(overrides, never()).save(any());
    }

    @Test
    void openedRequiresAConfirmedAccountId() {
        assertThatThrownBy(() -> service.apply(
                "app-1234",
                new OverrideCaseRequest(AccountOutcome.OPENED, "confirmed", "operator-1", null)))
                .isInstanceOf(InvalidOverrideException.class)
                .hasMessageContaining("accountId");

        verify(accounts, never()).findByApplicationIdForUpdate(any());
    }

    @Test
    void failsAnOpenedCaseAndClearsOpeningFields() {
        AccountRecord account = new AccountRecord("app-opened", "acc-opened");
        account.open(
                "CC-0058291",
                3000,
                false,
                "agreement-1",
                "CREDIT_CARD_REWARDS",
                1,
                AccountReasonCode.ACC_OPENED,
                Instant.parse("2026-07-28T09:00:00Z"));
        when(accounts.findByApplicationIdForUpdate("app-opened")).thenReturn(Optional.of(account));

        service.apply(
                "app-opened",
                new OverrideCaseRequest(
                        AccountOutcome.FAILED,
                        "Core team rejected the account match",
                        "operator-2",
                        null));

        assertThat(account.getOutcome()).isEqualTo(AccountOutcome.FAILED);
        assertThat(account.getAccountId()).isNull();
        assertThat(account.getOpenedAt()).isNull();
        assertThat(account.getReasonCode()).isEqualTo(AccountReasonCode.ACC_OPENED);
    }

    @Test
    void firstSameOutcomeRequestIsRejectedBeforeTheDatabaseConstraint() {
        AccountRecord account = failedAccount();
        when(accounts.findByApplicationIdForUpdate("app-1234")).thenReturn(Optional.of(account));
        when(overrides.findTopByApplicationIdOrderByOverriddenAtDescIdDesc("app-1234"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(
                "app-1234",
                new OverrideCaseRequest(
                        AccountOutcome.FAILED,
                        "confirmed",
                        "operator-1",
                        null)))
                .isInstanceOf(InvalidOverrideException.class)
                .hasMessageContaining("differ");

        verify(accounts, never()).save(any());
        verify(overrides, never()).save(any());
    }

    @Test
    void inProgressIsNotAPermittedOverrideOutcome() {
        assertThatThrownBy(() -> service.apply(
                "app-1234",
                new OverrideCaseRequest(AccountOutcome.IN_PROGRESS, "confirmed", "operator-1", null)))
                .isInstanceOf(InvalidOverrideException.class)
                .hasMessageContaining("OPENED or FAILED");
    }

    @Test
    void unknownCaseIsNotFound() {
        when(accounts.findByApplicationIdForUpdate("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.apply(
                "missing",
                new OverrideCaseRequest(AccountOutcome.FAILED, "confirmed", "operator-1", null)))
                .isInstanceOf(CaseNotFoundException.class);
    }

    private AccountRecord failedAccount() {
        AccountRecord account = new AccountRecord("app-1234", "acc-000123");
        account.fail(AccountReasonCode.ACC_CORE_UNAVAILABLE);
        return account;
    }
}
