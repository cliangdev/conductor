package com.conductor.integration;

import java.time.Duration;

/**
 * PULL capability: scheduled/on-demand fetch into the cached data view. PostHog, GCP Billing.
 * {@code getToolSpec()} (classpath tool-spec JSON loading) is inherited from {@link Connector} —
 * action-only connectors get the same lookup without duplicating it.
 */
public interface FetchConnector extends Connector {
    ConnectorData fetchData(ConnectionContext ctx);
    ConnectorHealth checkHealth(ConnectionContext ctx);
    default Duration getMaxCacheAge() { return Duration.ofHours(1); }
}
