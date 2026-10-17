package com.conductor.integration;

import java.time.Duration;
import java.util.Optional;

/**
 * PULL capability: scheduled/on-demand fetch into the cached data view. PostHog, GCP Billing.
 * {@code getToolSpec()} (classpath tool-spec JSON loading) is inherited from {@link Connector} —
 * action-only connectors get the same lookup without duplicating it.
 */
public interface FetchConnector extends Connector {
    ConnectorData fetchData(ConnectionContext ctx);
    ConnectorHealth checkHealth(ConnectionContext ctx);

    /**
     * Opt-in credential check run by the generic connect flow BEFORE a connection is saved. The context is
     * transient (built from the request; its connection id is a throwaway). Return empty when the credentials
     * work, or a plain, human-readable message when they do not: the connect form then refuses with that
     * message as a 400 and nothing is persisted. The default accepts everything, so connectors that do not
     * override it keep today's behaviour.
     */
    default Optional<String> verifyCredentials(ConnectionContext ctx) { return Optional.empty(); }

    /**
     * What a connection is ABOUT (e.g. the app id, the metrics URL), normalized, or empty when a workspace may
     * connect the same thing more than once. The connect and edit paths refuse a second connection in the same
     * workspace with the same identity, since both would feed the same numbers and double every total.
     */
    default Optional<String> identityKey(ConnectionContext ctx) { return Optional.empty(); }

    default Duration getMaxCacheAge() { return Duration.ofHours(1); }
}
