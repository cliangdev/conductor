package com.conductor.integration;

import java.util.List;
import java.util.Map;

/**
 * OUTBOUND capability: a connector that performs actions/notifications (Slack/Discord post,
 * "create issue in Linear"). Invoked through {@code ActionInvocationService}, which owns
 * idempotency, bounded-timeout execution, and retry/dead-lettering.
 *
 * <p><b>Transient vs. permanent failure contract</b> — {@link #invoke} communicates failure two
 * ways, and the caller treats them very differently:
 * <ul>
 *   <li><b>Thrown exception</b> (network error, 5xx, timeout) = TRANSIENT. The caller retries
 *       (bounded inline attempts, then a background sweep) up to a max-attempts cutoff before
 *       dead-lettering.</li>
 *   <li><b>{@link ActionResult#error}</b> returned normally = PERMANENT (e.g. a 4xx rejection —
 *       bad webhook URL, malformed input). The caller does NOT retry; it dead-letters immediately.
 *       Retrying a request the provider has already rejected as invalid wastes attempts and can
 *       duplicate side effects on providers that partially processed the request before rejecting.</li>
 * </ul>
 * Implementations must classify failures accordingly rather than defaulting everything to one path.
 */
public interface ActionConnector extends Connector {
    List<ActionDescriptor> getActions();
    ActionResult invoke(String actionId, Map<String, Object> input, ConnectionContext ctx);
}
