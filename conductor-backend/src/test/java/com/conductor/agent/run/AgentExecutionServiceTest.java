package com.conductor.agent.run;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.agent.provider.ChatMessage;
import com.conductor.agent.provider.ChatModelProvider;
import com.conductor.agent.provider.ChatRequest;
import com.conductor.agent.provider.ChatResponse;
import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.agent.provider.TokenUsage;
import com.conductor.agent.provider.ToolCall;
import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.AgentToolProvider;
import com.conductor.agent.tool.AgentToolRegistry;
import com.conductor.agent.tool.ToolInvocationContext;
import com.conductor.agent.tool.ToolResult;
import com.conductor.service.LogRedactionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (no Spring/Docker) for the ReAct loop: a fake {@link ChatModelProvider} that requests
 * a tool then finalizes, plus a fake {@link AgentTool}. Asserts the loop executes the tool, feeds the
 * result back, returns the final text + summed usage, and respects {@code maxToolTurns}.
 */
class AgentExecutionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Records each invocation so the test can assert the tool was actually called. */
    private static final class RecordingTool implements AgentTool {
        final AtomicInteger calls = new AtomicInteger();

        public String id() { return "fake:t1"; }
        public String name() { return "fake_tool"; }
        public String description() { return "A fake tool."; }
        public Map<String, Object> inputSchema() { return Map.of("type", "object"); }

        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            calls.incrementAndGet();
            return ToolResult.ok("TOOL_OUTPUT");
        }
    }

    /** Records every {@link ChatRequest} it's asked to complete, always finalizing immediately. */
    private static final class CapturingProvider implements ChatModelProvider {
        final List<ChatRequest> requests = new ArrayList<>();

        public String id() { return "fake"; }

        public ChatResponse complete(ChatRequest request, String apiKey) {
            // The runner reuses one mutable transcript list across turns, so snapshot messages() here --
            // otherwise later mutations (e.g. the finalizing assistant turn) would leak into this capture.
            requests.add(new ChatRequest(request.model(), request.systemPrompt(), List.copyOf(request.messages()),
                    request.tools(), request.maxTokens(), request.temperature()));
            return new ChatResponse(ChatResponse.StopReason.COMPLETE, "Final answer",
                    List.of(), new TokenUsage(3, 2));
        }
    }

    private AgentToolRegistry registryFor(AgentTool tool) {
        AgentToolProvider provider = new AgentToolProvider() {
            public String sourceId() { return "fake"; }
            public List<AgentTool> available(String projectId) { return List.of(tool); }
            public Optional<AgentTool> resolve(String projectId, String toolId) {
                return tool.id().equals(toolId) ? Optional.of(tool) : Optional.empty();
            }
        };
        AgentToolRegistry r = new AgentToolRegistry(List.of(provider));
        r.init();
        return r;
    }

    private ModelProviderRegistry registryFor(ChatModelProvider provider) {
        ModelProviderRegistry r = new ModelProviderRegistry(List.of(provider));
        r.init();
        return r;
    }

    private Agent agent(String configJson) {
        Agent agent = new Agent();
        agent.setId("agent-1");
        agent.setProjectId("p1");
        agent.setProvider("fake");
        agent.setSystemPrompt("You are a test agent.");
        agent.setConfigJson(configJson);
        agent.setToolIds("[\"fake:t1\"]");
        return agent;
    }

    private AgentExecutionService serviceWith(Agent agent, ChatModelProvider provider, AgentTool tool) {
        AgentRepository agentRepo = mock(AgentRepository.class);
        when(agentRepo.findById("agent-1")).thenReturn(Optional.of(agent));

        AgentRunRepository runRepo = mock(AgentRunRepository.class);
        when(runRepo.save(any(AgentRun.class))).thenAnswer(inv -> inv.getArgument(0));

        ProviderCredentialService credentials = mock(ProviderCredentialService.class);
        when(credentials.resolveApiKey("p1", "fake")).thenReturn(Optional.of("sk-test"));

        LogRedactionService redaction = mock(LogRedactionService.class);
        when(redaction.redact(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        return new AgentExecutionService(agentRepo, registryFor(provider), credentials,
                registryFor(tool), runRepo, redaction, MAPPER);
    }

    @Test
    void executesToolThenReturnsFinalTextWithSummedUsage() {
        RecordingTool tool = new RecordingTool();
        AtomicInteger turns = new AtomicInteger();
        ChatModelProvider provider = new ChatModelProvider() {
            public String id() { return "fake"; }
            public ChatResponse complete(ChatRequest request, String apiKey) {
                if (turns.getAndIncrement() == 0) {
                    return new ChatResponse(ChatResponse.StopReason.TOOL_USE, "let me check",
                            List.of(new ToolCall("call-1", "fake_tool", "{}")), new TokenUsage(10, 5));
                }
                return new ChatResponse(ChatResponse.StopReason.COMPLETE, "Final answer",
                        List.of(), new TokenUsage(3, 2));
            }
        };

        AgentExecutionService service = serviceWith(agent("{}"), provider, tool);
        AgentRunResult result = service.run(new AgentRunRequest("agent-1", "Do the thing", Map.of(), null));

        assertThat(turns.get()).isEqualTo(2);                 // tool turn + final turn
        assertThat(tool.calls.get()).isEqualTo(1);            // tool actually executed
        assertThat(result.outputText()).isEqualTo("Final answer");
        assertThat(result.usage()).isEqualTo(new TokenUsage(13, 7)); // summed across turns
        assertThat(result.status()).isEqualTo(AgentRun.Status.SUCCEEDED.name());
    }

    @Test
    void respectsMaxToolTurnsWhenModelNeverFinishes() {
        RecordingTool tool = new RecordingTool();
        AtomicInteger turns = new AtomicInteger();
        ChatModelProvider provider = new ChatModelProvider() {
            public String id() { return "fake"; }
            public ChatResponse complete(ChatRequest request, String apiKey) {
                turns.incrementAndGet();
                return new ChatResponse(ChatResponse.StopReason.TOOL_USE, "again",
                        List.of(new ToolCall("call-x", "fake_tool", "{}")), new TokenUsage(1, 1));
            }
        };

        AgentExecutionService service = serviceWith(agent("{\"maxToolTurns\":2}"), provider, tool);
        AgentRunResult result = service.run(new AgentRunRequest("agent-1", "Loop forever", Map.of(), null));

        assertThat(turns.get()).isEqualTo(2);                 // hard cap honored, no runaway
        assertThat(tool.calls.get()).isEqualTo(2);
        assertThat(result.status()).isEqualTo(AgentRun.Status.FAILED.name());
        assertThat(result.usage()).isEqualTo(new TokenUsage(2, 2));
    }

    @Test
    void priorMessagesAppearInOrderBeforeTheFinalTaskMessage() {
        CapturingProvider provider = new CapturingProvider();
        AgentExecutionService service = serviceWith(agent("{}"), provider, new RecordingTool());

        List<ChatMessage> prior = List.of(
                ChatMessage.user("earlier question"),
                ChatMessage.assistant("earlier answer", List.of()));

        service.run(new AgentRunRequest("agent-1", "Do the thing", Map.of(), null), prior, null);

        List<ChatMessage> sent = provider.requests.get(0).messages();
        assertThat(sent).hasSize(3);
        assertThat(sent.get(0)).isEqualTo(prior.get(0));
        assertThat(sent.get(1)).isEqualTo(prior.get(1));
        assertThat(sent.get(2).role()).isEqualTo(ChatMessage.Role.USER);
        assertThat(sent.get(2).text()).contains("Do the thing");
    }

    @Test
    void systemPromptSuffixIsAppendedWhenPresentAndOmittedWhenAbsent() {
        CapturingProvider provider = new CapturingProvider();
        AgentExecutionService service = serviceWith(agent("{}"), provider, new RecordingTool());
        AgentRunRequest request = new AgentRunRequest("agent-1", "Do the thing", Map.of(), null);

        service.run(request, List.of(), "Extra instructions.");
        assertThat(provider.requests.get(0).systemPrompt())
                .isEqualTo("You are a test agent.\n\nExtra instructions.");

        service.run(request, List.of(), null);
        assertThat(provider.requests.get(1).systemPrompt()).isEqualTo("You are a test agent.");
    }

    @Test
    void singleShotRunMatchesTheThreeArgOverloadWithNoPriorMessagesOrSuffix() {
        CapturingProvider provider = new CapturingProvider();
        AgentExecutionService service = serviceWith(agent("{}"), provider, new RecordingTool());
        AgentRunRequest request = new AgentRunRequest("agent-1", "Do the thing", Map.of(), null);

        service.run(request);
        service.run(request, List.of(), null);

        assertThat(provider.requests.get(0)).isEqualTo(provider.requests.get(1));
    }

    // ---- maxTokens: unset stays null so each provider applies its own cap ----

    @Test
    void unsetMaxTokensInConfigIsPassedThroughAsNullNotAHardcodedDefault() {
        CapturingProvider provider = new CapturingProvider();
        AgentExecutionService service = serviceWith(agent("{}"), provider, new RecordingTool());

        service.run(new AgentRunRequest("agent-1", "Do the thing", Map.of(), null));

        assertThat(provider.requests.get(0).maxTokens()).isNull();
    }

    @Test
    void explicitMaxTokensInConfigFlowsThroughToTheRequest() {
        CapturingProvider provider = new CapturingProvider();
        AgentExecutionService service = serviceWith(agent("{\"maxTokens\":4096}"), provider, new RecordingTool());

        service.run(new AgentRunRequest("agent-1", "Do the thing", Map.of(), null));

        assertThat(provider.requests.get(0).maxTokens()).isEqualTo(4096);
    }

    // ---- MAX_TOKENS terminal turn: FAILED when blank, SUCCEEDED-with-warning when truncated but non-empty ----

    @Test
    void maxTokensStopReasonWithBlankTextFails() {
        ChatModelProvider provider = new ChatModelProvider() {
            public String id() { return "fake"; }
            public ChatResponse complete(ChatRequest request, String apiKey) {
                return new ChatResponse(ChatResponse.StopReason.MAX_TOKENS, "", List.of(), new TokenUsage(5, 5));
            }
        };
        AgentExecutionService service = serviceWith(agent("{}"), provider, new RecordingTool());

        AgentRunResult result = service.run(new AgentRunRequest("agent-1", "Do the thing", Map.of(), null));

        assertThat(result.status()).isEqualTo(AgentRun.Status.FAILED.name());
        assertThat(result.outputText()).isEmpty();
    }

    @Test
    void maxTokensStopReasonWithNonBlankTextSucceedsWithTruncationWarning() {
        ChatModelProvider provider = new ChatModelProvider() {
            public String id() { return "fake"; }
            public ChatResponse complete(ChatRequest request, String apiKey) {
                return new ChatResponse(ChatResponse.StopReason.MAX_TOKENS, "partial answer",
                        List.of(), new TokenUsage(5, 5));
            }
        };
        AgentExecutionService service = serviceWith(agent("{}"), provider, new RecordingTool());

        AgentRunResult result = service.run(new AgentRunRequest("agent-1", "Do the thing", Map.of(), null));

        assertThat(result.status()).isEqualTo(AgentRun.Status.SUCCEEDED.name());
        assertThat(result.outputText()).isEqualTo("partial answer");
    }

    @Test
    void priorMessagesOverTheCharCapThrows() {
        CapturingProvider provider = new CapturingProvider();
        AgentExecutionService service = serviceWith(agent("{}"), provider, new RecordingTool());
        List<ChatMessage> tooLong = List.of(ChatMessage.user("x".repeat(60_001)));

        assertThatThrownBy(() -> service.run(
                new AgentRunRequest("agent-1", "Do the thing", Map.of(), null), tooLong, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- missing API key: the failure reason must name the fix and any already-configured alternative ----

    /** Runs the agent with no credential for its provider and returns the persisted run's errorReason. */
    private String runWithNoCredentialAndCaptureErrorReason(
            List<ProviderCredentialService.ProviderCredentialStatusView> statuses) {
        Agent agent = agent("{}");
        AgentRepository agentRepo = mock(AgentRepository.class);
        when(agentRepo.findById("agent-1")).thenReturn(Optional.of(agent));

        ArgumentCaptor<AgentRun> savedRun = ArgumentCaptor.forClass(AgentRun.class);
        AgentRunRepository runRepo = mock(AgentRunRepository.class);
        when(runRepo.save(savedRun.capture())).thenAnswer(inv -> inv.getArgument(0));

        ProviderCredentialService credentials = mock(ProviderCredentialService.class);
        when(credentials.resolveApiKey("p1", "fake")).thenReturn(Optional.empty());
        when(credentials.listStatuses("p1")).thenReturn(statuses);

        LogRedactionService redaction = mock(LogRedactionService.class);
        when(redaction.redact(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        AgentExecutionService service = new AgentExecutionService(agentRepo, registryFor(new CapturingProvider()),
                credentials, registryFor(new RecordingTool()), runRepo, redaction, MAPPER);

        AgentRunResult result = service.run(new AgentRunRequest("agent-1", "Do the thing", Map.of(), null));
        assertThat(result.status()).isEqualTo(AgentRun.Status.FAILED.name());

        List<AgentRun> saved = savedRun.getAllValues();
        return saved.get(saved.size() - 1).getErrorReason();
    }

    @Test
    void missingApiKeyMessageNamesTheFixWhenNoOtherProviderIsConfigured() {
        String reason = runWithNoCredentialAndCaptureErrorReason(
                List.of(new ProviderCredentialService.ProviderCredentialStatusView("fake", false)));

        assertThat(reason).isEqualTo("No API key configured for provider 'fake' in this project — add one "
                + "under Settings → AI Providers.");
    }

    @Test
    void missingApiKeyMessageNamesOtherConfiguredModelProvidersButNotNonModelOnes() {
        // "claude-code" is a NON_MODEL_PROVIDERS credential id, not a selectable agent model provider --
        // suggesting it here would tell the operator to do something impossible.
        String reason = runWithNoCredentialAndCaptureErrorReason(List.of(
                new ProviderCredentialService.ProviderCredentialStatusView("fake", false),
                new ProviderCredentialService.ProviderCredentialStatusView("claude", true),
                new ProviderCredentialService.ProviderCredentialStatusView("claude-code", true)));

        assertThat(reason)
                .contains("No API key configured for provider 'fake'")
                .contains("Settings → AI Providers")
                .contains("This project has a key configured for: claude.")
                .doesNotContain("claude-code");
    }
}
