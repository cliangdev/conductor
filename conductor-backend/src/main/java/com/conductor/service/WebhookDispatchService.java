package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.WebhookEvent;
import com.conductor.entity.WebhookEventStatus;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.InboundEvent;
import com.conductor.integration.WebhookConnector;
import com.conductor.repository.WebhookEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Runs a persisted webhook event through its connector's {@code handleEvent}, updating the event's
 * status/attempt bookkeeping. Called synchronously by the receiver (accept-then-process) and by the
 * retry scheduler.
 */
@Service
public class WebhookDispatchService {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatchService.class);

    private final ConnectorRegistry connectorRegistry;
    private final ConnectionService connectionService;
    private final WebhookEventRepository eventRepository;

    public WebhookDispatchService(ConnectorRegistry connectorRegistry,
                                  ConnectionService connectionService,
                                  WebhookEventRepository eventRepository) {
        this.connectorRegistry = connectorRegistry;
        this.connectionService = connectionService;
        this.eventRepository = eventRepository;
    }

    public void dispatch(WebhookEvent event) {
        event.setAttempts(event.getAttempts() + 1);
        event.setLastAttemptedAt(OffsetDateTime.now());
        try {
            Optional<WebhookConnector> connector = connectorRegistry.findWebhook(event.getConnectorId());
            Optional<Connection> connection = connectionService.getById(event.getConnectionId());
            if (connector.isEmpty() || connection.isEmpty()) {
                throw new IllegalStateException("Connector or connection no longer available");
            }
            ConnectionContext ctx = connectionService.toContext(connection.get());
            InboundEvent inbound = new InboundEvent(
                    event.getDeliveryId(), event.getEventType(), event.getPayload(), Map.of(), event.getTraceId());
            connector.get().handleEvent(inbound, ctx);
            event.setStatus(WebhookEventStatus.PROCESSED);
            event.setErrorMessage(null);
        } catch (Exception e) {
            event.setStatus(WebhookEventStatus.FAILED);
            event.setErrorMessage(e.getMessage());
            log.error("Failed to process webhook event {}: {}", event.getId(), e.getMessage(), e);
        } finally {
            eventRepository.save(event);
        }
    }
}
