package com.conductor.agent.run;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.agent.provider.ChatModelProvider;
import com.conductor.agent.provider.ChatRequest;
import com.conductor.agent.provider.ChatResponse;
import com.conductor.agent.provider.ModelProviderRegistry;
import com.conductor.agent.provider.TokenUsage;
import com.conductor.agent.provider.ToolDef;
import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.AgentToolProvider;
import com.conductor.agent.tool.AgentToolRegistry;
import com.conductor.agent.tool.ToolInvocationContext;
import com.conductor.agent.tool.ToolResult;
import com.conductor.service.LogRedactionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Tools granted to one run (the workflow step's {@code with.grant_tools}) reach the model for that run only. */
class AgentExecutionServiceGrantedToolsTest {

    private record Tool(String id, String name, String description) implements AgentTool {
        public Map<String, Object> inputSchema() { return Map.of("type", "object"); }
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            return ToolResult.ok("x");
        }
    }

    private static final class Capturing implements ChatModelProvider {
        final List<ChatRequest> requests = new ArrayList<>();
        public String id() { return "fake"; }
        public ChatResponse complete(ChatRequest request, String apiKey) {
            requests.add(request);
            return new ChatResponse(ChatResponse.StopReason.COMPLETE, "done", List.of(), new TokenUsage(1, 1));
        }
    }

    private final Agent agent = agentWithTools("[\"fake:own\"]");
    private final Capturing provider = new Capturing();

    private static Agent agentWithTools(String toolIds) {
        Agent a = new Agent();
        a.setId("agent-1");
        a.setProjectId("p1");
        a.setSlug("helper");
        a.setProvider("fake");
        a.setSystemPrompt("sys");
        a.setConfigJson("{}");
        a.setToolIds(toolIds);
        return a;
    }

    private AgentExecutionService service(Tool... offered) {
        AgentToolProvider tools = new AgentToolProvider() {
            public String sourceId() { return "fake"; }
            public List<AgentTool> available(String projectId) { return List.of(offered); }
            public Optional<AgentTool> resolve(String projectId, String toolId) {
                return List.of(offered).stream().filter(t -> t.id().equals(toolId)).map(t -> (AgentTool) t).findFirst();
            }
        };
        AgentToolRegistry registry = new AgentToolRegistry(List.of(tools));
        registry.init();
        ModelProviderRegistry providers = new ModelProviderRegistry(List.of(provider));
        providers.init();

        AgentRepository agentRepo = mock(AgentRepository.class);
        when(agentRepo.findByProjectIdAndSlug("p1", "helper")).thenReturn(Optional.of(agent));
        AgentRunRepository runRepo = mock(AgentRunRepository.class);
        when(runRepo.save(any(AgentRun.class))).thenAnswer(inv -> inv.getArgument(0));
        ProviderCredentialService credentials = mock(ProviderCredentialService.class);
        when(credentials.resolveApiKey("p1", "fake")).thenReturn(Optional.of("sk"));
        LogRedactionService redaction = mock(LogRedactionService.class);
        when(redaction.redact(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        return new AgentExecutionService(agentRepo, providers, credentials, registry, runRepo, redaction,
                new ObjectMapper());
    }

    private List<String> toolNames() {
        return provider.requests.get(0).tools().stream().map(ToolDef::name).toList();
    }

    @Test
    void grantedToolsAreOfferedAlongsideTheAgentsOwnAndTheAgentIsUnchanged() {
        AgentExecutionService svc = service(new Tool("fake:own", "own_tool", "own"),
                new Tool("fake:extra", "extra_tool", "extra"));

        svc.run("p1", "helper", "task", Map.of(), null, Set.of("fake:extra"));

        assertThat(toolNames()).containsExactly("own_tool", "extra_tool");
        assertThat(agent.getToolIds()).isEqualTo("[\"fake:own\"]");
    }

    @Test
    void withoutGrantsOnlyTheAgentsOwnToolsAreOffered() {
        AgentExecutionService svc = service(new Tool("fake:own", "own_tool", "own"),
                new Tool("fake:extra", "extra_tool", "extra"));

        svc.run("p1", "helper", "task", Map.of(), null);

        assertThat(toolNames()).containsExactly("own_tool");
    }

    @Test
    void aGrantedToolReplacesAnAgentToolWithTheSameModelFacingName() {
        AgentExecutionService svc = service(new Tool("fake:own", "read_doc", "the agent's own"),
                new Tool("fake:granted", "read_doc", "the granted one"));

        svc.run("p1", "helper", "task", Map.of(), null, Set.of("fake:granted"));

        assertThat(provider.requests.get(0).tools()).hasSize(1);
        assertThat(provider.requests.get(0).tools().get(0).description()).isEqualTo("the granted one");
    }

    @Test
    void aGrantTheProjectCannotResolveIsSimplyNotOffered() {
        AgentExecutionService svc = service(new Tool("fake:own", "own_tool", "own"));

        svc.run("p1", "helper", "task", Map.of(), null, Set.of("fake:ghost"));

        assertThat(toolNames()).containsExactly("own_tool");
    }
}
