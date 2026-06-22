package com.conductor.controller;

import com.conductor.entity.Connection;
import com.conductor.entity.WebhookEvent;
import com.conductor.entity.WebhookEventStatus;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.WebhookConnector;
import com.conductor.integration.WebhookVerification;
import com.conductor.repository.WebhookEventRepository;
import com.conductor.service.ConnectionService;
import com.conductor.service.WebhookDispatchService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Optional;

/**
 * Generic inbound webhook receiver. The URL — /api/v1/webhooks/{connectorId}/{connectionId} — is both
 * the router and the instance selector. Framework owns receive/route/verify-orchestration/dedup/log;
 * the connector owns signature scheme + delivery-id extraction + domain mapping. Accept-then-process:
 * persist PENDING, dispatch synchronously, always return 200 (so the sender doesn't retry; our own
 * scheduler retries failures).
 */
@RestController
@RequestMapping("/api/v1")
public class WebhookReceiverController {

    private static final Logger log = LoggerFactory.getLogger(WebhookReceiverController.class);

    private final ConnectorRegistry connectorRegistry;
    private final ConnectionService connectionService;
    private final WebhookEventRepository eventRepository;
    private final WebhookDispatchService dispatchService;

    public WebhookReceiverController(ConnectorRegistry connectorRegistry,
                                     ConnectionService connectionService,
                                     WebhookEventRepository eventRepository,
                                     WebhookDispatchService dispatchService) {
        this.connectorRegistry = connectorRegistry;
        this.connectionService = connectionService;
        this.eventRepository = eventRepository;
        this.dispatchService = dispatchService;
    }

    @PostMapping("/webhooks/{connectorId}/{connectionId}")
    public ResponseEntity<Void> receiveWebhook(@PathVariable String connectorId,
                                               @PathVariable String connectionId,
                                               HttpServletRequest request) {
        // Raw bytes are mandatory for correct HMAC verification — never parse the body first.
        byte[] rawBody;
        try {
            rawBody = request.getInputStream().readAllBytes();
        } catch (Exception e) {
            log.warn("Failed to read webhook body for {}/{}: {}", connectorId, connectionId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        Optional<WebhookConnector> connectorOpt = connectorRegistry.findWebhook(connectorId);
        if (connectorOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        Optional<Connection> connectionOpt = connectionService.getById(connectionId, connectorId);
        if (connectionOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        WebhookConnector connector = connectorOpt.get();
        ConnectionContext ctx = connectionService.toContext(connectionOpt.get());
        HttpHeaders headers = extractHeaders(request);

        WebhookVerification verification = connector.verify(rawBody, headers, ctx);
        if (!verification.valid()) {
            log.warn("Invalid webhook signature for {}/{}: {}", connectorId, connectionId, verification.reason());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String deliveryId = connector.extractDeliveryId(headers, rawBody);
        if (deliveryId != null && eventRepository.findByDeliveryId(deliveryId).isPresent()) {
            return ResponseEntity.ok().build(); // idempotent replay
        }

        WebhookEvent event = new WebhookEvent();
        event.setConnectorId(connectorId);
        event.setConnectionId(connectionId);
        event.setDeliveryId(deliveryId);
        event.setEventType(connector.extractEventType(headers, rawBody));
        event.setPayload(new String(rawBody, StandardCharsets.UTF_8));
        event.setStatus(WebhookEventStatus.PENDING);
        WebhookEvent saved = eventRepository.save(event);

        dispatchService.dispatch(saved);

        return ResponseEntity.ok().build();
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
