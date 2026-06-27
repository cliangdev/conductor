package com.conductor.agent.run;

import com.conductor.agent.provider.TokenUsage;

import java.util.Map;

/**
 * The result of an {@link AgentExecutionService} run. {@code outputText} is the agent's final answer;
 * {@code structuredJson} (nullable) is the best-effort parsed JSON object when an
 * {@code outputSchema} was requested; {@code usage} is the summed token accounting; {@code status}
 * mirrors the persisted {@link AgentRun.Status}.
 */
public record AgentRunResult(
        String runId,
        String outputText,
        Map<String, Object> structuredJson,
        TokenUsage usage,
        String status) {
}
