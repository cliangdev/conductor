package com.conductor.agent.provider;

/**
 * The multi-provider LLM gateway SPI. Each implementation adapts one vendor (Claude, Gemini,
 * OpenAI) behind a single normalized contract — the "chat-model" layer of the agent module.
 *
 * <p>Implementations are stateless: the per-project BYO API key is passed in on each call (decrypted
 * by the caller from {@code provider_credentials}), so one bean serves every project. Discovered by
 * {@link ModelProviderRegistry}, exactly as connectors are discovered by {@code ConnectorRegistry}.
 */
public interface ChatModelProvider {

    /** Stable provider id, e.g. {@code "claude"}, {@code "gemini"}, {@code "openai"}. */
    String id();

    /**
     * The model applied when an agent does not pin one. Surfaced to clients (e.g. the Agents UI) as a
     * placeholder/hint. Defaults to {@code null} (no advertised default).
     */
    default String defaultModel() {
        return null;
    }

    /**
     * Run one model turn. Returns either a final answer or a set of tool calls (see
     * {@link ChatResponse.StopReason}). May throw — the runner classifies and retries transient
     * failures.
     */
    ChatResponse complete(ChatRequest request, String apiKey);
}
