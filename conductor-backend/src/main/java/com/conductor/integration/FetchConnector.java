package com.conductor.integration;

import java.time.Duration;

/** PULL capability: scheduled/on-demand fetch into the cached data view. PostHog, GCP Billing. */
public interface FetchConnector extends Connector {
    ConnectorData fetchData(ConnectionContext ctx);
    ConnectorHealth checkHealth(ConnectionContext ctx);
    default Duration getMaxCacheAge() { return Duration.ofHours(1); }
}
