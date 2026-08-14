package com.conductor.agent.provider;

import com.openai.client.OpenAIClient;
import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.core.JsonValue;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.chat.completions.ChatCompletionMessage;
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall;
import com.openai.models.chat.completions.ChatCompletionMessageToolCall;
import com.openai.models.completions.CompletionUsage;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (no Spring, no real network call) for {@link OpenAiProvider}. Uses the package-private
 * client-factory constructor to inject a mocked {@link OpenAIClient}, capturing the
 * {@link ChatCompletionCreateParams} the SDK receives to verify message/tool translation, and mocking
 * {@link ChatCompletion} responses to verify the reverse translation.
 */
class OpenAiProviderTest {


    // ---- request-building: message ordering / role mapping ----

    @Test
    void translatesSystemUserAssistantAndToolMessagesInOrder() {
        ChatCompletionService completions = mock(ChatCompletionService.class);
        OpenAiProvider provider = providerFor(completions, chatCompletion("hi", List.of(), ChatCompletion.Choice.FinishReason.STOP));

        ChatRequest request = new ChatRequest("gpt-5.4", "You are helpful.", List.of(
                ChatMessage.user("Hello"),
                ChatMessage.assistant("Let me check.", List.of(new ToolCall("call_1", "lookup", "{\"q\":\"x\"}"))),
                ChatMessage.toolResult("call_1", "result text"),
                ChatMessage.user("Thanks")
        ), List.of(), null, null);

        provider.complete(request, "sk-test");

        ChatCompletionCreateParams params = capturedParams(completions);
        List<?> messages = params.messages();
        assertThat(messages).hasSize(5); // system + user + assistant + tool + user

        assertThat(messages.get(0).toString()).contains("You are helpful.");
        assertThat(messages.get(1).toString()).contains("Hello");
        assertThat(messages.get(2).toString()).contains("Let me check.").contains("lookup").contains("call_1");
        assertThat(messages.get(3).toString()).contains("call_1").contains("result text");
        assertThat(messages.get(4).toString()).contains("Thanks");
    }

    @Test
    void assistantMessageWithNoTextOrToolCallsBuildsSafely() {
        ChatCompletionService completions = mock(ChatCompletionService.class);
        OpenAiProvider provider = providerFor(completions, chatCompletion("ok", List.of(), ChatCompletion.Choice.FinishReason.STOP));

        ChatRequest request = new ChatRequest(null, null,
                List.of(ChatMessage.assistant(null, null)), List.of(), null, null);

        provider.complete(request, "sk-test");

        ChatCompletionCreateParams params = capturedParams(completions);
        assertThat(params.messages()).hasSize(1);
    }

    @Test
    void blankSystemPromptIsOmitted() {
        ChatCompletionService completions = mock(ChatCompletionService.class);
        OpenAiProvider provider = providerFor(completions, chatCompletion("ok", List.of(), ChatCompletion.Choice.FinishReason.STOP));

        ChatRequest request = new ChatRequest(null, "   ", List.of(ChatMessage.user("hi")), List.of(), null, null);

        provider.complete(request, "sk-test");

        ChatCompletionCreateParams params = capturedParams(completions);
        assertThat(params.messages()).hasSize(1);
    }

    // ---- tool definition translation ----

    @Test
    void translatesToolDefinitionNameDescriptionAndParameters() {
        ChatCompletionService completions = mock(ChatCompletionService.class);
        OpenAiProvider provider = providerFor(completions, chatCompletion("ok", List.of(), ChatCompletion.Choice.FinishReason.STOP));

        Map<String, Object> schema = Map.of("type", "object", "properties",
                Map.of("q", Map.of("type", "string")), "required", List.of("q"));
        ChatRequest request = new ChatRequest(null, null, List.of(ChatMessage.user("hi")),
                List.of(new ToolDef("search", "Searches things.", schema)), null, null);

        provider.complete(request, "sk-test");

        ChatCompletionCreateParams params = capturedParams(completions);
        assertThat(params.tools()).isPresent();
        assertThat(params.tools().get()).hasSize(1);
        String toolString = params.tools().get().get(0).toString();
        assertThat(toolString).contains("search").contains("Searches things.").contains("properties");
    }

    @Test
    void nullInputSchemaBecomesEmptyObjectSchema() {
        ChatCompletionService completions = mock(ChatCompletionService.class);
        OpenAiProvider provider = providerFor(completions, chatCompletion("ok", List.of(), ChatCompletion.Choice.FinishReason.STOP));

        ChatRequest request = new ChatRequest(null, null, List.of(ChatMessage.user("hi")),
                List.of(new ToolDef("noop", "Does nothing.", null)), null, null);

        provider.complete(request, "sk-test");

        ChatCompletionCreateParams params = capturedParams(completions);
        assertThat(params.tools()).isPresent();
        assertThat(params.tools().get()).hasSize(1);
        assertThat(params.tools().get().get(0).toString()).contains("object");
    }

    // ---- max tokens ----

