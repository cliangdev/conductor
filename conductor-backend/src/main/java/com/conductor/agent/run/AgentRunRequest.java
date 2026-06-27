package com.conductor.agent.run;

import java.util.Map;

/**
 * The agent module's public contract for invoking an agent (what the workflow {@code agent} step and,
 * later, MCP/UI call). {@code task} is the instruction; {@code context} is structured data handed to
 * the agent (e.g. collected GSC/PostHog outputs); {@code outputSchema} (nullable) requests a
 * structured JSON answer matching the given shape.
 */
public record AgentRunRequest(
        String agentId,
        String task,
        Map<String, Object> context,
        Map<String, Object> outputSchema) {

    public AgentRunRequest {
        if (context == null) context = Map.of();
    }
}
