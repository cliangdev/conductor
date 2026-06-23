package com.conductor.integration;

import org.springframework.http.HttpHeaders;

/**
 * PUSH capability: inbound webhook. The framework owns receive/route/log/dedup/retry; the connector
 * owns verify (signature scheme), delivery-id + event-type extraction, routing, lifecycle, and the
 * event → domain mapping.
 *
 * <p>The SPI supports BOTH webhook models behind the same generic receiver:
 * <ul>
 *   <li><b>Per-connection</b> ({@code POST /webhooks/{connectorId}/{connectionId}}): the URL selects the
 *       instance; {@code verify} uses the per-connection secret from {@code ctx}; {@link #route} returns
 *       {@code null} (the default).</li>
 *   <li><b>App-level</b> ({@code POST /webhooks/{connectorId}}): one shared endpoint + secret for all
 *       installations (e.g. a GitHub App). {@code verify} uses the connector's own injected app secret and
 *       ignores {@code ctx}; {@link #route} resolves the target connection(s) from the payload; lifecycle
 *       events (install/uninstall) are handled by {@link #handleLifecycle}.</li>
 * </ul>
 */
public interface WebhookConnector extends Connector {

    /**
     * Verify raw bytes against the appropriate secret. Per-connection connectors use the per-connection
     * secret from {@code ctx}; app-level connectors use their own injected app secret and ignore {@code ctx}.
     */
    WebhookVerification verify(byte[] rawBody, HttpHeaders headers, ConnectionContext ctx);

    /** Stable provider delivery id for idempotency (GitHub X-GitHub-Delivery, Stripe event id). */
    String extractDeliveryId(HttpHeaders headers, byte[] rawBody);

    /** Provider event type for the log / dispatch (GitHub X-GitHub-Event, Stripe event.type). */
    String extractEventType(HttpHeaders headers, byte[] rawBody);

    /**
     * Resolve the target connection(s) for this delivery WITHOUT touching persistence.
     *
     * <p>Return a {@link WebhookRouting} config selector (e.g. {@code installationId = "123"}) for app-level
     * connectors where the connection is identified by a payload value; the receiver fans out to every
     * matching connection (possibly across projects). Return {@code null} (the default) for per-connection
     * connectors — the receiver uses the {@code connectionId} from the URL.
     */
    default WebhookRouting route(byte[] rawBody, HttpHeaders headers) {
        return null;
    }

    /**
     * Handle an app-level, non-routed lifecycle event (install/uninstall/repo-add, etc.). The connector may
     * mutate connection state here (it has no access to persistence directly, so connectors needing this run
     * their own injected services — keeping payload specifics inside the connector). Return {@code true} if
     * the event was fully consumed (the receiver then skips routing + fan-out); {@code false} (the default)
     * to let the receiver continue with normal routing + dispatch.
     */
    default boolean handleLifecycle(byte[] rawBody, HttpHeaders headers, String eventType) {
        return false;
    }

    /** Map a verified, deduped inbound event to a domain action. Runs inside the generic retry engine. */
    void handleEvent(InboundEvent event, ConnectionContext ctx);
}