    @Test
    void nullMaxTokensAppliesDefaultMaxCompletionTokensAndNeverSendsMaxTokens() {
        ChatCompletionService completions = mock(ChatCompletionService.class);
        OpenAiProvider provider = providerFor(completions, chatCompletion("ok", List.of(), ChatCompletion.Choice.FinishReason.STOP));

        provider.complete(new ChatRequest(null, null, List.of(ChatMessage.user("hi")), List.of(), null, null), "sk-test");

        ChatCompletionCreateParams params = capturedParams(completions);
        assertThat(params.maxCompletionTokens()).contains(32768L);
        assertThat(params.maxTokens()).isEmpty();
    }

    @Test
    void explicitMaxTokensFlowsToMaxCompletionTokens() {
        ChatCompletionService completions = mock(ChatCompletionService.class);
        OpenAiProvider provider = providerFor(completions, chatCompletion("ok", List.of(), ChatCompletion.Choice.FinishReason.STOP));

        provider.complete(new ChatRequest(null, null, List.of(ChatMessage.user("hi")), List.of(), 4096, null), "sk-test");

        ChatCompletionCreateParams params = capturedParams(completions);
        assertThat(params.maxCompletionTokens()).contains(4096L);
        assertThat(params.maxTokens()).isEmpty();
    }

    @Test
    void defaultModelIsUsedWhenRequestModelIsBlank() {
        ChatCompletionService completions = mock(ChatCompletionService.class);
        OpenAiProvider provider = providerFor(completions, chatCompletion("ok", List.of(), ChatCompletion.Choice.FinishReason.STOP));

        provider.complete(new ChatRequest("  ", null, List.of(ChatMessage.user("hi")), List.of(), null, null), "sk-test");

        ChatCompletionCreateParams params = capturedParams(completions);
        assertThat(params.model().asString()).isEqualTo(OpenAiProvider.FALLBACK_MODEL);
        assertThat(provider.defaultModel()).isEqualTo(OpenAiProvider.FALLBACK_MODEL);
    }

    // ---- response translation: tool-call extraction round trip ----

    @Test
    void toolCallsAreExtractedWithIdNameAndArgumentsJson() {
        ChatCompletionMessageToolCall call = ChatCompletionMessageToolCall.ofFunction(
                ChatCompletionMessageFunctionToolCall.builder()
                        .id("call_9")
                        .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                .name("do_thing")
                                .arguments("{\"x\":1}")
                                .build())
                        .build());
        ChatCompletionService completions = mock(ChatCompletionService.class);
        OpenAiProvider provider = providerFor(completions,
                chatCompletion(null, List.of(call), ChatCompletion.Choice.FinishReason.TOOL_CALLS));

        ChatResponse response = provider.complete(
                new ChatRequest(null, null, List.of(ChatMessage.user("hi")), List.of(), null, null), "sk-test");

