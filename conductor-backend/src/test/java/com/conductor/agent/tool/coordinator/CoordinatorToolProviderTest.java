package com.conductor.agent.tool.coordinator;

import com.conductor.agent.AgentService;
import com.conductor.agent.run.AgentExecutionService;
import com.conductor.agent.tool.AgentTool;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.service.ProjectDocService;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkflowService;
import com.conductor.workflow.WorkflowRunQueryService;
import com.conductor.workflow.WorkflowTriggerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Pure unit test (no Spring) for {@link CoordinatorToolProvider}, now a composition root over the five
 * per-context tool classes -- see those classes' own tests ({@code WorkItemCoordinatorToolsTest} etc.)
 * for per-tool behavior/output-shape coverage. This test covers only what the provider itself owns:
 * discovery/resolve/claudeCodeToolName plumbing, and that the ten tools are built once.
 */
class CoordinatorToolProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CoordinatorToolProvider provider;

    @BeforeEach
    void setUp() {
        provider = new CoordinatorToolProvider(
                mock(WorkItemService.class),
                mock(ProjectRepository.class),
                mock(WorkflowService.class),
                mock(WorkflowTriggerService.class),
                mock(WorkflowRunRepository.class),
                mock(WorkflowRunQueryService.class),
                mock(AgentService.class),
                mock(ProjectDocService.class),
                mock(AgentExecutionService.class),
                MAPPER);
    }

    @Test
    void sourceIdIsCoordinator() {
        assertThat(provider.sourceId()).isEqualTo("coordinator");
    }

    @Test
    void offersAllTenTools() {
        assertThat(provider.available("p1")).extracting(AgentTool::name).containsExactlyInAnyOrder(
                "create_work_item", "list_work_items", "get_work_item", "list_workflows", "dispatch_workflow",
                "get_workflow_run", "list_agents", "search_project_docs", "read_project_doc", "ask_agent");
    }

    @Test
    void availableBuildsTheToolListOnceRatherThanPerCall() {
        // The tools are stateless -- available() must return the same List instance every call, not
        // reallocate ten new tool objects each time.
        List<AgentTool> first = provider.available("p1");
        List<AgentTool> second = provider.available("p1");

        assertThat(first).isSameAs(second);
    }

    @Test
    void everyToolHasAParsableNonEmptyInputSchemaAndNonBlankDescription() throws Exception {
        for (AgentTool t : provider.available("p1")) {
            assertThat(t.id()).isEqualTo("coordinator:" + t.name());
            assertThat(t.description()).isNotBlank();
            String json = MAPPER.writeValueAsString(t.inputSchema());
            Map<String, Object> parsed = MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            assertThat(parsed.get("type")).isEqualTo("object");
            assertThat(parsed).containsKey("properties");
        }
    }

    @Test
    void claudeCodeToolNameMapsOnlyTheSevenMcpOverlappingTools() {
        for (String bare : List.of("create_work_item", "list_work_items", "get_work_item", "list_workflows",
                "dispatch_workflow", "get_workflow_run", "list_agents")) {
            assertThat(provider.claudeCodeToolName("coordinator:" + bare))
                    .contains("mcp__conductor__" + bare);
        }
        for (String bare : List.of("search_project_docs", "read_project_doc", "ask_agent")) {
            assertThat(provider.claudeCodeToolName("coordinator:" + bare)).isEmpty();
        }
    }

    @Test
    void claudeCodeToolNameIsEmptyForAnUnnamespacedOrUnknownId() {
        assertThat(provider.claudeCodeToolName("not-namespaced")).isEmpty();
        assertThat(provider.claudeCodeToolName("coordinator:not_a_tool")).isEmpty();
    }

    @Test
    void resolveFindsAnOfferedToolAndReturnsEmptyForAnUnknownId() {
        assertThat(provider.resolve("p1", "coordinator:list_agents")).isPresent();
        assertThat(provider.resolve("p1", "coordinator:not_a_tool")).isEmpty();
    }
}
