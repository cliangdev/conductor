package com.conductor.agent.provider;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.ChatModel;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionAssistantMessageParam;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.chat.completions.ChatCompletionToolMessageParam;
import com.openai.models.models.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * {@link ChatModelProvider} for OpenAI, backed by the official openai-java SDK — mirrors
 * {@link ClaudeProvider}'s structure so the two adapters stay easy to compare. Translates the agent
 * module's neutral {@link ChatRequest}/{@link ChatResponse} records to/from the Chat Completions API.
 * Per-key clients are cached so repeated runs for one project reuse the HTTP client.
 *
 * <p>v1 deliberately omits: streaming (the ReAct runner consumes one full turn at a time, same as
 * Claude); the newer Responses API (Chat Completions is the stable, tool-calling-complete surface);
 * structured-outputs / strict function schemas (Conductor's tool schemas — connector, HTTP-tool, and
 * built-in — are not guaranteed strict-compatible, so every tool is sent non-strict); and
 * reasoning-effort tuning ({@code request} has no neutral slot for it yet — a future enhancement, not
 * a v1 gap).
 */
@Component
public class OpenAiProvider implements ChatModelProvider {

    private static final Logger log = LoggerFactory.getLogger(OpenAiProvider.class);

    /**
     * Last-resort fallback model, used only when live discovery ({@link #availableModels}) yields
     * nothing at all — no credential, a dead/rate-limited key, or a listing call with an empty
     * candidate set after filtering. In the common case (a working key) both {@link #defaultModel()}
     * and {@link #complete}'s blank-model substitution resolve to whatever discovery reports as
     * {@code latest}, not this constant.
     */
    public static final String FALLBACK_MODEL = "gpt-5.4";

    /**
     * Larger than {@link ClaudeProvider}'s 8192: OpenAI's reasoning-model families count internal
     * reasoning tokens against {@code max_completion_tokens}, so a small budget can be fully consumed
     * before any visible answer is produced.
     */
    private static final long DEFAULT_MAX_COMPLETION_TOKENS = 32768;

    /**
     * Substrings that mark a {@code models.list()} id as NOT "the latest general-purpose chat model"
     * even though {@link ChatModel} recognizes it (see {@link #isCandidateChatModel}):
     * <ul>
     *   <li>{@code -audio}, {@code -realtime}, {@code -transcribe}, {@code -tts} — voice/audio-first
     *       models, not text chat completions.</li>
     *   <li>{@code -search} — web-search-augmented variant, not the base model.</li>
     *   <li>{@code -image} — image-generation variant.</li>
     *   <li>{@code -codex} — a coding-specialized variant, not the general-purpose flagship.</li>
     *   <li>{@code -mini}, {@code -nano} — real, cheaper/smaller tiers, but never "the latest"
     *       flagship a blank-model agent should be defaulted onto.</li>
     * </ul>
     * Dated snapshots (e.g. {@code gpt-5.2-2025-12-11}) are excluded separately, by
     * {@link #DATED_SNAPSHOT_ID_SUFFIX} — a pinned snapshot is never "the latest" once the rolling
     * alias for it exists.
     */
    private static final List<String> EXCLUDED_MODEL_ID_SUBSTRINGS = List.of(
            "-audio", "-realtime", "-search", "-transcribe", "-tts", "-image", "-codex", "-mini", "-nano");

    /** See {@link #EXCLUDED_MODEL_ID_SUBSTRINGS}. */
    private static final Pattern DATED_SNAPSHOT_ID_SUFFIX = Pattern.compile("-\\d{4}-\\d{2}-\\d{2}$");

    private static final Duration MODEL_LIST_TTL = Duration.ofMinutes(30);

    /** Shorter TTL for a failed/empty listing, so a dead or rate-limited key doesn't get retried on every turn. */
    private static final Duration MODEL_LIST_NEGATIVE_TTL = Duration.ofMinutes(5);

    private record ModelCacheEntry(List<ModelInfo> models, Instant expiresAt) {}

    // No ObjectMapper here, unlike ClaudeProvider: tool arguments cross this adapter as raw JSON
    // strings in both directions (Function.arguments() / Function.Builder.arguments(String)), so
    // nothing ever needs parsing into a Map.
    private final Map<String, OpenAIClient> clientCache = new ConcurrentHashMap<>();
    private final Map<String, ModelCacheEntry> modelCache = new ConcurrentHashMap<>();
    private final Function<String, OpenAIClient> clientFactory;

