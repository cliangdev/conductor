package com.conductor.integration;

import java.util.Map;

/** A named operation a FetchConnector can perform, for agent discovery and workflow step authoring. */
public record ToolOperation(String id, String description, Map<String, String> params, String outputShape) {}
