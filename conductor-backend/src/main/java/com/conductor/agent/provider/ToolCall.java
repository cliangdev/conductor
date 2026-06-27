package com.conductor.agent.provider;

/**
 * A model's request to invoke a tool, normalized across providers. {@code argumentsJson} is the raw
 * JSON object the model produced for the tool's input schema; the runner parses it before dispatch.
 */
public record ToolCall(String id, String name, String argumentsJson) {}
