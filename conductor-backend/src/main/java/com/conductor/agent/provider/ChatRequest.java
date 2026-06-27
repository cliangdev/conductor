package com.conductor.agent.provider;

import java.util.List;

/**
 * A provider-neutral model invocation. The runner builds one of these per turn of the ReAct loop;
 * the {@link ChatModelProvider} translates it to the vendor API. {@code tools} may be empty (a plain
 * completion). {@code systemPrompt} may be null.
 */
public record ChatRequest(
        String model,
        String systemPrompt,
        List<ChatMessage> messages,
        List<ToolDef> tools,
        Integer maxTokens,
        Double temperature) {

    public ChatRequest {
        if (messages == null) messages = List.of();
        if (tools == null) tools = List.of();
    }
}