    public OpenAiProvider() {
        this(apiKey -> OpenAIOkHttpClient.builder().apiKey(apiKey).build());
    }

    /** Test seam: injects a stub client factory so tests never hit the real OpenAI API. */
    OpenAiProvider(Function<String, OpenAIClient> clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public String id() {
        return "openai";
    }

    /**
     * Static hint only — this SPI method takes no {@code apiKey}, so it can't run live discovery.
     * Feeds UI surfaces that don't have a credential in hand yet (the Agents-form placeholder).
     * {@link #complete}'s actual blank-model substitution uses {@link #availableModels} instead, so
     * the two deliberately answer different questions: this is "what would a generic hint show?",
     * that is "what's newest for this specific key, right now?". Do not unify them.
     */
    @Override
    public String defaultModel() {
        return FALLBACK_MODEL;
    }

    @Override
    public List<ModelInfo> availableModels(String apiKey) {
        ModelCacheEntry cached = modelCache.get(apiKey);
        Instant now = Instant.now();
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.models();
        }
        List<ModelInfo> models = listModels(apiKey);
        Duration ttl = models.isEmpty() ? MODEL_LIST_NEGATIVE_TTL : MODEL_LIST_TTL;
        modelCache.put(apiKey, new ModelCacheEntry(models, now.plus(ttl)));
        return models;
    }

