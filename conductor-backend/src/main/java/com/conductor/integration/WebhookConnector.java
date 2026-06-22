package com.conductor.integration;

import org.springframework.http.HttpHeaders;

/**
 * PUSH capability: inbound webhook. The framework owns receive/route/log/dedup/retry; the connector
 * owns verify (signature scheme), delivery-id + event-type extraction, and the event → domain mapping.
 */
public interface WebhookConnector extends Connector {

    /** Verify raw bytes against this connection's secret. Per-connector scheme (GitHub HMAC-SHA256, ...). */
    WebhookVerification verify(byte[] rawBody, HttpHeaders headers, ConnectionContext ctx);

    /** Stable provider delivery id for idempotency (GitHub X-GitHub-Delivery, Stripe event id). */
    String extractDeliveryId(HttpHeaders headers, byte[] rawBody);

    /** Provider event type for the log / dispatch (GitHub X-GitHub-Event, Stripe event.type). */
    String extractEventType(HttpHeaders headers, byte[] rawBody);

    /** Map a verified, deduped inbound event to a domain action. Runs inside the generic retry engine. */
    void handleEvent(InboundEvent event, ConnectionContext ctx);
}
