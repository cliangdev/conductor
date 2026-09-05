package com.conductor.agent.tool.coordinator;

import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.ToolInvocationContext;
import com.conductor.agent.tool.ToolResult;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.service.WorkflowService;
import com.conductor.workflow.WorkflowRunQueryService;
import com.conductor.workflow.WorkflowTriggerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.conductor.agent.tool.coordinator.CoordinatorToolSupport.objectSchema;
import static com.conductor.agent.tool.coordinator.CoordinatorToolSupport.stringArg;
import static com.conductor.agent.tool.coordinator.CoordinatorToolSupport.truncate;

/**
 * The Workflow tools: {@code list_workflows}, {@code dispatch_workflow}, {@code get_workflow_run}. Every
 * tool composes {@link WorkflowService}/{@link WorkflowTriggerService}/{@link WorkflowRunQueryService}
 * read-only or writes through them -- see {@link CoordinatorToolProvider}'s class javadoc.
 *
 * <p>{@link WorkflowRunRepository} is still reached directly (only {@code findByIdWithWorkflow}, a
 * single eager-fetch lookup): no service wraps that one query, and {@code WorkflowController
 * #getWorkflowRun} does the identical inline call itself, so this isn't a duplicated business rule --
 * just the same thin data-access call made twice.
 */
final class WorkflowCoordinatorTools {

    private static final Logger log = LoggerFactory.getLogger(WorkflowCoordinatorTools.class);

    static final String LIST_WORKFLOWS = "list_workflows";
    static final String DISPATCH_WORKFLOW = "dispatch_workflow";
    static final String GET_WORKFLOW_RUN = "get_workflow_run";

    private final WorkflowService workflowService;
    private final WorkflowTriggerService workflowTriggerService;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowRunQueryService workflowRunQueryService;
    private final ObjectMapper objectMapper;

    WorkflowCoordinatorTools(WorkflowService workflowService, WorkflowTriggerService workflowTriggerService,
                             WorkflowRunRepository workflowRunRepository,
                             WorkflowRunQueryService workflowRunQueryService, ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.workflowTriggerService = workflowTriggerService;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowRunQueryService = workflowRunQueryService;
        this.objectMapper = objectMapper;
    }

    List<AgentTool> tools() {
        return List.of(new ListWorkflowsTool(), new DispatchWorkflowTool(), new GetWorkflowRunTool());
    }

    private final class ListWorkflowsTool extends CoordinatorAgentTool {
        ListWorkflowsTool() { super(LIST_WORKFLOWS); }

