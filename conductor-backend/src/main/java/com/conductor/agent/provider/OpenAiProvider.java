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
     *
     * <p>Deliberately conservative — not the newest id the SDK knows about (already ahead of this, e.g.
     * {@code gpt-5.5}/{@code gpt-5.6-*}): unlike {@link OpenAiApiPreflight#PROBE_MODEL} (which wants the
     * cheapest model broadly available), this wants the model most likely to still exist and be
     * reachable on *any* account when discovery itself couldn't be trusted — the newest id is exactly the
     * one least likely to satisfy that on an account that hasn't been granted access to it yet.
     */
    public static final String FALLBACK_MODEL = "gpt-5.4";

    /**
     * Larger than {@link ClaudeProvider}'s 8192: OpenAI's reasoning-model families count internal
     * reasoning tokens against {@code max_completion_tokens}, so a small budget can be fully consumed
     * before any visible answer is produced.
     */
    private static final long DEFAULT_MAX_COMPLETION_TOKENS = 32768;

    /**
     * A candidate chat-model id: the general-purpose {@code gpt-<digit>...} and {@code o<digit>...}
     * (reasoning) families. This replaces gating on the pinned SDK's {@link ChatModel} enum — that enum
     * is frozen at the SDK version this backend was built against, so any model OpenAI ships afterward
     * (e.g. a same-generation {@code gpt-6}) would be permanently invisible to discovery, defeating the
     * entire point of live discovery ("track new releases without a code change" — see
     * {@code docs/ai-providers.md}). The tradeoff is real: an id that happens to match this shape but
     * isn't actually a real/supported model can now surface where the SDK enum would have caught it —
     * accepted as the price of not being capped by the SDK's release cadence.
     *
     * <p>Deliberately excludes ids that don't fit either shape, e.g. {@code chatgpt-4o-latest},
     * {@code davinci-002}, {@code tts-1}, {@code whisper-1}, {@code dall-e-3}, {@code text-embedding-*},
     * {@code omni-moderation-*} — none of those are general-purpose chat-completions models.
     */
    private static final Pattern CHAT_FAMILY_ID_PATTERN = Pattern.compile("^(gpt-\\d.*|o\\d.*)$");

    /**
     * Substrings that mark a {@link #CHAT_FAMILY_ID_PATTERN}-matching id as NOT a general-purpose chat
     * *candidate* at all — filtered out of the picker entirely, not just out of "latest" contention (see
     * {@link #isCandidateChatModel}):
     * <ul>
     *   <li>{@code -audio}, {@code -realtime}, {@code -transcribe}, {@code -tts} — voice/audio-first
     *       models, not text chat completions.</li>
     *   <li>{@code -search} — web-search-augmented variant, not the base model.</li>
     *   <li>{@code -image} — image-generation variant.</li>
     *   <li>{@code -deep-research} — a distinct, long-running research mode, not ordinary chat.</li>
     *   <li>{@code -preview} — pre-GA variants (also catches audio/realtime/search preview ids not
     *       already caught above).</li>
     * </ul>
     * These are excluded from the candidate set entirely (unlike {@link #FLAGSHIP_EXCLUDED_SUBSTRINGS}
     * below): an operator can never usefully pin one for chat completions, so there's no reason to offer
     * them in the picker.
     */
    private static final List<String> CANDIDATE_EXCLUDED_SUBSTRINGS = List.of(
            "-audio", "-realtime", "-search", "-transcribe", "-tts", "-image", "-deep-research", "-preview");

    /**
     * Additional substrings that disqualify a *candidate* from being the {@code latest} flagship,
     * without removing it from the candidate list the picker offers (see {@link #isFlagshipCandidate}).
     * These are all real, pinnable models — {@code docs/ai-providers.md} explicitly recommends pinning
     * "an older/cheaper tier than the current default" — just never the id a blank-model agent should
     * silently resolve to:
     * <ul>
     *   <li>{@code -mini}, {@code -nano} — cheaper/smaller tiers.</li>
     *   <li>{@code -pro} — a pricier tier, not the flagship default (the bug this list exists to close:
     *       a same-day {@code -pro} release must never outrank its base model as {@code latest}).</li>
     *   <li>{@code -codex} — a coding-specialized variant, not the general-purpose flagship.</li>
     *   <li>{@code -chat-latest} — itself a rolling alias, but a distinct product line from the numbered
     *       flagship (e.g. {@code gpt-5-chat-latest} vs {@code gpt-5.4}).</li>
     * </ul>
     * Dated snapshots (e.g. {@code gpt-5.2-2025-12-11}) are excluded the same way, via
     * {@link #DATED_SNAPSHOT_ID_SUFFIX} — a pinned snapshot is a deliberate choice, never "the latest".
     */
    private static final List<String> FLAGSHIP_EXCLUDED_SUBSTRINGS = List.of(
            "-mini", "-nano", "-pro", "-codex", "-chat-latest");

    /** See {@link #FLAGSHIP_EXCLUDED_SUBSTRINGS}. */
    private static final Pattern DATED_SNAPSHOT_ID_SUFFIX = Pattern.compile("-\\d{4}-\\d{2}-\\d{2}$");

    /**
     * Bounds how many raw {@code models.list()} entries the auto-pager will fetch before filtering.
     * OpenAI's catalog is a few hundred ids; this is generous headroom while still guaranteeing a
     * pathological/misbehaving catalog can't make discovery page unboundedly.
     */
    private static final int RAW_MODEL_LIST_CAP = 200;

    /**
     * Newest-{@code created} first; among ids sharing a {@code created} timestamp, the *shorter* id
     * sorts first, and equal-length ids break by id descending.
     *
     * <p>Both tie-break steps matter, in this order. A base model id is always a strict prefix of its
     * own suffixed variants ({@code gpt-5.2} / {@code gpt-5.2-pro}), so shortest-first ranks a base
     * model above same-day variants of itself — sorting the whole comparator (tie-break included) in
     * descending id order does the reverse, ranking every variant above its base purely for being a
     * longer string. Among ids of equal length, though, descending is right: {@code gpt-5.5} should
     * outrank {@code gpt-5.4} when the two share a timestamp, and ascending would pick the older one.
     *
     * <p>This only narrows same-timestamp mistakes. The more common case — a variant shipped on a later
     * date than its base — is handled by {@link #isFlagshipCandidate}, which excludes known variant
     * suffixes from flagship contention regardless of date.
     */
    private static final Comparator<Model> NEWEST_FIRST_BASE_BEFORE_VARIANT = Comparator
            .comparingLong(Model::created).reversed()
            .thenComparingInt((Model m) -> m.id().length())
            .thenComparing(Comparator.comparing(Model::id).reversed());

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

    /** Unlike {@link ClaudeProvider} (a fixed constant), a blank OpenAI model resolves live — see {@link #resolveDefaultModel}. */
    @Override
    public boolean defaultModelIsLive() {
        return true;
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
            // autoPager(), not .data() (the first page only) -- the raw catalog is a few hundred ids,
            // well past a single page, so .data() alone silently truncated the picker.
            List<Model> candidates = client.models().list().autoPager().stream()
                    .limit(RAW_MODEL_LIST_CAP)
                    .filter(m -> isCandidateChatModel(m.id()))
                    .sorted(NEWEST_FIRST_BASE_BEFORE_VARIANT)
                    .toList();

            // latest goes to the first candidate (in the newest-first order above) that also clears the
            // stricter flagship bar -- not necessarily index 0, e.g. a dated snapshot or -mini variant
            // can be newest-created without ever being "the" flagship default.
            String flagshipId = candidates.stream()
                    .map(Model::id)
                    .filter(this::isFlagshipCandidate)
                    .findFirst()
                    .orElse(null);

            return candidates.stream()
                    .map(m -> new ModelInfo(m.id(), m.id().equals(flagshipId)))
                    .toList();
        } catch (RuntimeException e) {
            // Never let a listing failure propagate: a dead/rate-limited key or a network hiccup must
            // not break the ReAct loop's default-model resolution or an Agents-form model picker.
            // Key deliberately omitted from the log line.
            log.debug("OpenAI model discovery failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * True when {@code id} matches the general-purpose chat family ({@link #CHAT_FAMILY_ID_PATTERN}) and
     * isn't one of the non-chat/specialized-modality variants filtered by
     * {@link #CANDIDATE_EXCLUDED_SUBSTRINGS}. This is everything the Agents-form model picker offers —
     * broader than {@link #isFlagshipCandidate}, which additionally excludes ids nobody should be
     * defaulted onto blind (mini/nano/pro/codex/dated-snapshot) but that remain legitimate to pin
     * explicitly.
     */
    private boolean isCandidateChatModel(String id) {
        if (!CHAT_FAMILY_ID_PATTERN.matcher(id).matches()) {
            return false;
        }
        for (String excluded : CANDIDATE_EXCLUDED_SUBSTRINGS) {
            if (id.contains(excluded)) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when a candidate id (already passed {@link #isCandidateChatModel}) is also eligible to be
     * flagged {@code latest} -- see {@link #FLAGSHIP_EXCLUDED_SUBSTRINGS}.
     */
    private boolean isFlagshipCandidate(String id) {
        for (String excluded : FLAGSHIP_EXCLUDED_SUBSTRINGS) {
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
