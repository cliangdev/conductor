package com.conductor.integration;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Describes a connected integration as a workflow data-source tool. Stored as tool_metadata JSON on the connection row. */
public record IntegrationToolSpec(
        @JsonProperty("description") String description,
        @JsonProperty("operations") List<ToolOperation> operations) {}