        @Override
        public String description() {
            return "List the project's Workflows -- id, name, kind (LIFECYCLE/AUTOMATION), enabled. "
                    + "Discovery entry point before dispatch_workflow.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("kind", Map.of("type", "string", "enum", List.of("LIFECYCLE", "AUTOMATION"),
                    "description", "Filter by Workflow kind (optional)."));
            return objectSchema(properties, List.of());
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                String kind = stringArg(arguments.get("kind"));
                Boolean lifecycle = "LIFECYCLE".equalsIgnoreCase(kind) ? Boolean.TRUE
                        : "AUTOMATION".equalsIgnoreCase(kind) ? Boolean.FALSE : null;
                List<WorkflowDefinition> defs = workflowService.listWorkflows(context.projectId(), lifecycle, null, null);
                List<Map<String, Object>> rows = new ArrayList<>();
                for (WorkflowDefinition def : defs) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", def.getId());
                    row.put("name", def.getName());
                    row.put("kind", def.isLifecycle() ? "LIFECYCLE" : "AUTOMATION");
                    row.put("enabled", def.isEnabled());
                    rows.add(row);
                }
                return truncate(objectMapper.writeValueAsString(rows));
            } catch (Exception e) {
                log.warn("WorkflowCoordinatorTools list_workflows failed: {}", e.getMessage());
                return ToolResult.error("list_workflows failed: " + e.getMessage());
            }
        }
    }

    private final class DispatchWorkflowTool extends CoordinatorAgentTool {
        DispatchWorkflowTool() { super(DISPATCH_WORKFLOW); }

        @Override
        public String description() {
            return "Manually trigger a PUBLISHED YAML automation Workflow run (not a statechart lifecycle "
                    + "Workflow). Optional flat string `inputs` become ${{ inputs.KEY }} in the run. "
                    + "Returns the run id -- call get_workflow_run after to verify it started.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("workflowId", Map.of("type", "string", "description", "Workflow definition id."));
            properties.put("inputs", Map.of("type", "object",
                    "description", "Flat string key-value input values passed to the run (optional)."));
            return objectSchema(properties, List.of("workflowId"));
        }

        @Override
        @SuppressWarnings("unchecked")
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                String workflowId = stringArg(arguments.get("workflowId"));
                if (workflowId == null || workflowId.isBlank()) {
                    return ToolResult.error("workflowId is required");
                }
                WorkflowDefinition workflow = workflowService.getWorkflow(context.projectId(), workflowId);

                // Same two guards WorkflowController#dispatchWorkflow applies before triggerManual --
                // deliberately NOT its isProjectMember(userId) check, which is a human project_members
                // membership test that has no meaning for an agent caller.
                if (!workflow.isEnabled()) {
                    String reason = workflow.getAutoPausedAt() != null
                            ? " -- it was auto-paused after " + workflow.getConsecutiveFailures()
                                    + " consecutive failed runs. Re-enable it to clear the pause and try again."
                            : " -- enable it first.";
                    return ToolResult.error("'" + workflow.getName() + "' is disabled" + reason);
                }
                if (!workflowService.allowsManualDispatch(workflow.getYaml())) {
                    return ToolResult.error("'" + workflow.getName() + "' is managed automatically and can't "
                            + "be run manually -- its trigger data is supplied by the process that dispatches it.");
                }

                Map<String, String> inputs = arguments.get("inputs") instanceof Map<?, ?> m
                        ? stringMap((Map<Object, Object>) m) : null;
                WorkflowRun run = workflowTriggerService.triggerManual(
                        workflow, "agent:" + context.agentId(), inputs);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("runId", run.getId());
                row.put("status", run.getStatus().name());
                return truncate(objectMapper.writeValueAsString(row));
            } catch (Exception e) {
                log.warn("WorkflowCoordinatorTools dispatch_workflow failed: {}", e.getMessage());
                return ToolResult.error("dispatch_workflow failed: " + e.getMessage());
            }
        }

        private Map<String, String> stringMap(Map<Object, Object> raw) {
            Map<String, String> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                if (k != null && v != null) out.put(k.toString(), v.toString());
            });
            return out;
        }
    }

    private final class GetWorkflowRunTool extends CoordinatorAgentTool {
        GetWorkflowRunTool() { super(GET_WORKFLOW_RUN); }

        @Override
        public String description() {
            return "Get status and a compact per-step summary for a workflow run. Call after "
                    + "dispatch_workflow to verify the run started or succeeded.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("workflowId", Map.of("type", "string", "description", "Workflow definition id."));
            properties.put("runId", Map.of("type", "string", "description", "Run id (from dispatch_workflow)."));
            return objectSchema(properties, List.of("workflowId", "runId"));
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                String workflowId = stringArg(arguments.get("workflowId"));
                String runId = stringArg(arguments.get("runId"));
                if (workflowId == null || runId == null) {
                    return ToolResult.error("workflowId and runId are required");
                }
                // Resolving the workflow first (project-scoped) rather than navigating run.getWorkflow()
                // .getProject() avoids a lazy-load on a detached entity outside a transaction -- run's
                // own workflow association only needs an id-equality check against this already-loaded,
                // already-project-verified WorkflowDefinition.
                WorkflowDefinition workflow = workflowService.getWorkflow(context.projectId(), workflowId);

                WorkflowRun run = workflowRunRepository.findByIdWithWorkflow(runId)
                        .orElse(null);
                if (run == null || !run.getWorkflow().getId().equals(workflow.getId())) {
                    return ToolResult.error("Workflow run not found: " + runId);
                }

                List<WorkflowRunQueryService.JobRunWithSteps> jobRuns =
                        workflowRunQueryService.findJobRunsWithSteps(runId);
                List<Map<String, Object>> jobRows = new ArrayList<>();
                for (WorkflowRunQueryService.JobRunWithSteps jobRunWithSteps : jobRuns) {
                    List<Map<String, Object>> stepRows = new ArrayList<>();
                    for (WorkflowStepRun step : jobRunWithSteps.steps()) {
                        Map<String, Object> stepRow = new LinkedHashMap<>();
                        stepRow.put("stepName", step.getStepName());
                        stepRow.put("status", step.getStatus().name());
                        stepRow.put("errorReason", step.getErrorReason());
                        stepRows.add(stepRow);
                    }
                    Map<String, Object> jobRow = new LinkedHashMap<>();
                    jobRow.put("jobId", jobRunWithSteps.jobRun().getJobId());
                    jobRow.put("status", jobRunWithSteps.jobRun().getStatus().name());
                    jobRow.put("steps", stepRows);
                    jobRows.add(jobRow);
                }

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("runId", run.getId());
                response.put("status", run.getStatus().name());
                response.put("jobs", jobRows);
                return truncate(objectMapper.writeValueAsString(response));
            } catch (Exception e) {
                log.warn("WorkflowCoordinatorTools get_workflow_run failed: {}", e.getMessage());
                return ToolResult.error("get_workflow_run failed: " + e.getMessage());
            }
        }
    }
}
