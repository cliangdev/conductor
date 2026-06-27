package com.conductor.agent.tool;

import java.util.Map;

/**
 * The single runtime contract every tool exposes to an agent, regardless of where it comes from
 * (a connector, a user-defined HTTP endpoint, a built-in action, an MCP server). The provider
 * gateway and ReAct loop see only this shape — they are fully decoupled from the tool's origin.
 *
 * <p>{@code id} is namespaced by source, e.g. {@code connector:posthog/web_analytics_summary},
 * {@code http:notion-search}, {@code builtin:create_document}. {@code inputSchema} is a JSON Schema
 * object the model must satisfy; {@link #invoke} receives the model-produced, parsed arguments.
 */
public interface AgentTool {

    /** Namespaced, project-unique tool id ({@code "<sourceId>:<rest>"}). */
    String id();

    /** Short model-facing name (no namespace) used in the provider tool definition. */
    String name();

    /** Model-facing description — prescriptive about when to call it. */
    String description();

    /** JSON Schema object for the tool input. */
    Map<String, Object> inputSchema();

    /** Execute the tool. Must not throw for ordinary failures — return {@link ToolResult#error}. */
    ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context);
}
