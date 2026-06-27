package com.conductor.agent.tool;

/**
 * Provider-neutral result of an {@link AgentTool} invocation. The ReAct runner serializes
 * {@code payload} back to the model as a tool result regardless of which source produced it.
 * {@code ok=false} surfaces a tool error to the model (e.g. as {@code is_error}) without aborting
 * the run. {@code truncated} flags that {@code payload} was clipped to a size budget.
 */
public record ToolResult(boolean ok, String payload, boolean truncated) {

    public static ToolResult ok(String payload) {
        return new ToolResult(true, payload, false);
    }

    public static ToolResult ok(String payload, boolean truncated) {
        return new ToolResult(true, payload, truncated);
    }

    public static ToolResult error(String message) {
        return new ToolResult(false, message, false);
    }
}
