package com.conductor.agent.tool.coordinator;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentService;
import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.ToolInvocationContext;
import com.conductor.agent.tool.ToolResult;
import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.repository.ProjectRepository;
import com.conductor.service.ProjectActor;
import com.conductor.service.WorkItemService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pure unit test (no Spring) for {@link WorkItemCoordinatorTools}: pins each tool's exact output JSON
 *  shape against the pre-refactor {@code CoordinatorToolProviderTest} behavior. */
class WorkItemCoordinatorToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolInvocationContext CTX = new ToolInvocationContext("p1", "agent-1", "run-1");

    private WorkItemService workItemService;
    private AgentService agentService;
    private ProjectRepository projectRepository;
    private WorkItemCoordinatorTools tools;

    @BeforeEach
    void setUp() {
        workItemService = mock(WorkItemService.class);
        agentService = mock(AgentService.class);
        projectRepository = mock(ProjectRepository.class);
        tools = new WorkItemCoordinatorTools(workItemService, agentService, projectRepository, MAPPER);

        when(projectRepository.findById("p1")).thenReturn(Optional.of(project("p1", "COND")));
    }

    private Project project(String id, String key) {
        Project p = new Project();
        p.setId(id);
        p.setKey(key);
        return p;
    }

    private AgentTool tool(String bareName) {
        return tools.tools().stream()
                .filter(t -> t.name().equals(bareName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No tool named " + bareName));
    }

    private WorkItem workItem(String id, int seq, String title, String status, String type) {
        WorkItem w = new WorkItem();
        w.setId(id);
        w.setSequenceNumber(seq);
        w.setTitle(title);
        w.setCurrentStatus(status);
        w.setType(type);
        return w;
    }

    private Agent agent(String id, String slug) {
        Agent a = new Agent();
        a.setId(id);
        a.setProjectId("p1");
        a.setSlug(slug);
        a.setName(slug);
        a.setState("ACTIVE");
        a.setProvider("fake");
        a.setConfigJson("{}");
        a.setToolIds("[]");
        return a;
    }

    // ---- offered tools ----

    @Test
    void offersExactlyTheThreeWorkItemTools() {
        assertThat(tools.tools()).extracting(AgentTool::name)
                .containsExactlyInAnyOrder("create_work_item", "list_work_items", "get_work_item");
        for (AgentTool t : tools.tools()) {
            assertThat(t.id()).isEqualTo("coordinator:" + t.name());
        }
    }

    // ---- create_work_item ----

    @Test
    void createWorkItemHappyPathAttributesToTheCallingAgentsSlugAndReturnsPinnedShape() throws Exception {
        when(agentService.get("p1", "agent-1")).thenReturn(agent("agent-1", "ceo"));
        WorkItem created = workItem("w9", 5, "New Title", "DRAFT", "BUG");
        created.setWorkflow("ENGINEERING");
        when(workItemService.createWorkItem(eq("p1"), eq("BUG"), eq("New Title"), eq("desc"), eq("ENGINEERING"),
                eq(ProjectActor.agent("Agent (ceo)"))))
                .thenReturn(created);

        ToolResult result = tool("create_work_item").invoke(
                Map.of("workflow", "ENGINEERING", "type", "BUG", "title", "New Title", "description", "desc"), CTX);

        assertThat(result.ok()).isTrue();
        Map<String, Object> row = MAPPER.readValue(result.payload(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(row.keySet()).containsExactlyInAnyOrder("id", "displayId", "title", "status", "type", "workflow");
        assertThat(row.get("id")).isEqualTo("w9");
        assertThat(row.get("displayId")).isEqualTo("COND-5");
        assertThat(row.get("workflow")).isEqualTo("ENGINEERING");
    }

    @Test
    void createWorkItemFallsBackToAgentIdWhenCallingAgentRowIsGone() throws Exception {
        when(agentService.get("p1", "agent-1")).thenThrow(new EntityNotFoundException("Agent not found: agent-1"));
        WorkItem created = workItem("w9", 1, "T", "DRAFT", "BUG");
        when(workItemService.createWorkItem(eq("p1"), eq("BUG"), eq("T"), any(), eq("ENGINEERING"),
                eq(ProjectActor.agent("Agent (agent-1)"))))
                .thenReturn(created);

        ToolResult result = tool("create_work_item").invoke(
                Map.of("workflow", "ENGINEERING", "type", "BUG", "title", "T"), CTX);

        assertThat(result.ok()).isTrue();
    }

    @Test
    void createWorkItemMissingRequiredFieldsReturnsError() {
        ToolResult result = tool("create_work_item").invoke(Map.of("type", "BUG"), CTX);

        assertThat(result.ok()).isFalse();
    }

    @Test
    void createWorkItemServiceExceptionBecomesToolError() {
        when(agentService.get("p1", "agent-1")).thenReturn(agent("agent-1", "ceo"));
        when(workItemService.createWorkItem(anyString(), anyString(), anyString(), any(), anyString(), any(ProjectActor.class)))
                .thenThrow(new RuntimeException("boom"));

        ToolResult result = tool("create_work_item").invoke(
                Map.of("workflow", "ENGINEERING", "type", "BUG", "title", "T"), CTX);

        assertThat(result.ok()).isFalse();
    }

    // ---- list_work_items ----

    @Test
    void listWorkItemsHappyPathReturnsCompactRowsFromTheLimitedQuery() throws Exception {
        when(workItemService.listWorkItemsForAgent("p1", null, null, null, 50))
                .thenReturn(List.of(workItem("w1", 1, "Fix bug", "OPEN", "BUG")));

        ToolResult result = tool("list_work_items").invoke(Map.of(), CTX);

        assertThat(result.ok()).isTrue();
        List<?> rows = MAPPER.readValue(result.payload(), List.class);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = (Map<String, Object>) rows.get(0);
        assertThat(row.keySet()).containsExactlyInAnyOrder("id", "displayId", "title", "status", "type");
        assertThat(row.get("id")).isEqualTo("w1");
        assertThat(row.get("displayId")).isEqualTo("COND-1");
        assertThat(row.get("title")).isEqualTo("Fix bug");
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(row.get("type")).isEqualTo("BUG");
    }

    @Test
    void listWorkItemsClampsAnOversizedLimitAndPassesItToTheQuery() throws Exception {
        // The cap is now enforced at the query level (WorkItemRepository#findByProjectFilteredLimited) --
        // the service call itself must receive the clamped 50, not the raw 200.
        when(workItemService.listWorkItemsForAgent("p1", null, null, null, 50))
                .thenReturn(List.of());

        tool("list_work_items").invoke(Map.of("limit", 200), CTX);

        org.mockito.Mockito.verify(workItemService).listWorkItemsForAgent("p1", null, null, null, 50);
    }

    @Test
    void listWorkItemsRepositoryExceptionBecomesToolError() {
        when(workItemService.listWorkItemsForAgent(anyString(), any(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        ToolResult result = tool("list_work_items").invoke(Map.of(), CTX);

        assertThat(result.ok()).isFalse();
        assertThat(result.payload()).contains("list_work_items failed");
    }

    // ---- get_work_item ----

    @Test
    void getWorkItemHappyPathReturnsPinnedShape() throws Exception {
        WorkItem w = workItem("w1", 7, "Title", "OPEN", "BUG");
        w.setDescription("desc");
        w.setWorkflow("ENGINEERING");
        when(workItemService.resolveByReference(eq("p1"), eq("w1"), any(ProjectActor.class))).thenReturn(w);

        ToolResult result = tool("get_work_item").invoke(Map.of("issueId", "w1"), CTX);

        assertThat(result.ok()).isTrue();
        Map<String, Object> row = MAPPER.readValue(result.payload(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(row.keySet()).containsExactlyInAnyOrder(
                "id", "displayId", "title", "description", "status", "type", "workflow");
        assertThat(row.get("displayId")).isEqualTo("COND-7");
        assertThat(row.get("description")).isEqualTo("desc");
        assertThat(row.get("workflow")).isEqualTo("ENGINEERING");
    }

    @Test
    void getWorkItemMissingIssueIdReturnsError() {
        ToolResult result = tool("get_work_item").invoke(Map.of(), CTX);

        assertThat(result.ok()).isFalse();
    }

    @Test
    void getWorkItemNotFoundReturnsError() {
        when(workItemService.resolveByReference(eq("p1"), eq("nope"), any(ProjectActor.class)))
                .thenThrow(new EntityNotFoundException("Work Item not found: nope"));

        ToolResult result = tool("get_work_item").invoke(Map.of("issueId", "nope"), CTX);

        assertThat(result.ok()).isFalse();
    }
}
