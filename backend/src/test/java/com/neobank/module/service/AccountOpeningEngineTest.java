package com.neobank.module.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountReasonCode;
import com.neobank.module.model.CoreAttemptResult;
import com.neobank.module.service.AccountOpeningEngine.CoreCallException;
import com.neobank.module.service.AccountOpeningEngine.CoreCallOutcome;
import com.neobank.module.service.AccountOpeningEngine.CreditTerms;
import com.neobank.module.service.AccountOpeningEngine.EngineResult;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Table-driven, no Spring/Mockito — {@link AccountOpeningEngine} is pure, so a hand-written
 * lambda queue drives each path exactly like the brief's own build note prescribes.
 */
class AccountOpeningEngineTest {

    /** A canned sequence of calls; each invocation pops the next canned outcome/exception. */
    private static AccountOpeningEngine.CoreCaller scripted(AtomicInteger callCount, Object... script) {
        Deque<Object> queue = new ArrayDeque<>(List.of(script));
        return (applicationId, cycle) -> {
            callCount.incrementAndGet();
            Object next = queue.poll();
            if (next instanceof CoreCallException e) {
                throw e;
            }
            return (CoreCallOutcome) next;
        };
    }

    @Test
    void cleanPathProbeMissThenOpenCreated() {
        AtomicInteger probeCalls = new AtomicInteger();
        AtomicInteger openCalls = new AtomicInteger();
        AccountOpeningEngine.CoreCaller probe = scripted(probeCalls,
                new CoreCallOutcome(CoreAttemptResult.MISS, null, 10));
        AccountOpeningEngine.CoreCaller open = scripted(openCalls,
                new CoreCallOutcome(CoreAttemptResult.CREATED, "CC-0058291", 212));

        EngineResult result = AccountOpeningEngine.run(3, "app-1234", 3000, CreditTerms.none(), probe, open);

        assertThat(result.outcome()).isEqualTo(AccountOutcome.OPENED);
        assertThat(result.reasonCode()).isEqualTo(AccountReasonCode.ACC_OPENED);
        assertThat(result.accountId()).isEqualTo("CC-0058291");
        assertThat(probeCalls.get()).isEqualTo(1);
        assertThat(openCalls.get()).isEqualTo(1);
    }

    @Test
    void probeHitImmediatelyAdoptsWithoutCallingOpen() {
        AtomicInteger probeCalls = new AtomicInteger();
        AtomicInteger openCalls = new AtomicInteger();
        AccountOpeningEngine.CoreCaller probe = scripted(probeCalls,
                new CoreCallOutcome(CoreAttemptResult.HIT, "CC-existing1", 5));
        AccountOpeningEngine.CoreCaller open = scripted(openCalls);

        EngineResult result = AccountOpeningEngine.run(3, "app-1234", 3000, CreditTerms.none(), probe, open);

        assertThat(result.outcome()).isEqualTo(AccountOutcome.OPENED);
        assertThat(result.reasonCode()).isEqualTo(AccountReasonCode.ACC_DUPLICATE_PREVENTED);
        assertThat(result.accountId()).isEqualTo("CC-existing1");
        assertThat(probeCalls.get()).isEqualTo(1);
        assertThat(openCalls.get()).isEqualTo(0);
    }

    @Test
    void adoptOnTimeoutProbeMissOpenTimeoutRecoveryProbeHit() {
        AtomicInteger probeCalls = new AtomicInteger();
        AtomicInteger openCalls = new AtomicInteger();
        AccountOpeningEngine.CoreCaller probe = scripted(probeCalls,
                new CoreCallOutcome(CoreAttemptResult.MISS, null, 8),
                new CoreCallOutcome(CoreAttemptResult.HIT, "CC-0058291", 4));
        AccountOpeningEngine.CoreCaller open = scripted(openCalls,
                new CoreCallException(CoreAttemptResult.TIMEOUT, "core call timed out"));

        EngineResult result = AccountOpeningEngine.run(3, "app-1234", 3000, CreditTerms.none(), probe, open);

        assertThat(result.outcome()).isEqualTo(AccountOutcome.OPENED);
        assertThat(result.reasonCode()).isEqualTo(AccountReasonCode.ACC_DUPLICATE_PREVENTED);
        assertThat(result.accountId()).isEqualTo("CC-0058291");
        // 2 probes + 1 open = 3 attempts total, all in the same cycle (AC#3's checkpoint shape).
        assertThat(probeCalls.get()).isEqualTo(2);
        assertThat(openCalls.get()).isEqualTo(1);
    }

