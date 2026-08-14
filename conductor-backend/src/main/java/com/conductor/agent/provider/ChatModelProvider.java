package com.conductor.agent.provider;

import java.util.List;

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
     * Optional live model-discovery surface: providers backed by a vendor models-list API can report
     * the models they currently support, so an Agents-form model picker (and a provider's own
     * default-model resolution inside {@link #complete}) reflect what the account can actually use
     * today instead of a hand-maintained hardcoded list. A provider with no listing API just inherits
     * this default (an empty list) — callers must treat that the same as "unknown," not as an error.
     *
     * <p>{@code apiKey} is the project's decrypted BYO key, resolved by the caller from
     * {@code provider_credentials}; implementations must never log it. Exactly zero or one returned
     * {@link ModelInfo} has {@code latest() == true}. Implementations must never throw for a listing
     * failure (bad key, rate limit, network error) — they return an empty list instead so a dead
     * credential never breaks a caller that merely wants suggestions.
     */
    default List<ModelInfo> availableModels(String apiKey) {
        return List.of();
    }

    /**
     * Run one model turn. Returns either a final answer or a set of tool calls (see
     * {@link ChatResponse.StopReason}). May throw — the runner classifies and retries transient
     * failures.
     */
    ChatResponse complete(ChatRequest request, String apiKey);
}
