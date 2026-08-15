package com.conductor.memory;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.agent.provider.ChatModelProvider;
import com.conductor.agent.provider.ChatRequest;
import com.conductor.agent.provider.ChatResponse;
import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.agent.provider.TokenUsage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (no Spring/Docker): {@link MemoryExtractionService#parseCandidates} is exercised
 * directly (it's a static, mock-free method), and {@link MemoryExtractionService#onTurnCompleted} is
 * exercised against Mockito mocks of its collaborators, using a same-thread {@link ExecutorService} so
 * the "submitted job" runs synchronously and its effects (or lack of them) can be asserted immediately.
 */
class MemoryExtractionServiceTest {

    /** Runs a submitted job inline on the calling thread -- makes the fire-and-forget extraction job
     *  deterministic for tests that need to observe its effects. */
    private static final ExecutorService DIRECT_EXECUTOR = new AbstractExecutorService() {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    };

    // ---- parseCandidates ----

    @Test
    void parseCandidates_cleanArray_parsesAllFields() {
        String response = "[{\"content\": \"The team prefers async standups.\", \"type\": \"preference\", "
                + "\"importance\": 7}]";

        List<MemoryExtractionService.Candidate> result = MemoryExtractionService.parseCandidates(response);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).isEqualTo("The team prefers async standups.");
        assertThat(result.get(0).type()).isEqualTo(MemoryType.PREFERENCE);
        assertThat(result.get(0).importance()).isEqualTo(7);
    }

    @Test
    void parseCandidates_arrayWrappedInProseAndFences_stillParses() {
        String response = "Sure, here's the extraction:\n```json\n[{\"content\": \"Deploys use Cloud Run.\", "
                + "\"type\": \"fact\", \"importance\": 4}]\n```\nLet me know if you need more.";

        List<MemoryExtractionService.Candidate> result = MemoryExtractionService.parseCandidates(response);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).isEqualTo("Deploys use Cloud Run.");
    }

    @Test
    void parseCandidates_garbage_returnsEmpty() {
        assertThat(MemoryExtractionService.parseCandidates("not json at all")).isEmpty();
        assertThat(MemoryExtractionService.parseCandidates("[{\"content\": broken")).isEmpty();
        assertThat(MemoryExtractionService.parseCandidates(null)).isEmpty();
        assertThat(MemoryExtractionService.parseCandidates("   ")).isEmpty();
        assertThat(MemoryExtractionService.parseCandidates("[]")).isEmpty();
    }

    @Test
    void parseCandidates_moreThanFiveElements_capsAtFive() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < 8; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"content\": \"fact number ").append(i).append("\", \"type\": \"fact\", \"importance\": 5}");
        }
        sb.append("]");

        List<MemoryExtractionService.Candidate> result = MemoryExtractionService.parseCandidates(sb.toString());

        assertThat(result).hasSize(5);
        assertThat(result.get(0).content()).isEqualTo("fact number 0");
        assertThat(result.get(4).content()).isEqualTo("fact number 4");
    }

    @Test
    void parseCandidates_unrecognizedType_fallsBackToFact() {
        String response = "[{\"content\": \"Something happened.\", \"type\": \"nonsense\", \"importance\": 5}]";

        List<MemoryExtractionService.Candidate> result = MemoryExtractionService.parseCandidates(response);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).type()).isEqualTo(MemoryType.FACT);
    }

    @Test
    void parseCandidates_missingType_fallsBackToFact() {
        String response = "[{\"content\": \"Something happened.\", \"importance\": 5}]";

        List<MemoryExtractionService.Candidate> result = MemoryExtractionService.parseCandidates(response);

        assertThat(result.get(0).type()).isEqualTo(MemoryType.FACT);
    }

    @Test
    void parseCandidates_importanceOutOfRange_clampsToBounds() {
        String response = "[{\"content\": \"Too important.\", \"type\": \"fact\", \"importance\": 99}, "
                + "{\"content\": \"Not important.\", \"type\": \"fact\", \"importance\": -3}]";

        List<MemoryExtractionService.Candidate> result = MemoryExtractionService.parseCandidates(response);

        assertThat(result.get(0).importance()).isEqualTo(10);
        assertThat(result.get(1).importance()).isEqualTo(1);
    }

    @Test
    void parseCandidates_missingImportance_defaultsToFive() {
        String response = "[{\"content\": \"No importance given.\", \"type\": \"fact\"}]";

        List<MemoryExtractionService.Candidate> result = MemoryExtractionService.parseCandidates(response);

        assertThat(result.get(0).importance()).isEqualTo(5);
    }

    @Test
    void parseCandidates_longContent_hardCappedAt500Chars() {
        String longContent = "x".repeat(800);
        String response = "[{\"content\": \"" + longContent + "\", \"type\": \"fact\", \"importance\": 5}]";

        List<MemoryExtractionService.Candidate> result = MemoryExtractionService.parseCandidates(response);

        assertThat(result.get(0).content()).hasSize(500);
    }

    @Test
    void parseCandidates_blankContent_dropsElement() {
        String response = "[{\"content\": \"   \", \"type\": \"fact\", \"importance\": 5}, "
                + "{\"content\": \"kept\", \"type\": \"fact\", \"importance\": 5}]";

        List<MemoryExtractionService.Candidate> result = MemoryExtractionService.parseCandidates(response);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).isEqualTo("kept");
    }

    // ---- onTurnCompleted ----

    private AgentRepository agentRepository;
    private ModelProviderRegistry providerRegistry;
    private ProviderCredentialService credentialService;
    private MemoryService memoryService;
    private ChatModelProvider chatModelProvider;

    private MemoryExtractionService service(ExecutorService executor, boolean memoryEnabled, boolean extractionEnabled) {
        agentRepository = mock(AgentRepository.class);
        providerRegistry = mock(ModelProviderRegistry.class);
        credentialService = mock(ProviderCredentialService.class);
        memoryService = mock(MemoryService.class);
        chatModelProvider = mock(ChatModelProvider.class);
        return new MemoryExtractionService(agentRepository, providerRegistry, credentialService, memoryService,
                executor, memoryEnabled, extractionEnabled);
    }

    private Agent agentWithProvider(String provider) {
        Agent agent = new Agent();
        agent.setId("agent-1");
        agent.setProvider(provider);
        agent.setModel("fake-model");
        return agent;
    }

    private static final String LONG_USER = "u".repeat(150);
    private static final String LONG_ASSISTANT = "a".repeat(150);

    @Test
    void onTurnCompleted_extractionFlagOff_neverTouchesExecutor() {
        ExecutorService spyExecutor = mock(ExecutorService.class);
        MemoryExtractionService service = service(spyExecutor, true, false);

        service.onTurnCompleted("p1", "agent-1", "conv-1", LONG_USER, LONG_ASSISTANT);

        verifyNoInteractions(spyExecutor);
        verifyNoInteractions(agentRepository);
    }

    @Test
    void onTurnCompleted_memoryFlagOff_neverTouchesExecutor() {
        ExecutorService spyExecutor = mock(ExecutorService.class);
        MemoryExtractionService service = service(spyExecutor, false, true);

        service.onTurnCompleted("p1", "agent-1", "conv-1", LONG_USER, LONG_ASSISTANT);

        verifyNoInteractions(spyExecutor);
    }

    @Test
    void onTurnCompleted_shortTurn_skipsWithoutSubmitting() {
        ExecutorService spyExecutor = mock(ExecutorService.class);
        MemoryExtractionService service = service(spyExecutor, true, true);

        service.onTurnCompleted("p1", "agent-1", "conv-1", "hi", "hello");

        verifyNoInteractions(spyExecutor);
        verifyNoInteractions(agentRepository);
    }

    @Test
    void onTurnCompleted_missingCredential_doesNotCreateMemory() {
        MemoryExtractionService service = service(DIRECT_EXECUTOR, true, true);
        when(agentRepository.findById("agent-1")).thenReturn(Optional.of(agentWithProvider("fake")));
        when(providerRegistry.findById("fake")).thenReturn(Optional.of(chatModelProvider));
        when(credentialService.resolveApiKey("p1", "fake")).thenReturn(Optional.empty());

        service.onTurnCompleted("p1", "agent-1", "conv-1", LONG_USER, LONG_ASSISTANT);

        verifyNoInteractions(chatModelProvider);
        verify(memoryService, never()).createRaw(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void onTurnCompleted_unknownProvider_doesNotCreateMemory() {
        MemoryExtractionService service = service(DIRECT_EXECUTOR, true, true);
        when(agentRepository.findById("agent-1")).thenReturn(Optional.of(agentWithProvider("mystery")));
        when(providerRegistry.findById("mystery")).thenReturn(Optional.empty());

        service.onTurnCompleted("p1", "agent-1", "conv-1", LONG_USER, LONG_ASSISTANT);

        verify(memoryService, never()).createRaw(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void onTurnCompleted_agentNotFound_doesNotCreateMemory() {
        MemoryExtractionService service = service(DIRECT_EXECUTOR, true, true);
        when(agentRepository.findById("agent-1")).thenReturn(Optional.empty());

        service.onTurnCompleted("p1", "agent-1", "conv-1", LONG_USER, LONG_ASSISTANT);

        verifyNoInteractions(providerRegistry, credentialService, memoryService);
    }

    @Test
    void onTurnCompleted_happyPath_createsRawMemoryForEachCandidate() {
        MemoryExtractionService service = service(DIRECT_EXECUTOR, true, true);
        when(agentRepository.findById("agent-1")).thenReturn(Optional.of(agentWithProvider("fake")));
        when(providerRegistry.findById("fake")).thenReturn(Optional.of(chatModelProvider));
        when(credentialService.resolveApiKey("p1", "fake")).thenReturn(Optional.of("sk-test"));
        when(chatModelProvider.complete(any(ChatRequest.class), eq("sk-test"))).thenReturn(
                new ChatResponse(ChatResponse.StopReason.COMPLETE,
                        "[{\"content\": \"The user prefers dark mode.\", \"type\": \"preference\", \"importance\": 6}]",
                        List.of(), TokenUsage.ZERO));

        service.onTurnCompleted("p1", "agent-1", "conv-1", LONG_USER, LONG_ASSISTANT);

        verify(memoryService).createRaw("p1", "agent-1", "conv-1",
                "The user prefers dark mode.", MemoryType.PREFERENCE, 6);
    }

    @Test
    void onTurnCompleted_modelReturnsNoCandidates_createsNothing() {
        MemoryExtractionService service = service(DIRECT_EXECUTOR, true, true);
        when(agentRepository.findById("agent-1")).thenReturn(Optional.of(agentWithProvider("fake")));
        when(providerRegistry.findById("fake")).thenReturn(Optional.of(chatModelProvider));
        when(credentialService.resolveApiKey("p1", "fake")).thenReturn(Optional.of("sk-test"));
        when(chatModelProvider.complete(any(ChatRequest.class), eq("sk-test"))).thenReturn(
                new ChatResponse(ChatResponse.StopReason.COMPLETE, "[]", List.of(), TokenUsage.ZERO));

        service.onTurnCompleted("p1", "agent-1", "conv-1", LONG_USER, LONG_ASSISTANT);

        verify(memoryService, never()).createRaw(any(), any(), any(), any(), any(), anyInt());
    }

    @Test
    void onTurnCompleted_providerThrows_doesNotPropagate() {
        MemoryExtractionService service = service(DIRECT_EXECUTOR, true, true);
        when(agentRepository.findById("agent-1")).thenReturn(Optional.of(agentWithProvider("fake")));
        when(providerRegistry.findById("fake")).thenReturn(Optional.of(chatModelProvider));
        when(credentialService.resolveApiKey("p1", "fake")).thenReturn(Optional.of("sk-test"));
        when(chatModelProvider.complete(any(ChatRequest.class), anyString()))
                .thenThrow(new RuntimeException("provider unreachable"));

        // Must not throw out of onTurnCompleted -- the job runs inline on DIRECT_EXECUTOR.
        service.onTurnCompleted("p1", "agent-1", "conv-1", LONG_USER, LONG_ASSISTANT);

        verify(memoryService, never()).createRaw(any(), any(), any(), any(), any(), anyInt());
    }
}
