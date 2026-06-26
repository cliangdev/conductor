package com.conductor.integration;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** A named operation a FetchConnector can perform, for agent discovery and workflow step authoring. */
public record ToolOperation(
        @JsonProperty("id") String id,
        @JsonProperty("description") String description,
        @JsonProperty("params") Map<String, String> params,
        @JsonProperty("outputShape") String outputShape,
        @JsonProperty("outputKeys") List<String> outputKeys) {}
