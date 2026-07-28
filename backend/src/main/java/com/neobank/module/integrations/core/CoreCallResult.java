package com.neobank.module.integrations.core;

import com.neobank.module.model.CoreAttemptResult;

/**
 * The I/O-shaped result of one real HTTP call to the mock core — {@link CoreAttemptResult} plus
 * whatever the wire actually returned (an accountId on a hit/create, latency always measured
 * here). Kept separate from {@link com.neobank.module.service.AccountOpeningEngine.CoreCallOutcome}
 * on purpose: this record knows about HTTP, the engine's own record only knows about the decision
 * — {@code AccountOpeningService} is the translation point between the two.
 */
public record CoreCallResult(CoreAttemptResult result, String accountId, long latencyMs) {
}
