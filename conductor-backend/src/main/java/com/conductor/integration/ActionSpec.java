package com.conductor.integration;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;
import java.util.List;

/** A named action an {@link ActionConnector} exposes, for agent discovery and workflow step authoring. */
public record ActionSpec(
        @JsonProperty("id") String id,
        @JsonProperty("description") String description,
        @JsonProperty("params") Map<String, String> params,
        @JsonProperty("outputKeys") List<String> outputKeys) {}
