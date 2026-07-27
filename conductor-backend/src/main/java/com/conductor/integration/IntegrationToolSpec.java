package com.conductor.integration;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Describes a connected integration as a workflow tool. Stored as tool_metadata JSON on the
 * connection row. {@code operations} come from {@link FetchConnector} (pull); {@code actions} come
 * from {@link ActionConnector} (outbound); {@code ingest} declares scheduled Knowledge Center feeds
 * (see {@link IngestSpec}) — a connector implementing any subset of these capabilities populates only
 * the corresponding lists. Jackson-tolerant of older tool-spec JSON files that predate {@code actions}
 * or {@code ingest}: the compact constructor defaults any of the three lists to empty rather than
 * requiring the field, so all six pre-existing tool-spec JSON files keep parsing unchanged.
 */
public record IntegrationToolSpec(
        @JsonProperty("description") String description,
        @JsonProperty("operations") List<ToolOperation> operations,
        @JsonProperty("actions") List<ActionSpec> actions,
        @JsonProperty("ingest") List<IngestSpec> ingest) {

    public IntegrationToolSpec {
        if (operations == null) operations = List.of();
        if (actions == null) actions = List.of();
        if (ingest == null) ingest = List.of();
    }

    public IntegrationToolSpec(String description, List<ToolOperation> operations) {
        this(description, operations, List.of(), List.of());
    }

    public IntegrationToolSpec(String description, List<ToolOperation> operations, List<ActionSpec> actions) {
        this(description, operations, actions, List.of());
    }
}
