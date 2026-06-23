package com.conductor.integration;

import java.util.List;
import java.util.Map;

/**
 * OUTBOUND capability: a connector that performs actions/notifications (Slack/Discord post,
 * "create issue in Linear"). Defined now as a forward-looking seam so future action connectors
 * don't reshape the SPI; the invocation/queue engine is intentionally deferred.
 */
public interface ActionConnector extends Connector {
    List<ActionDescriptor> getActions();
    ActionResult invoke(String actionId, Map<String, Object> input, ConnectionContext ctx);
}
