package com.conductor.integration;

import org.springframework.http.HttpHeaders;

import java.util.Optional;

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

    /**
     * A provider-specific body the receiver must return synchronously, within THIS request, instead of
     * (or in addition to) the generic empty {@code 200}. Called after {@link #verify} succeeds, before
     * dedup/persist — e.g. Discord's interaction PONG / deferred-ack acknowledgment, or Slack's {@code
     * url_verification} challenge echo, both of which the provider expects back within its own request
     * timeout, not after an async dispatch.
     *
     * <p>{@link WebhookSyncResponse#consumed()} controls what happens next:
     * <ul>
     *   <li>{@code true} — the receiver returns {@code jsonBody} ({@code application/json}, 200) and
     *       skips dedup/persist/dispatch entirely. The event never reaches {@link #handleEvent}.</li>
     *   <li>{@code false} — the receiver still returns {@code jsonBody}, but the normal dedup/persist/
     *       dispatch pipeline still runs underneath (synchronously, in this same request, exactly as it
     *       does for every other connector) before the response is sent. A connector using this to defer
     *       a slow domain action (e.g. Discord's "thinking…" ack while an agent answers) MUST make {@link
     *       #handleEvent} fast/enqueue-only on this path — the provider's own ack timeout (Discord: 3s)
     *       is already spent on the synchronous body, so {@code handleEvent} cannot itself do the slow
     *       work; it must hand off (e.g. submit to a bounded executor) and return.</li>
     * </ul>
     *
     * <p>Empty (the default) — today's behavior, byte-for-byte: the receiver runs the normal pipeline and
     * returns an empty {@code 200} at the end, exactly as if this method didn't exist.
     */
    default Optional<WebhookSyncResponse> synchronousResponse(byte[] rawBody, HttpHeaders headers, ConnectionContext ctx) {
        return Optional.empty();
    }

    /**
     * Map a verified, deduped inbound event to a domain action. {@code WebhookDispatchService#dispatch}
     * calls this SYNCHRONOUSLY, inline in the receiving request, for every connector today -- a failure
     * marks the persisted {@code WebhookEvent} FAILED for a later scheduled retry sweep, but the first
     * attempt always blocks the original request. That's ordinarily invisible to the provider (the
     * receiver's own response is a fire-and-forget empty {@code 200}), but on a path following {@link
     * #synchronousResponse} with {@code consumed=false} the receiver's response body IS the provider's
     * synchronous acknowledgment, sent only after this method returns -- so a slow {@code handleEvent}
     * here delays that acknowledgment too, past the provider's own ack budget. See {@link
     * #synchronousResponse}'s javadoc for why that constrains this method to be fast/enqueue-only on
     * that path.
     */
    void handleEvent(InboundEvent event, ConnectionContext ctx);

    /** One provider-specific synchronous acknowledgment -- see {@link #synchronousResponse}. */
    record WebhookSyncResponse(String jsonBody, boolean consumed) {}
}
