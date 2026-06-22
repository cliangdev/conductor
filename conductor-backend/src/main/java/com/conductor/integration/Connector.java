package com.conductor.integration;

/**
 * Thin base every connector implements — identity + descriptor only. What a connector can DO is
 * expressed by which capability sub-interfaces it also implements: {@link FetchConnector} (pull),
 * {@link WebhookConnector} (push), {@link ActionConnector} (outbound). The type system, not a
 * runtime flag, is the guard — a pull service can't see a webhook-only connector and vice versa.
 */
public interface Connector {
    String getId();
    ConnectorMetadata getMetadata();
    ConnectorSpec getSpec();
}
