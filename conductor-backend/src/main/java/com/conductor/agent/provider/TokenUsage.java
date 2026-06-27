package com.conductor.agent.provider;

/** Token accounting for a single model call. Summed across turns for an {@code AgentRun}. */
public record TokenUsage(long inputTokens, long outputTokens) {

    public static final TokenUsage ZERO = new TokenUsage(0, 0);

    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(inputTokens + other.inputTokens, outputTokens + other.outputTokens);
    }
}
