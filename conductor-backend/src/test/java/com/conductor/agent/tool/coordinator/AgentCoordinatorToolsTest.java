package com.conductor.agent.tool.coordinator;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentService;
import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.ToolInvocationContext;
import com.conductor.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pure unit test (no Spring) for {@link AgentCoordinatorTools}: pins {@code list_agents}'s exact
 *  output JSON shape against the pre-refactor {@code CoordinatorToolProviderTest} behavior, and pins
 *  that it now composes {@link AgentService#list} rather than {@code AgentRepository} directly. */
class AgentCoordinatorToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolInvocationContext CTX = new ToolInvocationContext("p1", "agent-1", "run-1");

    private AgentService agentService;
    private AgentCoordinatorTools tools;

    @BeforeEach
    void setUp() {
        agentService = mock(AgentService.class);
        tools = new AgentCoordinatorTools(agentService, MAPPER);
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
    void offersExactlyListAgents() {
        assertThat(tools.tools()).extracting(AgentTool::name).containsExactly("list_agents");
    }

    @Test
    void listAgentsHappyPathReturnsPinnedShape() throws Exception {
        when(agentService.list("p1")).thenReturn(List.of(agent("a1", "ceo", "CEO", "ACTIVE", true)));

        ToolResult result = tool("list_agents").invoke(Map.of(), CTX);

        List<?> rows = MAPPER.readValue(result.payload(), List.class);
        Map<String, Object> row = (Map<String, Object>) rows.get(0);
        assertThat(row.keySet()).containsExactlyInAnyOrder("slug", "name", "description", "state", "addressable");
        assertThat(row.get("slug")).isEqualTo("ceo");
        assertThat(row.get("addressable")).isEqualTo(true);
    }

    @Test
    void listAgentsServiceExceptionBecomesToolError() {
        when(agentService.list(anyString())).thenThrow(new RuntimeException("boom"));

        ToolResult result = tool("list_agents").invoke(Map.of(), CTX);

        assertThat(result.ok()).isFalse();
    }
}
