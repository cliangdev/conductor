package com.conductor.integration;

import java.time.Duration;
import java.util.List;

/** PULL capability: scheduled/on-demand fetch into the cached data view. PostHog, GCP Billing. */
public interface FetchConnector extends Connector {
    ConnectorData fetchData(ConnectionContext ctx);
    ConnectorHealth checkHealth(ConnectionContext ctx);
    default Duration getMaxCacheAge() { return Duration.ofHours(1); }

    /** Describes this connector as a workflow tool for agent discovery. Override to provide specific operations. */
    default IntegrationToolSpec getToolSpec() {
        return new IntegrationToolSpec(getMetadata().description(), List.of());
    }
}
