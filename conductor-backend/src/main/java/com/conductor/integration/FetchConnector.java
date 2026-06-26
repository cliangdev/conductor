package com.conductor.integration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;

/** PULL capability: scheduled/on-demand fetch into the cached data view. PostHog, GCP Billing. */
public interface FetchConnector extends Connector {
    ConnectorData fetchData(ConnectionContext ctx);
    ConnectorHealth checkHealth(ConnectionContext ctx);
    default Duration getMaxCacheAge() { return Duration.ofHours(1); }

    ObjectMapper TOOL_SPEC_MAPPER = new ObjectMapper();

    /**
     * Describes this connector as a workflow tool for agent discovery.
     * Loads from {@code /connectors/tool-specs/{connectorId}.json} on the classpath.
     * Add a new JSON file there to register tool metadata — no Java change needed.
     */
    default IntegrationToolSpec getToolSpec() {
        String id = getMetadata().id();
        String path = "/connectors/tool-specs/" + id + ".json";
        try (InputStream is = FetchConnector.class.getResourceAsStream(path)) {
            if (is == null) return new IntegrationToolSpec(getMetadata().description(), List.of());
            return TOOL_SPEC_MAPPER.readValue(is, IntegrationToolSpec.class);
        } catch (Exception e) {
            return new IntegrationToolSpec(getMetadata().description(), List.of());
        }
    }
}
