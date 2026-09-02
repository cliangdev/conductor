package com.conductor.controller;

import com.conductor.entity.Connection;
import com.conductor.entity.WebhookEvent;
import com.conductor.entity.WebhookEventStatus;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.WebhookConnector;
import com.conductor.integration.WebhookRouting;
import com.conductor.integration.WebhookVerification;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.WebhookEventRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.WebhookDispatchService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Generic inbound webhook receiver for ALL connectors. Two URL forms, both handled here:
 * <ul>
 *   <li>{@code POST /api/v1/webhooks/{connectorId}/{connectionId}} — per-connection: the URL selects the
 *       instance, verification uses the per-connection secret.</li>
 *   <li>{@code POST /api/v1/webhooks/{connectorId}} — app-level: one shared endpoint + secret (e.g. a
 *       GitHub App). The connector's {@link WebhookConnector#route} resolves the target connection(s) from
 *       the payload; one delivery may fan out to N connections across projects.</li>
 * </ul>
 *
 * <p>The framework owns receive / route / verify-orchestration / dedup / log / dispatch; the connector owns
 * signature scheme + delivery-id/event-type extraction + routing + lifecycle + domain mapping.
 * Accept-then-process: persist a PENDING {@link WebhookEvent} per target connection, dispatch synchronously,
 * always return 200 (so the sender does not retry; our own scheduler retries failures).
 *
 * <p><b>Why hand-rolled (not the generated {@code WebhooksApi} interface):</b> HMAC verification requires
 * the EXACT raw request bytes and arbitrary provider headers (X-Hub-Signature-256, X-GitHub-Event,
 * X-GitHub-Delivery). The generated interface would bind the body as a parsed/normalized type and would not
 * surface arbitrary headers, breaking byte-exact HMAC. We therefore read the raw body from
 * {@link HttpServletRequest} directly. Both URL forms are documented in {@code openapi.yaml}.
 */
@RestController
public class WebhookReceiverController {

    private static final Logger log = LoggerFactory.getLogger(WebhookReceiverController.class);

    private final ConnectorRegistry connectorRegistry;
    private final ConnectionService connectionService;
    private final ConnectionRepository connectionRepository;
    private final WebhookEventRepository eventRepository;
    private final WebhookDispatchService dispatchService;

    public WebhookReceiverController(ConnectorRegistry connectorRegistry,
                                     ConnectionService connectionService,
                                     ConnectionRepository connectionRepository,
                                     WebhookEventRepository eventRepository,
                                     WebhookDispatchService dispatchService) {
        this.connectorRegistry = connectorRegistry;
        this.connectionService = connectionService;
        this.connectionRepository = connectionRepository;
        this.eventRepository = eventRepository;
        this.dispatchService = dispatchService;
    }

    /** App-level form: no connectionId in the URL — the connector routes by payload. */
    @PostMapping("/webhooks/{connectorId}")
    public ResponseEntity<String> receiveAppLevel(@PathVariable String connectorId,
                                                  HttpServletRequest request) {
        return receive(connectorId, null, request);
    }

    /** Per-connection form: the URL selects the instance. */
    @PostMapping("/webhooks/{connectorId}/{connectionId}")
    public ResponseEntity<String> receiveWebhook(@PathVariable String connectorId,
                                                 @PathVariable String connectionId,
                                                 HttpServletRequest request) {
        return receive(connectorId, connectionId, request);
    }

