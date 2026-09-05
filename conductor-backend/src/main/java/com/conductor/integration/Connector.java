package com.conductor.integration;

import com.conductor.entity.Connection;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
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
    Logger TOOL_SPEC_LOG = LoggerFactory.getLogger(Connector.class);

    /**
     * Describes this connector as a workflow tool for agent discovery (operations for
     * {@link FetchConnector}s, actions for {@link ActionConnector}s, ingest feeds for
     * {@link IngestSpec} — any subset populated depending on which capabilities this connector
     * implements). Loads from {@code /connectors/tool-specs/{connectorId}.json} on the classpath.
     * Add a new JSON file there to register tool metadata — no Java change needed.
     *
     * <p>A declared {@code mode: WINDOW} ingest entry requires this connector to implement
     * {@link IngestConnector} (only it has window semantics — see {@link IngestMode}). That's a
     * load-time failure, not a silent degrade to {@code SNAPSHOT}: a silently narrowed window would
     * produce a digest over the wrong period, which is worse than no digest at all. The offending entry
     * is dropped and logged at WARN; every other entry (and the rest of the spec) still loads normally.
     */
    default IntegrationToolSpec getToolSpec() {
        String id = getMetadata().id();
        String path = "/connectors/tool-specs/" + id + ".json";
        try (InputStream is = Connector.class.getResourceAsStream(path)) {
            if (is == null) return new IntegrationToolSpec(getMetadata().description(), List.of(), List.of());
            IntegrationToolSpec spec = TOOL_SPEC_MAPPER.readValue(is, IntegrationToolSpec.class);
            return withValidIngest(spec, id);
        } catch (Exception e) {
            // Malformed tool-spec JSON silently degrading to empty metadata is easy to miss — name the
            // connector and the parse error so a broken tool-spec.json actually gets noticed.
            TOOL_SPEC_LOG.warn("Failed to load tool-spec JSON for connector '{}' at {}: {}", id, path, e.getMessage());
            return new IntegrationToolSpec(getMetadata().description(), List.of(), List.of());
        }
    }

    /**
     * Called once, synchronously, right after a connection is created AND its credentials/config are
     * fully stored -- the generic connection-creation flow ({@code IntegrationController#createConnection})
     * calls this for EVERY connector, regardless of which capability interfaces it implements. The
     * default no-op means most connectors need do nothing here; override it for a setup step that must
     * succeed up front rather than fail silently at first use later -- e.g. registering a vendor-side
     * command or webhook subscription ({@code DiscordAppConnector} registering its {@code /ask} slash
     * command).
     *
     * <p>Throwing here fails the connection creation itself, with the exception's message surfaced to
     * the caller -- the framework deletes the just-created connection row so a failed setup step never
     * leaves a connection that looks connected but silently can't work. A connector doing something here
     * that can be safely retried later should prefer NOT throwing (log and continue) unless success is
     * genuinely required before the connection is usable at all.
     */
    default void onConnectionCreated(Connection connection, ConnectionContext ctx) {
    }

    private IntegrationToolSpec withValidIngest(IntegrationToolSpec spec, String connectorId) {
        if (spec.ingest().isEmpty() || this instanceof IngestConnector) return spec;
        List<IngestSpec> valid = new ArrayList<>();
        for (IngestSpec entry : spec.ingest()) {
            if (entry.mode() == IngestMode.WINDOW) {
                TOOL_SPEC_LOG.warn("Dropping ingest '{}' for connector '{}': mode WINDOW requires an "
                        + "IngestConnector, but this connector only implements FetchConnector",
                        entry.id(), connectorId);
            } else {
                valid.add(entry);
            }
        }
        return new IntegrationToolSpec(spec.description(), spec.operations(), spec.actions(), valid);
    }
}