        assertThat(response.stopReason()).isEqualTo(ChatResponse.StopReason.TOOL_USE);
        assertThat(response.toolCalls()).hasSize(1);
        ToolCall extracted = response.toolCalls().get(0);
        assertThat(extracted.id()).isEqualTo("call_9");
        assertThat(extracted.name()).isEqualTo("do_thing");
        assertThat(extracted.argumentsJson()).isEqualTo("{\"x\":1}");
    }

    // ---- stop-reason mapping ----

    @Test
    void lengthFinishReasonMapsToMaxTokensWhenNoToolCalls() {
        ChatCompletionService completions = mock(ChatCompletionService.class);
        OpenAiProvider provider = providerFor(completions,
                chatCompletion("partial answer", List.of(), ChatCompletion.Choice.FinishReason.LENGTH));

        ChatResponse response = provider.complete(
                new ChatRequest(null, null, List.of(ChatMessage.user("hi")), List.of(), null, null), "sk-test");

        assertThat(response.stopReason()).isEqualTo(ChatResponse.StopReason.MAX_TOKENS);
        assertThat(response.text()).isEqualTo("partial answer");
    }

    @Test
    void stopFinishReasonMapsToComplete() {
        ChatCompletionService completions = mock(ChatCompletionService.class);
        OpenAiProvider provider = providerFor(completions,
                chatCompletion("done", List.of(), ChatCompletion.Choice.FinishReason.STOP));

        ChatResponse response = provider.complete(
                new ChatRequest(null, null, List.of(ChatMessage.user("hi")), List.of(), null, null), "sk-test");

        assertThat(response.stopReason()).isEqualTo(ChatResponse.StopReason.COMPLETE);
    }

    @Test
    void emptyChoicesListDoesNotNpeAndReturnsCompleteWithEmptyText() {
        ChatCompletion response = ChatCompletion.builder()
                .id("resp_1")
                .created(0)
                .model("gpt-5.4")
                .choices(List.of())
                .build();
        ChatCompletionService completions = mock(ChatCompletionService.class);
        OpenAiProvider provider = providerFor(completions, response);

        ChatResponse result = provider.complete(
                new ChatRequest(null, null, List.of(ChatMessage.user("hi")), List.of(), null, null), "sk-test");

        assertThat(result.stopReason()).isEqualTo(ChatResponse.StopReason.COMPLETE);
        assertThat(result.text()).isEmpty();
        assertThat(result.toolCalls()).isEmpty();
    }

    // ---- usage summation ----

    @Test
    void usageIsSummedFromPromptAndCompletionTokens() {
        ChatCompletionService completions = mock(ChatCompletionService.class);
        ChatCompletion response = chatCompletion("ok", List.of(), ChatCompletion.Choice.FinishReason.STOP).toBuilder()
                .usage(CompletionUsage.builder().promptTokens(100).completionTokens(50).totalTokens(150).build())
                .build();
        OpenAiProvider provider = providerFor(completions, response);

        ChatResponse result = provider.complete(
                new ChatRequest(null, null, List.of(ChatMessage.user("hi")), List.of(), null, null), "sk-test");

        assertThat(result.usage()).isEqualTo(new TokenUsage(100, 50));
    }

    @Test
    void missingUsageBecomesZero() {
        ChatCompletionService completions = mock(ChatCompletionService.class);
        OpenAiProvider provider = providerFor(completions, chatCompletion("ok", List.of(), ChatCompletion.Choice.FinishReason.STOP));

        ChatResponse result = provider.complete(
                new ChatRequest(null, null, List.of(ChatMessage.user("hi")), List.of(), null, null), "sk-test");

        assertThat(result.usage()).isEqualTo(TokenUsage.ZERO);
    }

    // ---- error classification ----

    @Test
    void rateLimitBecomesRetryableModelProviderException() {
        assertRetryable(RateLimitException.builder().headers(emptyHeaders()).build(), true);
    }

    @Test
    void internalServerErrorBecomesRetryableModelProviderException() {
        assertRetryable(com.openai.errors.InternalServerException.builder().statusCode(500).headers(emptyHeaders()).build(), true);
    }

    @Test
    void unauthorizedBecomesNonRetryableModelProviderException() {
        assertRetryable(UnauthorizedException.builder().headers(emptyHeaders()).build(), false);
    }

    @Test
    void badRequestBecomesNonRetryableModelProviderException() {
        assertRetryable(BadRequestException.builder().headers(emptyHeaders()).build(), false);
    }

    private void assertRetryable(RuntimeException thrown, boolean expectedRetryable) {
        ChatCompletionService completions = mock(ChatCompletionService.class);
        when(completions.create(any(ChatCompletionCreateParams.class))).thenThrow(thrown);
        OpenAIClient client = clientFor(completions);
        OpenAiProvider provider = new OpenAiProvider(keyCapturingFactory(client));

        ChatRequest request = new ChatRequest(null, null, List.of(ChatMessage.user("hi")), List.of(), null, null);
        try {
            provider.complete(request, "sk-test");
            org.junit.jupiter.api.Assertions.fail("expected ModelProviderException");
        } catch (ModelProviderException e) {
            assertThat(e.isRetryable()).isEqualTo(expectedRetryable);
        }
    }

    // ---- helpers ----

    private OpenAiProvider providerFor(ChatCompletionService completions, ChatCompletion response) {
        when(completions.create(any(ChatCompletionCreateParams.class))).thenReturn(response);
        OpenAIClient client = clientFor(completions);
        return new OpenAiProvider(keyCapturingFactory(client));
    }

    private OpenAIClient clientFor(ChatCompletionService completions) {
        OpenAIClient client = mock(OpenAIClient.class);
        ChatService chat = mock(ChatService.class);
        when(client.chat()).thenReturn(chat);
        when(chat.completions()).thenReturn(completions);
        return client;
    }

    private Function<String, OpenAIClient> keyCapturingFactory(OpenAIClient client) {
        return apiKey -> {
            assertThat(apiKey).isEqualTo("sk-test");
            return client;
        };
    }

    private ChatCompletionCreateParams capturedParams(ChatCompletionService completions) {
        org.mockito.ArgumentCaptor<ChatCompletionCreateParams> captor =
                org.mockito.ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        verify(completions).create(captor.capture());
        return captor.getValue();
    }

    private ChatCompletion chatCompletion(String text, List<ChatCompletionMessageToolCall> toolCalls,
                                           ChatCompletion.Choice.FinishReason finishReason) {
        ChatCompletionMessage.Builder message = ChatCompletionMessage.builder()
                .role(JsonValue.from("assistant"))
                .content(java.util.Optional.ofNullable(text))
                .refusal(java.util.Optional.empty());
        for (ChatCompletionMessageToolCall call : toolCalls) {
            message.addToolCall(call);
        }

        ChatCompletion.Choice choice = ChatCompletion.Choice.builder()
                .index(0)
                .finishReason(finishReason)
                .logprobs(java.util.Optional.empty())
                .message(message.build())
                .build();

        return ChatCompletion.builder()
                .id("resp_1")
                .created(0)
                .model("gpt-5.4")
                .addChoice(choice)
                .build();
    }

    private Headers emptyHeaders() {
        return Headers.builder().build();
    }
}
