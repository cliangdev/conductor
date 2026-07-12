package com.conductor.integration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;

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

    ObjectMapper TOOL_SPEC_MAPPER = new ObjectMapper();

    /**
     * Describes this connector as a workflow tool for agent discovery (operations for
     * {@link FetchConnector}s, actions for {@link ActionConnector}s — either, both, or neither
     * populated depending on which capabilities this connector implements).
     * Loads from {@code /connectors/tool-specs/{connectorId}.json} on the classpath.
     * Add a new JSON file there to register tool metadata — no Java change needed.
     */
    default IntegrationToolSpec getToolSpec() {
        String id = getMetadata().id();
        String path = "/connectors/tool-specs/" + id + ".json";
        try (InputStream is = Connector.class.getResourceAsStream(path)) {
            if (is == null) return new IntegrationToolSpec(getMetadata().description(), List.of(), List.of());
            return TOOL_SPEC_MAPPER.readValue(is, IntegrationToolSpec.class);
        } catch (Exception e) {
            return new IntegrationToolSpec(getMetadata().description(), List.of(), List.of());
        }
    }
}