    private List<ModelInfo> listModels(String apiKey) {
        try {
            OpenAIClient client = clientCache.computeIfAbsent(apiKey, clientFactory);
            List<Model> candidates = client.models().list().data().stream()
                    .filter(m -> isCandidateChatModel(m.id()))
                    // Newest first; id descending is an arbitrary but deterministic tie-break for
                    // models sharing a created() timestamp.
                    .sorted(Comparator.comparingLong(Model::created).thenComparing(Model::id).reversed())
                    .toList();

            List<ModelInfo> result = new ArrayList<>(candidates.size());
            for (int i = 0; i < candidates.size(); i++) {
                result.add(new ModelInfo(candidates.get(i).id(), i == 0));
            }
            return result;
        } catch (RuntimeException e) {
            // Never let a listing failure propagate: a dead/rate-limited key or a network hiccup must
            // not break the ReAct loop's default-model resolution or an Agents-form model picker.
            // Key deliberately omitted from the log line.
            log.debug("OpenAI model discovery failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * True when {@code id} is a model {@link ChatModel} recognizes (bounding the candidate set to
     * chat/tool-calling models the SDK understands, excluding embeddings/audio-transcription/image
     * models and Responses-API-only models Chat Completions can't run) and isn't one of the
     * specialized/cheaper-tier/dated-snapshot variants filtered by {@link #EXCLUDED_MODEL_ID_SUBSTRINGS}.
     */
    private boolean isCandidateChatModel(String id) {
        if (ChatModel.of(id).value() == ChatModel.Value._UNKNOWN) {
            return false;
        }
        for (String excluded : EXCLUDED_MODEL_ID_SUBSTRINGS) {
            if (id.contains(excluded)) {
                return false;
            }
        }
        return !DATED_SNAPSHOT_ID_SUFFIX.matcher(id).find();
    }

    private String resolveDefaultModel(String apiKey) {
        for (ModelInfo info : availableModels(apiKey)) {
            if (info.latest()) {
                return info.id();
            }
        }
        return FALLBACK_MODEL;
    }

    @Override
    public ChatResponse complete(ChatRequest request, String apiKey) {
        OpenAIClient client = clientCache.computeIfAbsent(apiKey, clientFactory);

        String model = request.model() == null || request.model().isBlank()
                ? resolveDefaultModel(apiKey) : request.model();
        long maxCompletionTokens = request.maxTokens() == null
                ? DEFAULT_MAX_COMPLETION_TOKENS : request.maxTokens();

        // NOTE: temperature is intentionally NOT sent, and max_completion_tokens is used instead of
        // max_tokens. Current OpenAI reasoning models (the gpt-5.x family) reject sampling params
        // (temperature/top_p) and the legacy max_tokens field with a 400. request.temperature() is
        // kept on the neutral ChatRequest for providers that do support it (Gemini/Claude seams).
        ChatCompletionCreateParams.Builder params = ChatCompletionCreateParams.builder()
                .model(model)
                .maxCompletionTokens(maxCompletionTokens);

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            params.addSystemMessage(request.systemPrompt());
        }

        for (ToolDef tool : request.tools()) {
            params.addFunctionTool(toFunctionDefinition(tool));
        }

        for (ChatMessage msg : request.messages()) {
            appendMessage(params, msg);
        }

        try {
            ChatCompletion response = client.chat().completions().create(params.build());
            return toChatResponse(response);
        } catch (OpenAIServiceException e) {
            int status = e.statusCode();
            boolean retryable = status == 429 || status >= 500;
            throw new ModelProviderException("OpenAI request failed (" + status + "): " + e.getMessage(), retryable, e);
        } catch (RuntimeException e) {
            // Connection / serialization failures — treat as retryable transient errors.
            throw new ModelProviderException("OpenAI request failed: " + e.getMessage(), true, e);
        }
    }

    private FunctionDefinition toFunctionDefinition(ToolDef tool) {
        Map<String, Object> input = tool.inputSchema() == null
                ? Map.of("type", "object", "properties", Map.of())
                : tool.inputSchema();

        FunctionParameters.Builder parameters = FunctionParameters.builder();
        input.forEach((k, v) -> parameters.putAdditionalProperty(k, JsonValue.from(v)));

        // strict is deliberately left unset (non-strict mode) — see class javadoc.
        return FunctionDefinition.builder()
                .name(tool.name())
                .description(tool.description() == null ? "" : tool.description())
                .parameters(parameters.build())
                .build();
    }

    private void appendMessage(ChatCompletionCreateParams.Builder params, ChatMessage msg) {
        switch (msg.role()) {
            case USER -> params.addUserMessage(msg.text() == null ? "" : msg.text());
            case ASSISTANT -> {
                ChatCompletionAssistantMessageParam.Builder assistant = ChatCompletionAssistantMessageParam.builder();
                if (msg.text() != null && !msg.text().isBlank()) {
                    assistant.content(msg.text());
                }
                for (ToolCall call : msg.toolCalls()) {
                    assistant.addToolCall(ChatCompletionMessageFunctionToolCall.builder()
                            .id(call.id())
                            .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                    .name(call.name())
                                    .arguments(call.argumentsJson() == null ? "{}" : call.argumentsJson())
                                    .build())
                            .build());
                }
                // content and toolCalls are both Optional — an empty assistant turn (neither) builds fine.
                params.addMessage(assistant.build());
            }
            case TOOL -> params.addMessage(ChatCompletionToolMessageParam.builder()
                    .toolCallId(msg.toolCallId())
                    .content(msg.text() == null ? "" : msg.text())
                    .build());
        }
    }

    private ChatResponse toChatResponse(ChatCompletion response) {
        if (response.choices().isEmpty()) {
            return new ChatResponse(ChatResponse.StopReason.COMPLETE, "", List.of(), usageOf(response));
        }

        ChatCompletion.Choice choice = response.choices().get(0);
        ChatCompletionMessage message = choice.message();

        String text = message.content().orElse("");
        List<ToolCall> toolCalls = new ArrayList<>();
        for (ChatCompletionMessageToolCall call : message.toolCalls().orElse(List.of())) {
            call.function().ifPresent(fn -> toolCalls.add(new ToolCall(fn.id(), fn.function().name(), fn.function().arguments())));
        }

        ChatResponse.StopReason stop;
        if (!toolCalls.isEmpty()) {
            stop = ChatResponse.StopReason.TOOL_USE;
        } else if (choice.finishReason().equals(ChatCompletion.Choice.FinishReason.LENGTH)) {
            stop = ChatResponse.StopReason.MAX_TOKENS;
        } else {
            stop = ChatResponse.StopReason.COMPLETE;
        }

        return new ChatResponse(stop, text, toolCalls, usageOf(response));
    }

    private TokenUsage usageOf(ChatCompletion response) {
        return response.usage()
                .map(u -> new TokenUsage(u.promptTokens(), u.completionTokens()))
                .orElse(TokenUsage.ZERO);
    }
}
