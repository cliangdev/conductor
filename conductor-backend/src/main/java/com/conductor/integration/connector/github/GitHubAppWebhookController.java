package com.conductor.integration.connector.github;

import com.conductor.entity.Connection;
import com.conductor.entity.WebhookEvent;
import com.conductor.entity.WebhookEventStatus;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.InboundEvent;
import com.conductor.integration.WebhookVerification;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.WebhookEventRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.WebhookDispatchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;

/**
 * Single app-level GitHub webhook endpoint. A GitHub App delivers ALL events for ALL installations here,
 * so routing is by {@code installation.id} in the payload (not the URL). Verification uses the app's
 * webhook secret. Lifecycle events keep connections in sync; routed events (pull_request, …) reuse the
 * generic webhook_event log + {@link WebhookDispatchService} + retry scheduler. Isolated to the github
 * connector — the generic per-connection receiver is untouched.
 */
@RestController
@RequestMapping("/api/v1")
public class GitHubAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GitHubAppWebhookController.class);
    private static final String CONNECTOR_ID = "github";

    private final GitHubConnector gitHubConnector;
    private final ConnectionRepository connectionRepository;
    private final ConnectionService connectionService;
    private final WebhookEventRepository eventRepository;
    private final WebhookDispatchService dispatchService;
    private final ObjectMapper objectMapper;

    public GitHubAppWebhookController(GitHubConnector gitHubConnector,
                                      ConnectionRepository connectionRepository,
                                      ConnectionService connectionService,
                                      WebhookEventRepository eventRepository,
                                      WebhookDispatchService dispatchService,
                                      ObjectMapper objectMapper) {
        this.gitHubConnector = gitHubConnector;
        this.connectionRepository = connectionRepository;
        this.connectionService = connectionService;
        this.eventRepository = eventRepository;
        this.dispatchService = dispatchService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/webhooks/github")
    public ResponseEntity<Void> receive(HttpServletRequest request) {
        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
        HttpHeaders headers = copyHeaders(request);

        // Verify against the app-level webhook secret (the connector ignores the per-connection ctx here).
        ConnectionContext verifyCtx = new ConnectionContext(null, CONNECTOR_ID, null, null, null, null, Map.of(), null);
        WebhookVerification verification = gitHubConnector.verify(rawBody, headers, verifyCtx);
        if (!verification.valid()) {
            return ResponseEntity.status(401).build();
        }

        String eventType = gitHubConnector.extractEventType(headers, rawBody);
        if ("ping".equals(eventType)) {
            return ResponseEntity.ok().build();
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception e) {
            return ResponseEntity.ok().build(); // accept-and-ignore unparseable bodies
        }
        String installationId = root.path("installation").path("id").asText(null);
        String action = root.path("action").asText("");

        // Lifecycle: keep connections in sync. (Repo add/remove needs no sync — repos are listed live.)
        if ("installation".equals(eventType)) {
            if ("deleted".equals(action) && installationId != null) {
                connectionRepository.findByConnectorIdAndConfigValue(CONNECTOR_ID, "installationId", installationId)
                        .forEach(c -> connectionService.delete(c.getId()));
            }
            return ResponseEntity.ok().build();
        }
        if ("installation_repositories".equals(eventType)) {
            return ResponseEntity.ok().build();
        }

        if (installationId == null) {
            return ResponseEntity.ok().build();
        }

        String deliveryId = gitHubConnector.extractDeliveryId(headers, rawBody);
        if (deliveryId != null && eventRepository.findByDeliveryId(deliveryId).isPresent()) {
            return ResponseEntity.ok().build(); // idempotent replay
        }

        List<Connection> connections = connectionRepository
                .findByConnectorIdAndConfigValue(CONNECTOR_ID, "installationId", installationId);
        if (connections.isEmpty()) {
            return ResponseEntity.ok().build(); // no project connected this installation
        }

        String payload = new String(rawBody, StandardCharsets.UTF_8);

        // Primary connection: log + dispatch through the generic machinery (gets retry/dead-letter).
        Connection primary = connections.get(0);
        WebhookEvent event = new WebhookEvent();
        event.setConnectorId(CONNECTOR_ID);
        event.setConnectionId(primary.getId());
        event.setDeliveryId(deliveryId);
        event.setEventType(eventType);
        event.setPayload(payload);
        event.setStatus(WebhookEventStatus.PENDING);
        eventRepository.save(event);
        dispatchService.dispatch(event);

        // Additional connections sharing this installation (same GitHub account connected from multiple
        // projects) — best-effort direct dispatch; handleEvent's project check scopes the update.
        for (Connection extra : connections.subList(1, connections.size())) {
            try {
                gitHubConnector.handleEvent(
                        new InboundEvent(deliveryId, eventType, payload, Map.of()),
                        connectionService.toContext(extra));
            } catch (Exception e) {
                log.warn("GitHub webhook delivery {} failed for additional connection {}: {}",
                        deliveryId, extra.getId(), e.getMessage());
            }
        }

        return ResponseEntity.ok().build();
    }

    private HttpHeaders copyHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                headers.add(name, request.getHeader(name));
            }
        }
        return headers;
    }
}
