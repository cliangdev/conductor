package com.conductor.integration;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Describes a connected integration as a workflow tool. Stored as tool_metadata JSON on the
 * connection row. {@code operations} come from {@link FetchConnector} (pull); {@code actions} come
 * from {@link ActionConnector} (outbound) — a connector implementing both capabilities populates
 * both lists. Jackson-tolerant of older tool-spec JSON files that predate {@code actions}: the
 * compact constructor defaults either list to empty rather than requiring the field.
 */
public record IntegrationToolSpec(
        @JsonProperty("description") String description,
        @JsonProperty("operations") List<ToolOperation> operations,
        @JsonProperty("actions") List<ActionSpec> actions) {

    public IntegrationToolSpec {
        if (operations == null) operations = List.of();
        if (actions == null) actions = List.of();
    }

    public IntegrationToolSpec(String description, List<ToolOperation> operations) {
        this(description, operations, List.of());
    }
}
