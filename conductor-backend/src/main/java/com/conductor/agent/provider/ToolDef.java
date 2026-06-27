package com.conductor.agent.provider;

import java.util.Map;

/**
 * A tool advertised to the model: a name, a description, and a JSON Schema object describing the
 * input. {@code inputSchema} is a parsed JSON Schema (a {@code Map} with {@code "type"},
 * {@code "properties"}, {@code "required"}); each provider renders it into its own tool format.
 */
public record ToolDef(String name, String description, Map<String, Object> inputSchema) {}
