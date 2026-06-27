package com.conductor.agent.provider;

import java.util.List;

/**
 * One turn in a provider-neutral conversation. The agent module speaks only this shape; each
 * {@link ChatModelProvider} translates it to/from its vendor wire format.
 *
 * <ul>
 *   <li>{@code USER} — a user/task message; {@code text} carries the content.</li>
 *   <li>{@code ASSISTANT} — a model turn; {@code text} and/or {@code toolCalls} are populated.</li>
 *   <li>{@code TOOL} — a tool result fed back to the model; {@code toolCallId} references the
 *       originating {@link ToolCall} and {@code text} carries the (serialized) result.</li>
 * </ul>
 */
public record ChatMessage(
        Role role,
        String text,
        List<ToolCall> toolCalls,
        String toolCallId) {

    public enum Role { USER, ASSISTANT, TOOL }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, text, List.of(), null);
    }

    public static ChatMessage assistant(String text, List<ToolCall> toolCalls) {
        return new ChatMessage(Role.ASSISTANT, text, toolCalls == null ? List.of() : toolCalls, null);
    }

    public static ChatMessage toolResult(String toolCallId, String resultText) {
        return new ChatMessage(Role.TOOL, resultText, List.of(), toolCallId);
    }
}
