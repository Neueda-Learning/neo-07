package com.neobank.module.integrations.core;

import com.neobank.module.model.CoreAttemptResult;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * This module's own client for the mock core (UC-05) — a sibling integration, not a change to
 * {@code integrations/orchestrator}, per AGENTS.md's prescribed shape ("your own integrations go
 * beside it, not in it").
 *
 * <p>Built against the caller-supplied {@code coreBaseUrl}/{@code timeoutMs} from the pinned
 * {@code CoreConfig}, cached per pair since a case pins one config for its whole engine run — the
 * shared {@code RestClient} bean in {@code AppConfig} belongs to {@code OrchestratorClient}, which
 * has its own timeout profile, and is left untouched.</p>
 */
@Component
public class CoreClient {

    private static final Logger log = LoggerFactory.getLogger(CoreClient.class);

    private final Map<String, RestClient> clients = new ConcurrentHashMap<>();

    /** {@code GET /core/card-accounts?reference=}: 200 -> HIT, 404 -> MISS, anything else -> ERROR/TIMEOUT. */
    public CoreCallResult probe(String coreBaseUrl, int timeoutMs, String reference) {
        RestClient http = client(coreBaseUrl, timeoutMs);
        long start = System.nanoTime();
        try {
            CoreAccountResponse body = http.get()
                    .uri("/core/card-accounts?reference={reference}", reference)
                    .retrieve()
                    .body(CoreAccountResponse.class);
            return new CoreCallResult(CoreAttemptResult.HIT, body == null ? null : body.accountId(), elapsedMs(start));
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatusCode.valueOf(404)) {
                return new CoreCallResult(CoreAttemptResult.MISS, null, elapsedMs(start));
            }
            log.warn("core probe for {} failed: {}", reference, e.toString());
            return new CoreCallResult(CoreAttemptResult.ERROR, null, elapsedMs(start));
        } catch (RestClientException e) {
            if (isTimeout(e)) {
                return new CoreCallResult(CoreAttemptResult.TIMEOUT, null, elapsedMs(start));
            }
            log.warn("core probe for {} failed: {}", reference, e.toString());
            return new CoreCallResult(CoreAttemptResult.ERROR, null, elapsedMs(start));
        }
    }

    /** {@code POST /core/card-accounts}: 201 -> CREATED, timeout -> TIMEOUT, anything else -> ERROR. */
    public CoreCallResult open(String coreBaseUrl, int timeoutMs, String reference, String productCode,
            Integer creditAmount) {
        RestClient http = client(coreBaseUrl, timeoutMs);
        long start = System.nanoTime();
        try {
            CoreAccountResponse body = http.post()
                    .uri("/core/card-accounts")
                    .body(new OpenRequest(reference, productCode, creditAmount))
                    .retrieve()
                    .body(CoreAccountResponse.class);
            return new CoreCallResult(CoreAttemptResult.CREATED, body == null ? null : body.accountId(),
                    elapsedMs(start));
        } catch (RestClientException e) {
            if (isTimeout(e)) {
                // The mock's timeout-trap dial commits the account, then goes silent past our own
                // read timeout — this is exactly the TIMEOUT the engine expects to recover from.
                return new CoreCallResult(CoreAttemptResult.TIMEOUT, null, elapsedMs(start));
            }
            log.warn("core open for {} failed: {}", reference, e.toString());
            return new CoreCallResult(CoreAttemptResult.ERROR, null, elapsedMs(start));
        }
    }

    /**
     * A socket read/connect timeout can reach us either as a bare {@code ResourceAccessException}
     * or, when it happens while the status-handler is peeking at the response code (as
     * {@code SimpleClientHttpRequestFactory} does), wrapped one level deeper — so this walks the
     * cause chain rather than matching on the outer exception type.
     */
    private static boolean isTimeout(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private RestClient client(String coreBaseUrl, int timeoutMs) {
        return clients.computeIfAbsent(coreBaseUrl + "|" + timeoutMs, key -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(Duration.ofMillis(timeoutMs));
            factory.setReadTimeout(Duration.ofMillis(timeoutMs));
            return RestClient.builder()
                    .baseUrl(coreBaseUrl)
                    .requestFactory(factory)
                    .build();
        });
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    /** Wire shape for both mock-core endpoints — a private mirror of {@code core.CoreAccountView}. */
    private record CoreAccountResponse(String accountId, String reference, String createdAt) {
    }

    /** Wire shape this client sends — a private mirror of {@code core.OpenCoreAccountRequest}. */
    private record OpenRequest(String reference, String productCode, Integer creditAmount) {
    }
}
