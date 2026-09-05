package com.conductor.agent.tool.coordinator;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentService;
import com.conductor.agent.provider.TokenUsage;
import com.conductor.agent.run.AgentExecutionService;
import com.conductor.agent.run.AgentRun;
import com.conductor.agent.run.AgentRunResult;
import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.ToolInvocationContext;
import com.conductor.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pure unit test (no Spring) for {@link DelegationCoordinatorTools}: pins {@code ask_agent}'s exact
 *  output JSON shape and refusal semantics against the pre-refactor {@code CoordinatorToolProviderTest}
 *  behavior, and pins that it now composes {@link AgentService#list} rather than {@code
 *  AgentRepository} directly. */
class DelegationCoordinatorToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolInvocationContext CTX = new ToolInvocationContext("p1", "agent-1", "run-1");

    private AgentService agentService;
    private AgentExecutionService agentExecutionService;
    private DelegationCoordinatorTools tools;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        agentExecutionService = mock(AgentExecutionService.class);
        tools = new DelegationCoordinatorTools(agentService, agentExecutionService, MAPPER);
    }

    private AgentTool tool(String bareName) {
        return tools.tools().stream()
                .filter(t -> t.name().equals(bareName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No tool named " + bareName));
    }

    private Agent agent(String id, String slug, String name, String state, boolean addressable) {
        Agent a = new Agent();
        a.setId(id);
        a.setProjectId("p1");
        a.setSlug(slug);
        a.setName(name);
        a.setState(state);
        a.setProvider("fake");
        a.setConfigJson(addressable ? "{\"addressable\":true}" : "{}");
        a.setToolIds("[]");
        return a;
    }

    @Test
    void offersExactlyAskAgent() {
        assertThat(tools.tools()).extracting(AgentTool::name).containsExactly("ask_agent");
    }

    @Test
    void askAgentHappyPathReturnsPinnedShape() throws Exception {
        Agent target = agent("a2", "researcher", "Researcher", "ACTIVE", false);
        when(agentService.list("p1")).thenReturn(List.of(target));
        when(agentExecutionService.run(eq("p1"), eq("a2"), eq("What's up?"), any(), eq(null)))
                .thenReturn(new AgentRunResult("run-5", "All good.", null, TokenUsage.ZERO,
                        AgentRun.Status.SUCCEEDED.name()));

        ToolResult result = tool("ask_agent").invoke(Map.of("agent", "researcher", "task", "What's up?"), CTX);

        assertThat(result.ok()).isTrue();
        Map<String, Object> row = MAPPER.readValue(result.payload(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(row.keySet()).containsExactlyInAnyOrder("runId", "output");
        assertThat(row.get("output")).isEqualTo("All good.");
        assertThat(row.get("runId")).isEqualTo("run-5");
    }

    @Test
    void askAgentRefusesTargetingItself() {
        Agent self = agent("agent-1", "self-slug", "Self", "ACTIVE", false);
        when(agentService.list("p1")).thenReturn(List.of(self));

        ToolResult result = tool("ask_agent").invoke(Map.of("agent", "self-slug", "task", "x"), CTX);

        assertThat(result.ok()).isFalse();
        assertThat(result.payload()).contains("itself");
    }

    @Test
    void askAgentRefusesCoordinatorChainTarget() {
        Agent chainable = agent("a3", "chainer", "Chainer", "ACTIVE", false);
        chainable.setToolIds("[\"coordinator:ask_agent\"]");
        when(agentService.list("p1")).thenReturn(List.of(chainable));

        ToolResult result = tool("ask_agent").invoke(Map.of("agent", "chainer", "task", "x"), CTX);

        assertThat(result.ok()).isFalse();
        assertThat(result.payload()).contains("coordinator chains");
    }

    @Test
    void askAgentIgnoresNonActiveAgents() {
        Agent draft = agent("a4", "researcher", "Researcher", "DRAFT", false);
        when(agentService.list("p1")).thenReturn(List.of(draft));

        ToolResult result = tool("ask_agent").invoke(Map.of("agent", "researcher", "task", "x"), CTX);

        assertThat(result.ok()).isFalse();
    }

    @Test
    void askAgentTargetNotFoundBecomesToolError() {
        when(agentService.list("p1")).thenReturn(List.of());

        ToolResult result = tool("ask_agent").invoke(Map.of("agent", "nonexistent", "task", "x"), CTX);

        assertThat(result.ok()).isFalse();
    }

    @Test
    void askAgentFailedRunResultBecomesToolErrorCarryingRunId() {
        Agent target = agent("a2", "researcher", "Researcher", "ACTIVE", false);
        when(agentService.list("p1")).thenReturn(List.of(target));
        when(agentExecutionService.run(eq("p1"), eq("a2"), anyString(), any(), eq(null)))
                .thenReturn(new AgentRunResult("run-6", "", null, TokenUsage.ZERO, AgentRun.Status.FAILED.name()));

        ToolResult result = tool("ask_agent").invoke(Map.of("agent", "researcher", "task", "x"), CTX);

        assertThat(result.ok()).isFalse();
        assertThat(result.payload()).contains("run-6");
    }

    @Test
    void askAgentMissingArgsReturnsError() {
        ToolResult result = tool("ask_agent").invoke(Map.of("agent", "researcher"), CTX);

        assertThat(result.ok()).isFalse();
    }
}