    private ResponseEntity<String> receive(String connectorId, String urlConnectionId, HttpServletRequest request) {
        // Raw bytes are mandatory for correct HMAC verification — never parse the body first.
        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.warn("Failed to read webhook body for {}: {}", connectorId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        Optional<WebhookConnector> connectorOpt = connectorRegistry.findWebhook(connectorId);
        if (connectorOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        WebhookConnector connector = connectorOpt.get();
        HttpHeaders headers = extractHeaders(request);

        // Resolve target connection(s). App-level connectors route by payload (fan-out, N across projects);
        // per-connection connectors use the single connection from the URL.
        WebhookRouting routing = connector.route(rawBody, headers);
        List<Connection> targets;
        ConnectionContext verifyCtx;
        if (routing != null) {
            // App-level: verify with the connector's own app secret (per-connection ctx is ignored).
            verifyCtx = new ConnectionContext(null, connectorId, null, null, null, null, java.util.Map.of(), null);
        } else if (urlConnectionId != null) {
            Optional<Connection> connectionOpt = connectionService.getById(urlConnectionId, connectorId);
            if (connectionOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            verifyCtx = connectionService.toContext(connectionOpt.get());
        } else {
            // App-level URL form but the connector did not route (e.g. ping / unparseable / no installation):
            // still verify, then let lifecycle decide, then accept-and-ignore.
            verifyCtx = new ConnectionContext(null, connectorId, null, null, null, null, java.util.Map.of(), null);
        }

        WebhookVerification verification = connector.verify(rawBody, headers, verifyCtx);
        if (!verification.valid()) {
            log.warn("Invalid webhook signature for {}: {}", connectorId, verification.reason());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Provider-specific synchronous ack (Discord PONG/deferred-ack, Slack url_verification, ...). See
        // WebhookConnector#synchronousResponse's javadoc for the full consumed=true/false contract.
        // consumed=true short-circuits here, before dedup/persist/dispatch ever run. consumed=false (or
        // absent) falls through to the normal pipeline below, which decides its own 200 at every return
        // point via okWithBody so the sync body (if any) still reaches the provider.
        Optional<WebhookConnector.WebhookSyncResponse> syncResponse =
                connector.synchronousResponse(rawBody, headers, verifyCtx);
        if (syncResponse.isPresent() && syncResponse.get().consumed()) {
            return jsonResponse(syncResponse.get().jsonBody());
        }

        String eventType = connector.extractEventType(headers, rawBody);

        // App-level lifecycle hook (install/uninstall, etc.). If fully consumed, skip routing + dispatch.
        if (urlConnectionId == null && connector.handleLifecycle(rawBody, headers, eventType)) {
            return okWithBody(syncResponse);
        }

        if (routing != null) {
            targets = connectionRepository.findByConnectorIdAndConfigValue(
                    connectorId, routing.configKey(), routing.configValue());
        } else if (urlConnectionId != null) {
            // Already validated above; re-read for the dispatch loop.
            targets = connectionService.getById(urlConnectionId, connectorId).map(List::of).orElse(List.of());
        } else {
            // App-level, no routing, not consumed by lifecycle → nothing to do (accept-and-ignore).
            return okWithBody(syncResponse);
        }
        if (targets.isEmpty()) {
            return okWithBody(syncResponse); // no connection matched this delivery
        }

        String deliveryId = connector.extractDeliveryId(headers, rawBody);
        String payload = new String(rawBody, StandardCharsets.UTF_8);

        // Durable per-connection fan-out: every target gets its own PENDING row + dispatch (retry/dead-letter).
        // Dedup is scoped to (connectionId, deliveryId): one delivery legitimately yields N rows (one per
        // connection), so a per-connection replay check is the correct idempotency key.
        for (Connection target : targets) {
            if (deliveryId != null
                    && eventRepository.findByConnectionIdAndDeliveryId(target.getId(), deliveryId).isPresent()) {
                continue; // idempotent replay for this connection
            }
            WebhookEvent event = new WebhookEvent();
            event.setConnectorId(connectorId);
            event.setConnectionId(target.getId());
            event.setDeliveryId(deliveryId);
            event.setEventType(eventType);
            event.setPayload(payload);
            event.setStatus(WebhookEventStatus.PENDING);
            WebhookEvent saved = eventRepository.save(event);
            dispatchService.dispatch(saved);
        }

        return okWithBody(syncResponse);
    }

    /** Every 200-return point in {@link #receive} funnels through here: an empty body, unless a {@code
     *  consumed=false} {@link WebhookConnector.WebhookSyncResponse} is in play, in which case its {@code
     *  jsonBody} is attached regardless of which branch the pipeline took underneath -- the provider
     *  still needs its synchronous acknowledgment body no matter how routing/dedup resolved. (A {@code
     *  consumed=true} response never reaches here -- it returns immediately where it's detected, above.) */
    private ResponseEntity<String> okWithBody(Optional<WebhookConnector.WebhookSyncResponse> syncResponse) {
        return syncResponse.map(r -> jsonResponse(r.jsonBody())).orElseGet(() -> ResponseEntity.ok().build());
    }

    private ResponseEntity<String> jsonResponse(String jsonBody) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(jsonBody);
    }

    private HttpHeaders extractHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        var names = request.getHeaderNames();
        if (names != null) {
            for (String name : Collections.list(names)) {
                headers.add(name, request.getHeader(name));
            }
        }
        return headers;
    }
}
