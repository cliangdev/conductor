package com.conductor.agent.provider;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.AutoPager;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.models.ModelListPage;
import com.anthropic.services.blocking.MessageService;
import com.anthropic.services.blocking.ModelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (no Spring, no real network call) for {@link ClaudeProvider#availableModels} — the
 * read-only discovery surface added alongside {@link OpenAiProvider}'s. {@link ClaudeProvider}'s
 * request/response translation (message roles, tool calls, stop reasons) is exercised indirectly by
 * every other agent test that runs a Claude-provider agent; this file is scoped to discovery + its
 * cache, mirroring {@code OpenAiProviderTest}'s "model discovery" section.
 */
class ClaudeProviderTest {

    @Test
    void availableModelsOrdersByCreatedAtDescendingAndMarksTheNewestLatest() {
        // Page built before when(models.list()) starts -- nesting a when(page.data()) call (inside
        // pageOf) as an argument of an unfinished outer when() confuses Mockito's ongoing-stubbing
        // state (it reports the OUTER when() as "unfinished").
        ModelListPage page = pageOf(
                modelInfo("claude-opus-4-8", OffsetDateTime.parse("2026-05-01T00:00:00Z")),
                modelInfo("claude-opus-4-7", OffsetDateTime.parse("2026-01-01T00:00:00Z")));
        ModelService models = mock(ModelService.class);
        when(models.list()).thenReturn(page);
        ClaudeProvider provider = new ClaudeProvider(new ObjectMapper(), keyCapturingFactory(clientWithModels(models)));

        List<ModelInfo> result = provider.availableModels("sk-test");

        assertThat(result).extracting(ModelInfo::id).containsExactly("claude-opus-4-8", "claude-opus-4-7");
        assertThat(result.get(0).latest()).isTrue();
        assertThat(result.get(1).latest()).isFalse();
    }

    @Test
    void availableModelsTieBreaksEqualLengthIdsAtTheSameCreatedAtByIdDescending() {
        OffsetDateTime same = OffsetDateTime.parse("2026-05-01T00:00:00Z");
        ModelListPage page = pageOf(modelInfo("claude-opus-4-7", same), modelInfo("claude-opus-4-8", same)); // see note above
        ModelService models = mock(ModelService.class);
        when(models.list()).thenReturn(page);
        ClaudeProvider provider = new ClaudeProvider(new ObjectMapper(), keyCapturingFactory(clientWithModels(models)));

        List<ModelInfo> result = provider.availableModels("sk-test");

        assertThat(result).extracting(ModelInfo::id).containsExactly("claude-opus-4-8", "claude-opus-4-7");
    }

    @Test
    void availableModelsCachesSoASecondCallWithinTtlDoesNotReList() {
        ModelListPage page = pageOf(modelInfo("claude-opus-4-8", OffsetDateTime.now())); // see note above
        ModelService models = mock(ModelService.class);
        when(models.list()).thenReturn(page);
        ClaudeProvider provider = new ClaudeProvider(new ObjectMapper(), keyCapturingFactory(clientWithModels(models)));

        provider.availableModels("sk-test");
        provider.availableModels("sk-test");

        verify(models, times(1)).list();
    }

    @Test
    void availableModelsNegativelyCachesAFailedListingCall() {
        ModelService models = mock(ModelService.class);
        when(models.list()).thenThrow(new RuntimeException("boom"));
        ClaudeProvider provider = new ClaudeProvider(new ObjectMapper(), keyCapturingFactory(clientWithModels(models)));

        assertThat(provider.availableModels("sk-test")).isEmpty();
        assertThat(provider.availableModels("sk-test")).isEmpty();
        verify(models, times(1)).list();
    }

    @Test
    void availableModelsReturnsEmptyListRatherThanThrowingOnListingException() {
        ModelService models = mock(ModelService.class);
        when(models.list()).thenThrow(new RuntimeException("boom"));
        ClaudeProvider provider = new ClaudeProvider(new ObjectMapper(), keyCapturingFactory(clientWithModels(models)));

        assertThat(provider.availableModels("sk-test")).isEmpty();
    }

    @Test
    void defaultModelAndCompleteBlankModelSubstitutionAreUnaffectedByDiscovery() {
        // availableModels is read-only per its javadoc -- confirm defaultModel() still returns the
        // static DEFAULT_MODEL regardless of what discovery would report.
        ClaudeProvider provider = new ClaudeProvider(new ObjectMapper());
        assertThat(provider.defaultModel()).isEqualTo(ClaudeProvider.DEFAULT_MODEL);
    }

    @Test
    void completeWithBlankModelStillResolvesToDefaultModelEvenWhenDiscoveryReportsADifferentNewest() {
        // The real invariant defaultModelAndCompleteBlankModelSubstitutionAreUnaffectedByDiscovery above
        // only pins a getter -- it would still pass if availableModels() were deleted from ClaudeProvider
        // entirely. Prove the actual behavior instead: stub discovery to report a newer model than
        // DEFAULT_MODEL, then confirm complete()'s blank-model substitution ignores it.
        ModelListPage page = pageOf(modelInfo("claude-opus-5-1", OffsetDateTime.now())); // see note above
        ModelService models = mock(ModelService.class);
        when(models.list()).thenReturn(page);

        MessageService messages = mock(MessageService.class);
        when(messages.create(any(MessageCreateParams.class))).thenReturn(mock(Message.class));
        AnthropicClient client = mock(AnthropicClient.class);
        when(client.models()).thenReturn(models);
        when(client.messages()).thenReturn(messages);

        ClaudeProvider provider = new ClaudeProvider(new ObjectMapper(), keyCapturingFactory(client));

        // Confirm discovery really does report the different model, so the assertion below is proof of
        // complete() ignoring it, not proof the stub silently didn't apply.
        assertThat(provider.availableModels("sk-test")).extracting(ModelInfo::id).contains("claude-opus-5-1");

        provider.complete(new ChatRequest(null, null, List.of(ChatMessage.user("hi")), List.of(), null, null),
                "sk-test");

        ArgumentCaptor<MessageCreateParams> captor = ArgumentCaptor.forClass(MessageCreateParams.class);
        verify(messages).create(captor.capture());
        assertThat(captor.getValue().model().asString()).isEqualTo(ClaudeProvider.DEFAULT_MODEL);
    }

    // ---- helpers ----

    private AnthropicClient clientWithModels(ModelService modelService) {
        AnthropicClient client = mock(AnthropicClient.class);
        when(client.models()).thenReturn(modelService);
        return client;
    }

    private Function<String, AnthropicClient> keyCapturingFactory(AnthropicClient client) {
        return apiKey -> {
            assertThat(apiKey).isEqualTo("sk-test");
            return client;
        };
    }

    /**
     * Production code now drives discovery through {@code page.autoPager().stream()} (see
     * {@link ClaudeProvider#listModels}), not {@code page.data()} directly -- see
     * {@code OpenAiProviderTest#pageOf}'s javadoc for why {@code items()}/{@code hasNextPage()}/
     * {@code autoPager()} must all be stubbed on the mocked page instead.
     */
    private ModelListPage pageOf(com.anthropic.models.models.ModelInfo... models) {
        ModelListPage page = mock(ModelListPage.class);
        when(page.items()).thenReturn(List.of(models));
        when(page.hasNextPage()).thenReturn(false);
        when(page.autoPager()).thenReturn(AutoPager.Companion.from(page));
        return page;
    }

    private com.anthropic.models.models.ModelInfo modelInfo(String id, OffsetDateTime createdAt) {
        return com.anthropic.models.models.ModelInfo.builder()
                .id(id)
                .createdAt(createdAt)
                .displayName(id)
                .capabilities(java.util.Optional.empty())
                .maxInputTokens(java.util.Optional.empty())
                .maxTokens(java.util.Optional.empty())
                .build();
    }
}
