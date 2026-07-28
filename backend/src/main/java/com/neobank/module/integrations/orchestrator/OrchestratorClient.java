package com.neobank.module.integrations.orchestrator;

import com.neobank.module.model.Decision;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The outbound half of the contract: telling the orchestrator what this module decided.
 *
 * <p>{@code ApplicationController} is the way in, this is the way out. Everything in the
 * {@code orchestrator} package is the wire; everything else in the module is local.</p>
 */
@Component
public class OrchestratorClient {

    private static final Logger log = LoggerFactory.getLogger(OrchestratorClient.class);

    private final RestClient http;
    private final String serviceId;
    private final String applicationsUrl;

    public OrchestratorClient(RestClient http,
                              @Value("${service.id:neo07}") String serviceId,
                              @Value("${service.orchestrator-url:http://localhost:9000}") String orchestratorUrl) {
        this.http = http;
        this.serviceId = serviceId;
        this.applicationsUrl = orchestratorUrl + "/api/v1/applications";
    }

    /**
     * Report the outcome: {@code PUT /api/v1/applications/{applicationId}}.
     *
     * <p>A {@code PUT} on the application, not a post to a mailbox — this is an update to the
     * status of something the orchestrator already has, which is why the id is in the URL and not
     * in the body.</p>
     *
     * <p><b>Failures are logged, never thrown.</b> The decision is already committed to our own
     * database, so re-throwing would roll nothing back and would only kill the worker thread. If
     * the orchestrator cannot be reached it treats the step as timed out — that is its job, not
     * ours.</p>
     */
    public void applicationStatusUpdate(String applicationId, Decision status, String comment) {
        ApplicationStatusUpdate body = new ApplicationStatusUpdate(serviceId, status.name(), comment);
        try {
            http.put()
                    .uri(applicationsUrl + "/" + applicationId)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("REPORTED {} -> {}", applicationId, status);
        } catch (Exception e) {
            log.warn("Status update to the orchestrator failed for {}: {} — its timeout sweeper "
                    + "will notice", applicationId, e.toString());
        }
    }

    /**
     * UC-01 name search's remote half: {@code GET /api/v1/applications?name=} — the v5 contract
     * addition that lets a name resolve to application ids without this module ever storing one.
     *
     * <p>Expected shape is a JSON array of objects carrying at least {@code applicationId} — the
     * same envelope shape the orchestrator already uses elsewhere. <b>Never throws.</b> A search is
     * a read the operator can just retry, so any failure (orchestrator down, unexpected shape)
     * degrades to "no name matches" rather than failing the whole board (AC6).</p>
     */
    public List<String> searchApplicationIdsByName(String name) {
        try {
            List<Map<String, Object>> matches = http.get()
                    .uri(applicationsUrl + "?name={name}", name)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<Map<String, Object>>>() { });
            if (matches == null) {
                return List.of();
            }
            return matches.stream()
                    .map(m -> m.get("applicationId"))
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .toList();
        } catch (Exception e) {
            log.warn("Name search via the orchestrator failed for '{}': {}", name, e.toString());
            return List.of();
        }
    }

    /**
     * UC-03's proxy, reused by UC-01 to hydrate the board's applicant-name column live: {@code GET
     * /api/v1/applications/{applicationId}}. Nothing from the response is ever persisted.
     *
     * <p>Empty on any failure — the caller renders a retryable placeholder rather than a 500
     * (AC6).</p>
     */
    public Optional<Map<String, Object>> fetchApplication(String applicationId) {
        try {
            Map<String, Object> body = http.get()
                    .uri(applicationsUrl + "/{id}", applicationId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() { });
            return Optional.ofNullable(body);
        } catch (Exception e) {
            log.warn("Applicant fetch via the orchestrator failed for {}: {}", applicationId,
                    e.toString());
            return Optional.empty();
        }
    }
}
