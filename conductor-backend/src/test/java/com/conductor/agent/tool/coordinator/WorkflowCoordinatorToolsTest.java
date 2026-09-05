package com.conductor.agent.tool.coordinator;

import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.ToolInvocationContext;
import com.conductor.agent.tool.ToolResult;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.entity.WorkflowStepStatus;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.service.WorkflowService;
import com.conductor.workflow.WorkflowRunQueryService;
import com.conductor.workflow.WorkflowTriggerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pure unit test (no Spring) for {@link WorkflowCoordinatorTools}: pins each tool's exact output JSON
 *  shape against the pre-refactor {@code CoordinatorToolProviderTest} behavior. */
class WorkflowCoordinatorToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolInvocationContext CTX = new ToolInvocationContext("p1", "agent-1", "run-1");

    private WorkflowService workflowService;
    private WorkflowTriggerService workflowTriggerService;
    private WorkflowRunRepository workflowRunRepository;
    private WorkflowRunQueryService workflowRunQueryService;
    private WorkflowCoordinatorTools tools;

    @BeforeEach
    void setUp() {
        workflowService = mock(WorkflowService.class);
        workflowTriggerService = mock(WorkflowTriggerService.class);
        workflowRunRepository = mock(WorkflowRunRepository.class);
        workflowRunQueryService = mock(WorkflowRunQueryService.class);
        tools = new WorkflowCoordinatorTools(workflowService, workflowTriggerService, workflowRunRepository,
                workflowRunQueryService, MAPPER);
    }

    private AgentTool tool(String bareName) {
        return tools.tools().stream()
                .filter(t -> t.name().equals(bareName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No tool named " + bareName));
    }

    private WorkflowDefinition workflowDef(String id, String name, boolean enabled) {
        WorkflowDefinition def = new WorkflowDefinition();
        def.setId(id);
        def.setName(name);
        def.setEnabled(enabled);
        return def;
    }

    // ---- offered tools ----

    @Test
    void offersExactlyTheThreeWorkflowTools() {
        assertThat(tools.tools()).extracting(AgentTool::name)
                .containsExactlyInAnyOrder("list_workflows", "dispatch_workflow", "get_workflow_run");
    }

    // ---- list_workflows ----

    @Test
    void listWorkflowsHappyPathReturnsPinnedShape() throws Exception {
        WorkflowDefinition def = workflowDef("wf1", "My Workflow", true);
        when(workflowService.listWorkflows(eq("p1"), any(), any(), any())).thenReturn(List.of(def));

        ToolResult result = tool("list_workflows").invoke(Map.of(), CTX);

        List<?> rows = MAPPER.readValue(result.payload(), List.class);
        Map<String, Object> row = (Map<String, Object>) rows.get(0);
        assertThat(row.keySet()).containsExactlyInAnyOrder("id", "name", "kind", "enabled");
        assertThat(row.get("name")).isEqualTo("My Workflow");
        assertThat(row.get("kind")).isEqualTo("AUTOMATION");
        assertThat(row.get("enabled")).isEqualTo(true);
    }

    @Test
    void listWorkflowsServiceExceptionBecomesToolError() {
        when(workflowService.listWorkflows(anyString(), any(), any(), any())).thenThrow(new RuntimeException("boom"));

        ToolResult result = tool("list_workflows").invoke(Map.of(), CTX);

        assertThat(result.ok()).isFalse();
    }

    // ---- dispatch_workflow ----

    @Test
    void dispatchWorkflowHappyPathReturnsPinnedShape() throws Exception {
        WorkflowDefinition def = workflowDef("wf1", "My Workflow", true);
        def.setYaml("on: {workflow_dispatch: {}}");
        when(workflowService.getWorkflow("p1", "wf1")).thenReturn(def);
        when(workflowService.allowsManualDispatch(def.getYaml())).thenReturn(true);
        WorkflowRun run = new WorkflowRun();
        run.setId("run-9");
        run.setStatus(WorkflowRunStatus.PENDING);
        when(workflowTriggerService.triggerManual(eq(def), eq("agent:agent-1"), any())).thenReturn(run);

        ToolResult result = tool("dispatch_workflow").invoke(Map.of("workflowId", "wf1"), CTX);

        assertThat(result.ok()).isTrue();
        Map<String, Object> row = MAPPER.readValue(result.payload(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(row.keySet()).containsExactlyInAnyOrder("runId", "status");
        assertThat(row.get("runId")).isEqualTo("run-9");
    }

    @Test
    void dispatchWorkflowRefusesWhenDisabled() {
        WorkflowDefinition def = workflowDef("wf1", "My Workflow", false);
        when(workflowService.getWorkflow("p1", "wf1")).thenReturn(def);

        ToolResult result = tool("dispatch_workflow").invoke(Map.of("workflowId", "wf1"), CTX);

        assertThat(result.ok()).isFalse();
        assertThat(result.payload()).contains("disabled");
    }

    @Test
    void dispatchWorkflowRefusesWhenManualDispatchNotAllowed() {
        WorkflowDefinition def = workflowDef("wf1", "My Workflow", true);
        def.setYaml("on: {}");
        when(workflowService.getWorkflow("p1", "wf1")).thenReturn(def);
        when(workflowService.allowsManualDispatch(def.getYaml())).thenReturn(false);

        ToolResult result = tool("dispatch_workflow").invoke(Map.of("workflowId", "wf1"), CTX);

        assertThat(result.ok()).isFalse();
        assertThat(result.payload()).contains("managed automatically");
    }

    @Test
    void dispatchWorkflowServiceExceptionBecomesToolError() {
        when(workflowService.getWorkflow(anyString(), anyString())).thenThrow(new EntityNotFoundException("not found"));

        ToolResult result = tool("dispatch_workflow").invoke(Map.of("workflowId", "nope"), CTX);

        assertThat(result.ok()).isFalse();
    }

    // ---- get_workflow_run ----

    @Test
    void getWorkflowRunHappyPathReturnsPinnedShape() throws Exception {
        WorkflowDefinition def = workflowDef("wf1", "My Workflow", true);
        when(workflowService.getWorkflow("p1", "wf1")).thenReturn(def);

        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        run.setWorkflow(def);
        run.setStatus(WorkflowRunStatus.RUNNING);
        when(workflowRunRepository.findByIdWithWorkflow("run-1")).thenReturn(Optional.of(run));

        WorkflowJobRun job = new WorkflowJobRun();
        job.setId("job-1");
        job.setJobId("main");
        job.setStatus(WorkflowJobStatus.RUNNING);
        WorkflowStepRun step = new WorkflowStepRun();
        step.setStepName("build");
        step.setStatus(WorkflowStepStatus.SUCCESS);
        when(workflowRunQueryService.findJobRunsWithSteps("run-1"))
                .thenReturn(List.of(new WorkflowRunQueryService.JobRunWithSteps(job, List.of(step))));

        ToolResult result = tool("get_workflow_run").invoke(Map.of("workflowId", "wf1", "runId", "run-1"), CTX);

        assertThat(result.ok()).isTrue();
        Map<String, Object> row = MAPPER.readValue(result.payload(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(row.keySet()).containsExactlyInAnyOrder("runId", "status", "jobs");
        assertThat(row.get("status")).isEqualTo("RUNNING");
        List<?> jobs = (List<?>) row.get("jobs");
        assertThat(jobs).hasSize(1);
        Map<String, Object> jobRow = (Map<String, Object>) jobs.get(0);
        assertThat(jobRow.keySet()).containsExactlyInAnyOrder("jobId", "status", "steps");
        List<?> steps = (List<?>) jobRow.get("steps");
        Map<String, Object> stepRow = (Map<String, Object>) steps.get(0);
        assertThat(stepRow.keySet()).containsExactlyInAnyOrder("stepName", "status", "errorReason");
        assertThat(stepRow.get("stepName")).isEqualTo("build");
    }

    @Test
    void getWorkflowRunBelongingToAnotherWorkflowIsNotFound() {
        WorkflowDefinition def = workflowDef("wf1", "wf1", true);
        WorkflowDefinition otherDef = workflowDef("wf2", "wf2", true);
        when(workflowService.getWorkflow("p1", "wf1")).thenReturn(def);
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        run.setWorkflow(otherDef);
        when(workflowRunRepository.findByIdWithWorkflow("run-1")).thenReturn(Optional.of(run));

        ToolResult result = tool("get_workflow_run").invoke(Map.of("workflowId", "wf1", "runId", "run-1"), CTX);

        assertThat(result.ok()).isFalse();
    }

    @Test
    void getWorkflowRunMissingArgsReturnsError() {
        ToolResult result = tool("get_workflow_run").invoke(Map.of("workflowId", "wf1"), CTX);

        assertThat(result.ok()).isFalse();
    }
}
