package com.conductor.agent.provider;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link ChatModelProvider} for Anthropic Claude, backed by the official anthropic-java SDK.
 * Translates the agent module's neutral {@link ChatRequest}/{@link ChatResponse} records to/from the
 * Messages API. Per-key clients are cached so repeated runs for one project reuse the HTTP client.
 *
 * <p>v1 omits extended thinking (no {@code thinking} param) to keep the tool-calling loop simple —
 * there are no thinking blocks to replay across turns. Adaptive thinking is a future enhancement.
 */
@Component
public class ClaudeProvider implements ChatModelProvider {

    /** Default model when an agent does not pin one. Latest Opus per the claude-api guidance. */
    public static final String DEFAULT_MODEL = "claude-opus-4-8";
    private static final int DEFAULT_MAX_TOKENS = 8192;

    private final ObjectMapper objectMapper;
    private final Map<String, AnthropicClient> clientCache = new ConcurrentHashMap<>();

    public ClaudeProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String id() {
        return "claude";
    }

    @Override
    public String defaultModel() {
        return DEFAULT_MODEL;
    }

    @Override
    public ChatResponse complete(ChatRequest request, String apiKey) {
        AnthropicClient client = clientCache.computeIfAbsent(apiKey,
                k -> AnthropicOkHttpClient.builder().apiKey(k).build());

        String model = request.model() == null || request.model().isBlank()
                ? DEFAULT_MODEL : request.model();
        long maxTokens = request.maxTokens() == null ? DEFAULT_MAX_TOKENS : request.maxTokens();

        // NOTE: temperature is intentionally NOT sent. The current Claude models (Opus 4.8/4.7,
        // Fable 5) reject sampling params (temperature/top_p/top_k) with a 400. request.temperature()
        // is kept on the neutral ChatRequest for providers that do support it (Gemini/OpenAI seams).
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(maxTokens);

        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            params.system(request.systemPrompt());
        }

        for (ToolDef tool : request.tools()) {
            params.addTool(toSdkTool(tool));
        }

        for (ChatMessage msg : request.messages()) {
            appendMessage(params, msg);
        }

        try {
            Message response = client.messages().create(params.build());
            return toChatResponse(response);
        } catch (AnthropicServiceException e) {
            int status = e.statusCode();
            boolean retryable = status == 429 || status >= 500;
            throw new ModelProviderException("Claude request failed (" + status + "): " + e.getMessage(), retryable, e);
        } catch (RuntimeException e) {
            // Connection / serialization failures — treat as retryable transient errors.
            throw new ModelProviderException("Claude request failed: " + e.getMessage(), true, e);
        }
    }

    private Tool toSdkTool(ToolDef tool) {
        Tool.InputSchema.Builder schema = Tool.InputSchema.builder();
        Map<String, Object> input = tool.inputSchema() == null ? Map.of() : tool.inputSchema();

        Object props = input.get("properties");
        if (props instanceof Map<?, ?> propMap) {
            Tool.InputSchema.Properties.Builder propsBuilder = Tool.InputSchema.Properties.builder();
            propMap.forEach((k, v) -> propsBuilder.putAdditionalProperty(String.valueOf(k), JsonValue.from(v)));
            schema.properties(propsBuilder.build());
        }
        Object required = input.get("required");
        if (required instanceof List<?> reqList) {
            List<String> reqs = new ArrayList<>();
            reqList.forEach(r -> reqs.add(String.valueOf(r)));
            schema.required(reqs);
        }

        return Tool.builder()
                .name(tool.name())
                .description(tool.description() == null ? "" : tool.description())
                .inputSchema(schema.build())
                .build();
    }

    private void appendMessage(MessageCreateParams.Builder params, ChatMessage msg) {
        switch (msg.role()) {
            case USER -> params.addUserMessage(msg.text() == null ? "" : msg.text());
            case ASSISTANT -> {
                List<ContentBlockParam> blocks = new ArrayList<>();
                if (msg.text() != null && !msg.text().isBlank()) {
                    blocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(msg.text()).build()));
                }
                for (ToolCall call : msg.toolCalls()) {
                    ToolUseBlockParam.Input.Builder inputBuilder = ToolUseBlockParam.Input.builder();
                    parseArgs(call.argumentsJson())
                            .forEach((k, v) -> inputBuilder.putAdditionalProperty(k, JsonValue.from(v)));
                    blocks.add(ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                            .id(call.id())
                            .name(call.name())
                            .input(inputBuilder.build())
                            .build()));
                }
                params.addMessage(MessageParam.builder()
                        .role(MessageParam.Role.ASSISTANT)
                        .contentOfBlockParams(blocks)
                        .build());
            }
            case TOOL -> params.addMessage(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(List.of(ContentBlockParam.ofToolResult(
                            ToolResultBlockParam.builder()
                                    .toolUseId(msg.toolCallId())
                                    .content(msg.text() == null ? "" : msg.text())
                                    .build())))
                    .build());
        }
    }

    private ChatResponse toChatResponse(Message response) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();

        for (ContentBlock block : response.content()) {
            block.text().ifPresent(t -> text.append(t.text()));
            block.toolUse().ifPresent(tu -> {
                String argsJson;
                try {
                    argsJson = objectMapper.writeValueAsString(tu._input().convert(Object.class));
                } catch (Exception e) {
                    argsJson = "{}";
                }
                toolCalls.add(new ToolCall(tu.id(), tu.name(), argsJson));
            });
        }

        TokenUsage usage = response.usage() != null
                ? new TokenUsage(response.usage().inputTokens(), response.usage().outputTokens())
                : TokenUsage.ZERO;

        ChatResponse.StopReason stop;
        if (!toolCalls.isEmpty()) {
            stop = ChatResponse.StopReason.TOOL_USE;
        } else if (response.stopReason().map(r -> r.equals(com.anthropic.models.messages.StopReason.MAX_TOKENS)).orElse(false)) {
            stop = ChatResponse.StopReason.MAX_TOKENS;
        } else {
            stop = ChatResponse.StopReason.COMPLETE;
        }

        return new ChatResponse(stop, text.toString(), toolCalls, usage);
    }

    private Map<String, Object> parseArgs(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(argumentsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
