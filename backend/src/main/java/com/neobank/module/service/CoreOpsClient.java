package com.neobank.module.service;

import com.neobank.module.core.CoreAccountView;
import com.neobank.module.core.OpenCoreAccountRequest;
import com.neobank.module.model.CoreAttemptResult;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * The calling half of the probe-then-open conversation with the core, for this module's own
 * operator-facing use cases: UC-04's manual retry and UC-06's duplicate cross-check. A sibling of
 * {@link com.neobank.module.integrations.core.CoreClient}, which is UC-02's engine's own client —
 * kept separate so the two Spring beans (and their independent {@code RestClient} lifecycles)
 * never collide. {@link com.neobank.module.core.MockCoreController} is the callee — a fake
 * external system this module talks to over real HTTP, exactly the way it would talk to the
 * genuine core.
 *
 * <p>Every call is timed and never throws: a timeout, a connection failure and a 5xx are all
 * turned into a {@link CoreCallOutcome} the caller records as one {@code core_attempt} row. That
 * is the point — "the core did not answer" is evidence, not an exception to propagate.</p>
 */
@Component
public class CoreOpsClient {

    private final RestClient.Builder builder;

    public CoreOpsClient(RestClient.Builder builder) {
        this.builder = builder;
    }

    /** {@code GET /core/card-accounts?reference=} — always probe before an open. */
    public CoreCallOutcome probe(String baseUrl, String applicationId, int timeoutMs) {
        long start = System.nanoTime();
        try {
            CoreAccountView account = client(timeoutMs).get()
                    .uri(baseUrl + "/core/card-accounts?reference={ref}", applicationId)
                    .retrieve()
                    .body(CoreAccountView.class);
            return new CoreCallOutcome(CoreAttemptResult.HIT, elapsedMs(start),
                    account == null ? null : account.accountId());
        } catch (HttpClientErrorException.NotFound e) {
            return new CoreCallOutcome(CoreAttemptResult.MISS, elapsedMs(start), null);
        } catch (ResourceAccessException e) {
            return new CoreCallOutcome(isTimeout(e) ? CoreAttemptResult.TIMEOUT : CoreAttemptResult.ERROR,
                    elapsedMs(start), null);
        } catch (Exception e) {
            return new CoreCallOutcome(CoreAttemptResult.ERROR, elapsedMs(start), null);
        }
    }

    /** {@code POST /core/card-accounts} — deliberately non-idempotent on the mock's side. */
    public CoreCallOutcome open(String baseUrl, String applicationId, String productCode,
                                Integer creditAmount, int timeoutMs) {
        long start = System.nanoTime();
        try {
            CoreAccountView account = client(timeoutMs).post()
                    .uri(baseUrl + "/core/card-accounts")
                    .body(new OpenCoreAccountRequest(applicationId, productCode, creditAmount))
                    .retrieve()
                    .body(CoreAccountView.class);
            return new CoreCallOutcome(CoreAttemptResult.CREATED, elapsedMs(start),
                    account == null ? null : account.accountId());
        } catch (ResourceAccessException e) {
            return new CoreCallOutcome(isTimeout(e) ? CoreAttemptResult.TIMEOUT : CoreAttemptResult.ERROR,
                    elapsedMs(start), null);
        } catch (Exception e) {
            return new CoreCallOutcome(CoreAttemptResult.ERROR, elapsedMs(start), null);
        }
    }

    /**
     * {@code GET /core/admin/accounts} — UC-06's cross-check: every account the core has ever
     * opened, across every reference. Throws on any failure; the caller (UC-06) turns that into
     * "cannot verify — core unreachable" rather than a silently empty report.
     */
    public List<CoreAccountView> listAccounts(String baseUrl) {
        return builder.build().get()
                .uri(baseUrl + "/core/admin/accounts")
                .retrieve()
                .body(new ParameterizedTypeReference<List<CoreAccountView>>() { });
    }

    private RestClient client(int timeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(timeoutMs);
        factory.setReadTimeout(timeoutMs);
        return builder.clone().requestFactory(factory).build();
    }

    private static boolean isTimeout(ResourceAccessException e) {
        return e.getCause() instanceof SocketTimeoutException;
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    /** One core call's outcome — what becomes one {@code core_attempt} row. */
    public record CoreCallOutcome(CoreAttemptResult result, long latencyMs, String accountId) {
    }
}
