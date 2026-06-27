package com.conductor.agent.provider;

import java.util.List;

/**
 * A provider-neutral model response. Either {@code text} carries the final answer, or
 * {@code toolCalls} carries one or more tool invocations the runner must satisfy before continuing.
 * {@code stopReason} tells the runner which.
 */
public record ChatResponse(
        StopReason stopReason,
        String text,
        List<ToolCall> toolCalls,
        TokenUsage usage) {

    public enum StopReason {
        /** Model produced a final answer. */
        COMPLETE,
        /** Model requested one or more tool calls — execute them and continue the loop. */
        TOOL_USE,
        /** Model hit the output cap mid-answer. */
        MAX_TOKENS
    }

    public ChatResponse {
        if (toolCalls == null) toolCalls = List.of();
        if (usage == null) usage = TokenUsage.ZERO;
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