    @Test
    void budgetExhaustedEndsFailedAfterEveryCycleErrors() {
        AtomicInteger probeCalls = new AtomicInteger();
        AtomicInteger openCalls = new AtomicInteger();
        AccountOpeningEngine.CoreCaller probe = scripted(probeCalls,
                new CoreCallOutcome(CoreAttemptResult.MISS, null, 5),
                new CoreCallOutcome(CoreAttemptResult.MISS, null, 5),
                new CoreCallOutcome(CoreAttemptResult.MISS, null, 5));
        AccountOpeningEngine.CoreCaller open = scripted(openCalls,
                new CoreCallException(CoreAttemptResult.ERROR, "core call failed"),
                new CoreCallException(CoreAttemptResult.ERROR, "core call failed"),
                new CoreCallException(CoreAttemptResult.ERROR, "core call failed"));

        EngineResult result = AccountOpeningEngine.run(3, "app-1240", 3000, CreditTerms.none(), probe, open);

        assertThat(result.outcome()).isEqualTo(AccountOutcome.FAILED);
        assertThat(result.reasonCode()).isEqualTo(AccountReasonCode.ACC_CORE_UNAVAILABLE);
        assertThat(result.accountId()).isNull();
        assertThat(result.openedAt()).isNull();
        assertThat(probeCalls.get()).isEqualTo(3);
        assertThat(openCalls.get()).isEqualTo(3);
    }

    @Test
    void budgetExhaustedEndsFailedWhenTheProbeItselfErrorsEveryCycle() {
        // The core being fully down (kill switch) fails PROBE too, not just OPEN — a probe
        // ERROR/TIMEOUT must not short-circuit the cycle, it must still fall through to attempt
        // OPEN, exactly like a MISS would (regression test for the bug where an unguarded probe
        // exception escaped run() uncaught and left the case stuck IN_PROGRESS forever).
        AtomicInteger probeCalls = new AtomicInteger();
        AtomicInteger openCalls = new AtomicInteger();
        AccountOpeningEngine.CoreCaller probe = scripted(probeCalls,
                new CoreCallException(CoreAttemptResult.ERROR, "core call failed"),
                new CoreCallException(CoreAttemptResult.ERROR, "core call failed"),
                new CoreCallException(CoreAttemptResult.ERROR, "core call failed"));
        AccountOpeningEngine.CoreCaller open = scripted(openCalls,
                new CoreCallException(CoreAttemptResult.ERROR, "core call failed"),
                new CoreCallException(CoreAttemptResult.ERROR, "core call failed"),
                new CoreCallException(CoreAttemptResult.ERROR, "core call failed"));

        EngineResult result = AccountOpeningEngine.run(3, "app-1240", 3000, CreditTerms.none(), probe, open);

        assertThat(result.outcome()).isEqualTo(AccountOutcome.FAILED);
        assertThat(result.reasonCode()).isEqualTo(AccountReasonCode.ACC_CORE_UNAVAILABLE);
        assertThat(result.accountId()).isNull();
        assertThat(result.openedAt()).isNull();
        // 3 cycles, both calls attempted every cycle (AC#2's exact "6 attempts" checkpoint shape).
        assertThat(probeCalls.get()).isEqualTo(3);
        assertThat(openCalls.get()).isEqualTo(3);
    }

    @Test
    void creditTermsAbsentFallsBackToRequestedCreditLimit() {
        AccountOpeningEngine.CoreCaller probe = (id, cycle) -> new CoreCallOutcome(CoreAttemptResult.MISS, null, 1);
        AccountOpeningEngine.CoreCaller open = (id, cycle) -> new CoreCallOutcome(CoreAttemptResult.CREATED, "CC-1", 1);

        EngineResult result = AccountOpeningEngine.run(3, "app-1", 3000, CreditTerms.none(), probe, open);

        assertThat(result.creditAmount()).isEqualTo(3000);
        assertThat(result.creditAmountFallback()).isTrue();
        assertThat(result.agreementId()).isNull();
    }

    @Test
    void creditTermsPresentUsesTheApprovedLimitNotTheFallback() {
        AccountOpeningEngine.CoreCaller probe = (id, cycle) -> new CoreCallOutcome(CoreAttemptResult.MISS, null, 1);
        AccountOpeningEngine.CoreCaller open = (id, cycle) -> new CoreCallOutcome(CoreAttemptResult.CREATED, "CC-1", 1);

        EngineResult result = AccountOpeningEngine.run(3, "app-1234", 3000,
                new CreditTerms(2800, "agr-000077"), probe, open);

        assertThat(result.creditAmount()).isEqualTo(2800);
        assertThat(result.creditAmountFallback()).isFalse();
        assertThat(result.agreementId()).isEqualTo("agr-000077");
    }
}
