package com.conductor.integration;

import java.util.List;
import java.util.Map;

/**
 * OUTBOUND capability: a connector that performs actions/notifications (Slack/Discord post,
 * "create issue in Linear"). Invoked through {@code ActionInvocationService}, which owns
 * idempotency, bounded-timeout execution, and retry/dead-lettering.
 *
 * <p><b>Transient vs. permanent vs. ambiguous failure contract</b> — {@link #invoke} communicates
 * failure two ways, and the caller (plus its own timeout handling) treats the three cases very
 * differently:
 * <ul>
 *   <li><b>Thrown exception</b> (network error, 5xx) = TRANSIENT. The caller retries
 *       (bounded inline attempts, then a background sweep) up to a max-attempts cutoff before
 *       dead-lettering.</li>
 *   <li><b>{@link ActionResult#error}</b> returned normally = PERMANENT (e.g. a 4xx rejection —
 *       bad webhook URL, malformed input). The caller does NOT retry; it dead-letters immediately.
 *       Retrying a request the provider has already rejected as invalid wastes attempts and can
 *       duplicate side effects on providers that partially processed the request before rejecting.</li>
 *   <li><b>{@link #invoke} not returning within the caller's invocation deadline</b> (a timeout) =
 *       TERMINAL-AMBIGUOUS, not TRANSIENT — a client-side timeout doesn't mean the request failed
 *       server-side (e.g. a webhook POST can time out on the client while still landing). The
 *       caller cancels the abandoned call and dead-letters immediately rather than retrying, to
 *       avoid duplicating a side effect that may have already succeeded.</li>
 * </ul>
 * Implementations must classify failures accordingly rather than defaulting everything to one path.
 */
public interface ActionConnector extends Connector {

    /**
     * Actions this connector exposes, for agent discovery and workflow step authoring. Default
     * implementation derives these directly from {@link Connector#getToolSpec()}'s {@code actions}
     * (the {@code /connectors/tool-specs/{connectorId}.json} on the classpath) — the same JSON
     * {@code computeToolMetadata} reads — so a connector normally doesn't need to override this at
     * all; the JSON file is the single source of truth. Override only if a connector's runtime action
     * set can't be expressed as static tool-spec JSON.
     */
    default List<ActionDescriptor> getActions() {
        return getToolSpec().actions().stream()
                .map(spec -> new ActionDescriptor(spec.id(), spec.description(),
                        spec.params() != null ? List.copyOf(spec.params().keySet()) : List.of()))
                .toList();
    }

    ActionResult invoke(String actionId, Map<String, Object> input, ConnectionContext ctx);
}
