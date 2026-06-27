package com.conductor.agent.tool;

/**
 * Ambient context passed to {@link AgentTool#invoke}. Carries the project scope (for resolving
 * connections/secrets) and the owning agent run (for attribution/observability). Kept minimal so
 * tools stay decoupled from the runner internals.
 */
public record ToolInvocationContext(String projectId, String agentId, String agentRunId) {}
