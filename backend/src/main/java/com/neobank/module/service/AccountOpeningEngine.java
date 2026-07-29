package com.neobank.module.service;

import com.neobank.module.model.AccountOutcome;
import com.neobank.module.model.AccountReasonCode;
import com.neobank.module.model.CoreAttemptResult;
import java.time.Instant;

/**
 * UC-02's decision core: probe-then-open against the mock core, bounded by a retry budget.
 *
 * <p>Deliberately Spring-free and I/O-free — {@code probe}/{@code open} are injected as
 * functional callers so this class never imports {@code RestClient} or any network type. A unit
 * test drives the whole state machine with hand-written lambdas returning canned outcomes, the
 * same pattern {@link CoreConfigService#validate} uses to stay testable without Spring.</p>
 *
 * <p>Order is law, per the UC-02 brief: one PROBE, then — on a MISS — one OPEN, then — only if
 * that OPEN timed out — one recovery PROBE in the same cycle (the core's timeout trap always
 * commits the account before going silent, so a HIT here means adoption, not a fresh create). A
 * cycle that ends any other way (ERROR, or a recovery-probe MISS) consumes one unit of
 * {@code retryBudget}; exhausting the budget ends the case FAILED/ACC_CORE_UNAVAILABLE.</p>
 */
public final class AccountOpeningEngine {

    private AccountOpeningEngine() {
    }

    /** One measured result from a single core call, fed back into the engine after each I/O step. */
    public record CoreCallOutcome(CoreAttemptResult result, String accountId, long latencyMs) {
    }

    /** What the caller must resolve before invoking the core — no Application/network types leak in. */
    public record CreditTerms(Integer approvedLimit, String agreementId) {
        public static CreditTerms none() {
            return new CreditTerms(null, null);
        }
    }

    /** The final verdict the Spring-wired layer must persist and report. */
    public record EngineResult(
            AccountOutcome outcome,
            AccountReasonCode reasonCode,
            String accountId,
            Integer creditAmount,
            boolean creditAmountFallback,
            String agreementId,
            Instant openedAt) {
    }

    /**
     * One core call, kind fixed by the call site (PROBE vs OPEN). Implementations perform the
     * real HTTP call and must persist a {@code CoreAttempt} row for it before returning or
     * throwing — the engine only decides what to call next, it never records anything itself.
     */
    @FunctionalInterface
    public interface CoreCaller {
        CoreCallOutcome call(String applicationId, int cycleNo) throws CoreCallException;
    }

    /** Wraps a core call that neither hit nor missed cleanly — a timeout or a hard error. */
    public static final class CoreCallException extends RuntimeException {
        private final CoreAttemptResult result;

        public CoreCallException(CoreAttemptResult result, String message) {
            super(message);
            if (result != CoreAttemptResult.TIMEOUT && result != CoreAttemptResult.ERROR) {
                throw new IllegalArgumentException("CoreCallException must carry TIMEOUT or ERROR, not " + result);
            }
            this.result = result;
        }

        public CoreAttemptResult result() {
            return result;
        }
    }

    /**
     * Runs the probe-then-open state machine for one application.
     *
     * @param retryBudget         how many cycles to attempt before giving up (seeded 3, {@link
     *                            com.neobank.module.model.CoreConfig#getRetryBudget()})
     * @param applicationId       the id passed through to every core call
     * @param requestedCreditLimit the applicant's requested limit — used as the fallback credit
     *                            amount when {@code creditTerms} carries no approved limit
     * @param creditTerms         the approved limit/agreement, if any authoritative source
     *                            supplied one; {@link CreditTerms#none()} drives the fallback
     * @param probe               performs {@code GET /core/card-accounts?reference=}
     * @param open                performs {@code POST /core/card-accounts}
     */
    public static EngineResult run(
            int retryBudget,
            String applicationId,
            Integer requestedCreditLimit,
            CreditTerms creditTerms,
            CoreCaller probe,
            CoreCaller open) {

        boolean hasApprovedLimit = creditTerms.approvedLimit() != null;
        Integer creditAmount = hasApprovedLimit ? creditTerms.approvedLimit() : requestedCreditLimit;
        boolean creditAmountFallback = !hasApprovedLimit;
        String agreementId = hasApprovedLimit ? creditTerms.agreementId() : null;

        for (int cycle = 1; cycle <= retryBudget; cycle++) {
            CoreCallOutcome probeOutcome = null;
            try {
                probeOutcome = probe.call(applicationId, cycle);
            } catch (CoreCallException e) {
                // ERROR or TIMEOUT on the probe itself does not prove the core is down for
                // writes too — fall through and still attempt the OPEN call this cycle, exactly
                // like a MISS would. Only an actual probe HIT short-circuits (adopt).
            }
            if (probeOutcome != null && probeOutcome.result() == CoreAttemptResult.HIT) {
                return new EngineResult(AccountOutcome.OPENED, AccountReasonCode.ACC_DUPLICATE_PREVENTED,
                        probeOutcome.accountId(), creditAmount, creditAmountFallback, agreementId, Instant.now());
            }
            // MISS (or a caught probe failure): proceed to open this same cycle.
            try {
                CoreCallOutcome openOutcome = open.call(applicationId, cycle);
                if (openOutcome.result() == CoreAttemptResult.CREATED) {
                    return new EngineResult(AccountOutcome.OPENED, AccountReasonCode.ACC_OPENED,
                            openOutcome.accountId(), creditAmount, creditAmountFallback, agreementId, Instant.now());
                }
                // Any other non-exceptional open result is a failed cycle; continue the loop.
            } catch (CoreCallException e) {
                if (e.result() == CoreAttemptResult.TIMEOUT) {
                    CoreCallOutcome recoveryProbe = null;
                    try {
                        recoveryProbe = probe.call(applicationId, cycle);
                    } catch (CoreCallException recoveryFailure) {
                        // The recovery probe erroring or timing out too does not prove the open
                        // never landed — it just means this cycle cannot confirm either way.
                        // Fall through and treat the cycle as failed, exactly like a MISS would;
                        // never let a second unguarded core call escape run() uncaught (that is
                        // the same bug class as the top-level probe, just on a different call).
                    }
                    if (recoveryProbe != null && recoveryProbe.result() == CoreAttemptResult.HIT) {
                        return new EngineResult(AccountOutcome.OPENED, AccountReasonCode.ACC_DUPLICATE_PREVENTED,
                                recoveryProbe.accountId(), creditAmount, creditAmountFallback, agreementId,
                                Instant.now());
                    }
                    // Recovery probe MISS: a real unrecoverable failure, not just the timeout trap.
                }
                // ERROR, or a MISSed recovery probe: this cycle failed; continue the loop.
            }
        }
        return new EngineResult(AccountOutcome.FAILED, AccountReasonCode.ACC_CORE_UNAVAILABLE,
                null, creditAmount, creditAmountFallback, agreementId, null);
    }
}
