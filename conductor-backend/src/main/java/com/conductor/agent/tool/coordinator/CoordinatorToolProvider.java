package com.conductor.agent.tool.coordinator;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.run.AgentExecutionService;
import com.conductor.agent.run.AgentRun;
import com.conductor.agent.run.AgentRunResult;
import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.AgentToolProvider;
import com.conductor.agent.tool.ToolInvocationContext;
import com.conductor.agent.tool.ToolResult;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectDoc;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.service.ProjectActor;
import com.conductor.service.ProjectDocService;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkflowService;
import com.conductor.workflow.WorkflowTriggerService;
import com.conductor.workflow.model.WorkflowYamlException;
import com.conductor.workflow.model.WorkflowYamlParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tool source {@code "coordinator"} — the hub-and-spoke surface for an addressable agent (e.g. the CEO
 * agent, Phase 5) to act across Conductor's other bounded contexts: Work Items, Workflows, Agents,
 * project docs, and asking a peer agent. Every tool here composes an EXISTING service or repository
 * from another context read-only, or writes through an existing service method — this provider never
 * duplicates another context's business logic. Unlike {@code KnowledgeToolProvider}, {@link #available}
 * has no project-level gate: coordination tools are always offered (an agent's own {@code toolIds}
 * binding is what actually grants access).
 *
 * <p>Every tool result is capped at ~8KB, same clamp and truncation marker as {@code
 * ConnectorToolProvider} — a coordinator's tool outputs are read by a model, not filed verbatim like a
 * Knowledge Center write, so a byte-sliced result is an acceptable tradeoff against an unbounded one.
 *
 * <p>{@code workflowService} and {@code agentExecutionService} are injected {@code @Lazy}: {@code
 * AgentToolRegistry} eagerly collects every {@code AgentToolProvider} bean (including this one), and
 * {@code AgentExecutionService} (via {@code AgentStepExecutor}/{@code WorkflowValidator}/{@code
 * WorkflowService}, and directly via {@code ask_agent}) depends back on {@code AgentToolRegistry} to
 * resolve tools for its own ReAct loop -- a real cycle, not a false positive, only visible when a full
 * Spring context is built (a pure-Mockito unit test never exercises it). Deferring these two to
 * first-use breaks the eager-init cycle without changing behavior; every other dependency here is a
 * plain repository or a service that doesn't loop back through this provider.
 */
@Component
public class CoordinatorToolProvider implements AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorToolProvider.class);
    private static final String SOURCE_ID = "coordinator";
    private static final int MAX_PAYLOAD_BYTES = 8_000;
    private static final int LIST_WORK_ITEMS_CAP = 50;

    private static final String CREATE_WORK_ITEM = "create_work_item";
    private static final String LIST_WORK_ITEMS = "list_work_items";
    private static final String GET_WORK_ITEM = "get_work_item";
    private static final String LIST_WORKFLOWS = "list_workflows";
    private static final String DISPATCH_WORKFLOW = "dispatch_workflow";
    private static final String GET_WORKFLOW_RUN = "get_workflow_run";
    private static final String LIST_AGENTS = "list_agents";
    private static final String SEARCH_PROJECT_DOCS = "search_project_docs";
    private static final String READ_PROJECT_DOC = "read_project_doc";
    private static final String ASK_AGENT = "ask_agent";

    private final WorkItemRepository workItemRepository;
    private final WorkItemService workItemService;
    private final ProjectRepository projectRepository;
    private final WorkflowService workflowService;
    private final WorkflowTriggerService workflowTriggerService;
    private final WorkflowYamlParser workflowYamlParser;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowJobRunRepository workflowJobRunRepository;
    private final WorkflowStepRunRepository workflowStepRunRepository;
    private final AgentRepository agentRepository;
    private final ProjectDocService projectDocService;
    private final AgentExecutionService agentExecutionService;
    private final ObjectMapper objectMapper;

    public CoordinatorToolProvider(WorkItemRepository workItemRepository,
                                   WorkItemService workItemService,
                                   ProjectRepository projectRepository,
                                   @Lazy WorkflowService workflowService,
                                   WorkflowTriggerService workflowTriggerService,
                                   WorkflowYamlParser workflowYamlParser,
                                   WorkflowRunRepository workflowRunRepository,
                                   WorkflowJobRunRepository workflowJobRunRepository,
                                   WorkflowStepRunRepository workflowStepRunRepository,
                                   AgentRepository agentRepository,
                                   ProjectDocService projectDocService,
                                   @Lazy AgentExecutionService agentExecutionService,
                                   ObjectMapper objectMapper) {
        this.workItemRepository = workItemRepository;
        this.workItemService = workItemService;
        this.projectRepository = projectRepository;
        this.workflowService = workflowService;
        this.workflowTriggerService = workflowTriggerService;
        this.workflowYamlParser = workflowYamlParser;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowJobRunRepository = workflowJobRunRepository;
        this.workflowStepRunRepository = workflowStepRunRepository;
        this.agentRepository = agentRepository;
        this.projectDocService = projectDocService;
        this.agentExecutionService = agentExecutionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<AgentTool> available(String projectId) {
        return List.of(new CreateWorkItemTool(), new ListWorkItemsTool(), new GetWorkItemTool(),
                new ListWorkflowsTool(), new DispatchWorkflowTool(), new GetWorkflowRunTool(),
                new ListAgentsTool(), new SearchProjectDocsTool(), new ReadProjectDocTool(), new AskAgentTool());
    }

    @Override
    public Optional<AgentTool> resolve(String projectId, String toolId) {
        return available(projectId).stream().filter(t -> t.id().equals(toolId)).findFirst();
    }

    @Override
    public Optional<String> claudeCodeToolName(String toolId) {
        String bareName = bareNameOf(toolId);
        return bareName == null ? Optional.empty() : Optional.of("mcp__conductor__" + bareName);
    }

    /** Only the seven tools with an identically-named/shaped MCP equivalent map onto it -- {@code
     *  search_project_docs}/{@code read_project_doc}/{@code ask_agent} have no MCP counterpart, so a
     *  {@code claude-code}-runtime agent can only reach them via the {@code api} runtime (this
     *  provider's own {@link #resolve}); returning no mapping is how the SPI signals that. */
    private String bareNameOf(String toolId) {
        if (toolId == null || !toolId.startsWith(SOURCE_ID + ":")) return null;
        String bare = toolId.substring(SOURCE_ID.length() + 1);
        return switch (bare) {
            case CREATE_WORK_ITEM, LIST_WORK_ITEMS, GET_WORK_ITEM, LIST_WORKFLOWS, DISPATCH_WORKFLOW,
                 GET_WORKFLOW_RUN, LIST_AGENTS -> bare;
            default -> null;
        };
    }

    // ---- tools ----

    private abstract class CoordinatorAgentTool implements AgentTool {
        private final String bareName;

        CoordinatorAgentTool(String bareName) {
            this.bareName = bareName;
        }

        @Override
        public String id() {
            return SOURCE_ID + ":" + bareName;
        }

        @Override
        public String name() {
            return bareName;
        }
    }

    private final class CreateWorkItemTool extends CoordinatorAgentTool {
        CreateWorkItemTool() { super(CREATE_WORK_ITEM); }

        @Override
        public String description() {
            return "Create a new Work Item in the project. `workflow` (the lifecycle Workflow slug that "
                    + "governs this item -- discover with list_workflows({kind: \"LIFECYCLE\"})) is "
                    + "required; never assume ENGINEERING. Verify with get_work_item.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("workflow", Map.of("type", "string",
                    "description", "Lifecycle Workflow slug that governs this Work Item (required)."));
            properties.put("type", Map.of("type", "string",
                    "description", "Work Item type, validated against the Workflow's allowed types (e.g. PRD, BUG_REPORT)."));
            properties.put("title", Map.of("type", "string", "description", "Work Item title."));
            properties.put("description", Map.of("type", "string", "description", "Work Item description (optional)."));
            return objectSchema(properties, List.of("workflow", "type", "title"));
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                String workflow = stringArg(arguments.get("workflow"));
                String type = stringArg(arguments.get("type"));
                String title = stringArg(arguments.get("title"));
                String description = stringArg(arguments.get("description"));
                if (workflow == null || workflow.isBlank() || type == null || type.isBlank()
                        || title == null || title.isBlank()) {
                    return ToolResult.error("workflow, type, and title are required");
                }

                String callerSlug = agentRepository.findById(context.agentId())
                        .map(Agent::getSlug).orElse(context.agentId());
                ProjectActor actor = ProjectActor.agent("Agent (" + callerSlug + ")");

                WorkItem item = workItemService.createWorkItem(
                        context.projectId(), type, title, description, workflow, actor);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.getId());
                row.put("displayId", projectKey(context.projectId()) + "-" + item.getSequenceNumber());
                row.put("title", item.getTitle());
                row.put("status", item.getCurrentStatus());
                row.put("type", item.getType());
                row.put("workflow", item.getWorkflow());
                return truncate(objectMapper.writeValueAsString(row));
            } catch (Exception e) {
                log.warn("CoordinatorToolProvider create_work_item failed: {}", e.getMessage());
                return ToolResult.error("create_work_item failed: " + e.getMessage());
            }
        }
    }

    private final class ListWorkItemsTool extends CoordinatorAgentTool {
        ListWorkItemsTool() { super(LIST_WORK_ITEMS); }

        @Override
        public String description() {
            return "List Work Items in the project, optionally filtered by type/status/workflow. "
                    + "Capped at " + LIST_WORK_ITEMS_CAP + " results -- narrow with filters on a larger "
                    + "project. Verify a specific item with get_work_item.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("type", Map.of("type", "string", "description", "Filter by type (optional)."));
            properties.put("status", Map.of("type", "string", "description", "Filter by status (optional)."));
            properties.put("workflow", Map.of("type", "string", "description", "Filter by bound Workflow slug (optional)."));
            properties.put("limit", Map.of("type", "integer",
                    "description", "Max results, capped at " + LIST_WORK_ITEMS_CAP + " (optional, default " + LIST_WORK_ITEMS_CAP + ")."));
            return objectSchema(properties, List.of());
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                String type = stringArg(arguments.get("type"));
                String status = stringArg(arguments.get("status"));
                String workflow = stringArg(arguments.get("workflow"));
                int limit = clampLimit(arguments.get("limit"), LIST_WORK_ITEMS_CAP);

                List<WorkItem> items = workItemRepository.findByProjectFiltered(
                        context.projectId(), type, status, workflow);
                String projectKey = projectKey(context.projectId());

                List<Map<String, Object>> rows = new ArrayList<>();
                for (WorkItem item : items) {
                    if (rows.size() >= limit) break;
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", item.getId());
                    row.put("displayId", projectKey + "-" + item.getSequenceNumber());
                    row.put("title", item.getTitle());
                    row.put("status", item.getCurrentStatus());
                    row.put("type", item.getType());
                    rows.add(row);
                }
                return truncate(objectMapper.writeValueAsString(rows));
            } catch (Exception e) {
                log.warn("CoordinatorToolProvider list_work_items failed: {}", e.getMessage());
                return ToolResult.error("list_work_items failed: " + e.getMessage());
            }
        }
    }

    private final class GetWorkItemTool extends CoordinatorAgentTool {
        GetWorkItemTool() { super(GET_WORK_ITEM); }

        @Override
        public String description() {
            return "Get one Work Item's full detail (including description) by id or display id "
                    + "(e.g. COND-42).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("issueId", Map.of("type", "string", "description", "Work Item id or display id."));
            return objectSchema(properties, List.of("issueId"));
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                String ref = stringArg(arguments.get("issueId"));
                if (ref == null || ref.isBlank()) {
                    return ToolResult.error("issueId is required");
                }
                WorkItem item = workItemRepository.findByIdWithProjectAndAssignee(ref)
                        .filter(i -> context.projectId().equals(i.getProject().getId()))
                        .orElse(null);
                if (item == null) {
                    Integer sequenceNumber = parseSequenceNumber(ref);
                    if (sequenceNumber != null) {
                        item = workItemRepository.findByProjectIdAndSequenceNumber(context.projectId(), sequenceNumber)
                                .orElse(null);
                    }
                }
                if (item == null) {
                    return ToolResult.error("Work Item not found: " + ref);
                }
                String projectKey = projectKey(context.projectId());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.getId());
                row.put("displayId", projectKey + "-" + item.getSequenceNumber());
                row.put("title", item.getTitle());
                row.put("description", item.getDescription());
                row.put("status", item.getCurrentStatus());
                row.put("type", item.getType());
                row.put("workflow", item.getWorkflow());
                return truncate(objectMapper.writeValueAsString(row));
            } catch (Exception e) {
                log.warn("CoordinatorToolProvider get_work_item failed: {}", e.getMessage());
                return ToolResult.error("get_work_item failed: " + e.getMessage());
            }
        }
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
                log.warn("CoordinatorToolProvider list_workflows failed: {}", e.getMessage());
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
                if (!allowsManualDispatch(workflow.getYaml())) {
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
                log.warn("CoordinatorToolProvider dispatch_workflow failed: {}", e.getMessage());
                return ToolResult.error("dispatch_workflow failed: " + e.getMessage());
            }
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

                List<WorkflowJobRun> jobRuns = workflowJobRunRepository.findByRunId(runId);
                List<Map<String, Object>> jobRows = new ArrayList<>();
                for (WorkflowJobRun jobRun : jobRuns) {
                    List<WorkflowStepRun> steps =
                            workflowStepRunRepository.findByJobRunIdOrderByStartedAtAscIdAsc(jobRun.getId());
                    List<Map<String, Object>> stepRows = new ArrayList<>();
                    for (WorkflowStepRun step : steps) {
                        Map<String, Object> stepRow = new LinkedHashMap<>();
                        stepRow.put("stepName", step.getStepName());
                        stepRow.put("status", step.getStatus().name());
                        stepRow.put("errorReason", step.getErrorReason());
                        stepRows.add(stepRow);
                    }
                    Map<String, Object> jobRow = new LinkedHashMap<>();
                    jobRow.put("jobId", jobRun.getJobId());
                    jobRow.put("status", jobRun.getStatus().name());
                    jobRow.put("steps", stepRows);
                    jobRows.add(jobRow);
                }

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("runId", run.getId());
                response.put("status", run.getStatus().name());
                response.put("jobs", jobRows);
                return truncate(objectMapper.writeValueAsString(response));
            } catch (Exception e) {
                log.warn("CoordinatorToolProvider get_workflow_run failed: {}", e.getMessage());
                return ToolResult.error("get_workflow_run failed: " + e.getMessage());
            }
        }
    }

    private final class ListAgentsTool extends CoordinatorAgentTool {
        ListAgentsTool() { super(LIST_AGENTS); }

        @Override
        public String description() {
            return "List the project's named AI Agents -- name, slug, description, state, and whether "
                    + "it's addressable (askable directly via ask_agent regardless of this flag; the "
                    + "flag marks agents meant for human/conversational addressing).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return objectSchema(Map.of(), List.of());
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                List<Agent> agents = agentRepository.findByProjectId(context.projectId());
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Agent agent : agents) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("slug", agent.getSlug());
                    row.put("name", agent.getName());
                    row.put("description", agent.getDescription());
                    row.put("state", agent.getState());
                    row.put("addressable", agent.isAddressable());
                    rows.add(row);
                }
                return truncate(objectMapper.writeValueAsString(rows));
            } catch (Exception e) {
                log.warn("CoordinatorToolProvider list_agents failed: {}", e.getMessage());
                return ToolResult.error("list_agents failed: " + e.getMessage());
            }
        }
    }

    private final class SearchProjectDocsTool extends CoordinatorAgentTool {
        SearchProjectDocsTool() { super(SEARCH_PROJECT_DOCS); }

        @Override
        public String description() {
            return "Full-text search over the project's docs -- id, title, snippet. Use before "
                    + "read_project_doc.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("q", Map.of("type", "string", "description", "Search query."));
            properties.put("limit", Map.of("type", "integer", "description", "Max results (optional, default 20)."));
            return objectSchema(properties, List.of("q"));
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                String q = stringArg(arguments.get("q"));
                if (q == null || q.isBlank()) {
                    return ToolResult.error("q is required");
                }
                int limit = clampLimit(arguments.get("limit"), 20);
                List<ProjectDoc> docs = projectDocService.searchDocs(context.projectId(), q, limit);
                List<Map<String, Object>> rows = new ArrayList<>();
                for (ProjectDoc doc : docs) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", doc.getId());
                    row.put("title", doc.getTitle());
                    row.put("snippet", snippet(doc.getContent(), q));
                    rows.add(row);
                }
                return truncate(objectMapper.writeValueAsString(rows));
            } catch (Exception e) {
                log.warn("CoordinatorToolProvider search_project_docs failed: {}", e.getMessage());
                return ToolResult.error("search_project_docs failed: " + e.getMessage());
            }
        }
    }

    private final class ReadProjectDocTool extends CoordinatorAgentTool {
        ReadProjectDocTool() { super(READ_PROJECT_DOC); }

        @Override
        public String description() {
            return "Fetch one project doc's full title + content by id (from search_project_docs).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("id", Map.of("type", "string", "description", "Doc id (from search_project_docs)."));
            return objectSchema(properties, List.of("id"));
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                String id = stringArg(arguments.get("id"));
                if (id == null || id.isBlank()) {
                    return ToolResult.error("id is required");
                }
                ProjectDoc doc = projectDocService.getDoc(context.projectId(), id);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", doc.getId());
                row.put("title", doc.getTitle());
                row.put("content", doc.getContent());
                return truncate(objectMapper.writeValueAsString(row));
            } catch (EntityNotFoundException e) {
                return ToolResult.error("Project doc not found: " + arguments.get("id"));
            } catch (Exception e) {
                log.warn("CoordinatorToolProvider read_project_doc failed: {}", e.getMessage());
                return ToolResult.error("read_project_doc failed: " + e.getMessage());
            }
        }
    }

    private final class AskAgentTool extends CoordinatorAgentTool {
        AskAgentTool() { super(ASK_AGENT); }

        @Override
        public String description() {
            return "Ask another ACTIVE agent in this project to perform a task and return its answer. "
                    + "Refuses to target itself, and refuses a target that could itself call ask_agent "
                    + "(no coordinator chains).";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("agent", Map.of("type", "string", "description", "Target agent's slug or display name."));
            properties.put("task", Map.of("type", "string", "description", "The task/question for the target agent."));
            return objectSchema(properties, List.of("agent", "task"));
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                String ref = stringArg(arguments.get("agent"));
                String task = stringArg(arguments.get("task"));
                if (ref == null || ref.isBlank() || task == null || task.isBlank()) {
                    return ToolResult.error("agent and task are required");
                }

                Agent target = resolveAnyActiveAgent(context.projectId(), ref);
                if (target == null) {
                    return ToolResult.error("No ACTIVE agent found for '" + ref + "'");
                }
                if (target.getId().equals(context.agentId())) {
                    return ToolResult.error("Refusing: an agent cannot ask_agent itself");
                }
                if (parseToolIds(target.getToolIds()).contains(SOURCE_ID + ":" + ASK_AGENT)) {
                    return ToolResult.error("Refusing: '" + target.getSlug() + "' can itself call ask_agent "
                            + "-- coordinator chains are not allowed");
                }

                // __conversation_depth is set for forward compatibility with the recursion guard
                // AgentConversationRunner seeds (Phase 3) -- ToolInvocationContext carries no depth
                // today, so this tool cannot read/increment an actual caller depth; see the Phase 4
                // report for why that guard is currently limited to (a) self-target and (b) chain-target.
                AgentRunResult result = agentExecutionService.run(context.projectId(), target.getId(), task,
                        Map.of("__conversation_depth", 1), null);

                if (AgentRun.Status.FAILED.name().equals(result.status())) {
                    return ToolResult.error("ask_agent run failed (runId=" + result.runId() + "): "
                            + (result.outputText() == null || result.outputText().isBlank()
                                    ? "no output" : result.outputText()));
                }

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("runId", result.runId());
                row.put("output", result.outputText() == null ? "" : result.outputText());
                return truncate(objectMapper.writeValueAsString(row));
            } catch (Exception e) {
                log.warn("CoordinatorToolProvider ask_agent failed: {}", e.getMessage());
                return ToolResult.error("ask_agent failed: " + e.getMessage());
            }
        }
    }

    // ---- shared helpers ----

    /** Slug match (case-insensitive) first, then display-name match (case-insensitive); any ACTIVE
     *  agent is eligible -- unlike {@code AddressableAgentResolver}, {@code ask_agent} is not restricted
     *  to agents opted into direct human/chat addressing. Null when zero or multiple matches. */
    private Agent resolveAnyActiveAgent(String projectId, String ref) {
        List<Agent> active = agentRepository.findByProjectId(projectId).stream()
                .filter(a -> "ACTIVE".equals(a.getState()))
                .toList();
        Optional<Agent> slugMatch = active.stream().filter(a -> a.getSlug().equalsIgnoreCase(ref)).findFirst();
        if (slugMatch.isPresent()) {
            return slugMatch.get();
        }
        List<Agent> nameMatches = active.stream()
                .filter(a -> a.getName() != null && a.getName().equalsIgnoreCase(ref))
                .toList();
        return nameMatches.size() == 1 ? nameMatches.get(0) : null;
    }

    /** Mirrors {@code WorkflowController}'s private helper of the same name exactly (same public
     *  collaborators, {@code yamlParser.parse(yaml).triggers().allowsManualDispatch()}) -- duplicated
     *  rather than shared because the controller's is private and this provider must not reach into the
     *  controller package. */
    private boolean allowsManualDispatch(String yaml) {
        if (yaml == null) return true;
        try {
            return workflowYamlParser.parse(yaml).triggers().allowsManualDispatch();
        } catch (WorkflowYamlException e) {
            return true;
        }
    }

    /** Mirrors {@code WorkItemService#parseSequenceNumber} (private there): the trailing integer after
     *  the last {@code -} in a display id like {@code COND-42}. Null for anything that doesn't parse --
     *  callers treat that as "not a display id", not an error. */
    private Integer parseSequenceNumber(String displayId) {
        if (displayId == null) return null;
        int dash = displayId.lastIndexOf('-');
        String tail = dash >= 0 ? displayId.substring(dash + 1) : displayId;
        try {
            return Integer.valueOf(tail.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String projectKey(String projectId) {
        return projectRepository.findById(projectId).map(Project::getKey).orElse(projectId);
    }

    private int clampLimit(Object value, int cap) {
        int requested = value instanceof Number n ? n.intValue() : cap;
        if (requested <= 0) return cap;
        return Math.min(requested, cap);
    }

    /** Best-effort snippet around the first query-term hit, falling back to a leading clip. Rows already
     *  arrive rank-ordered from {@code ProjectDocService#searchDocs}; this only picks WHERE within each
     *  doc's content to cut the snippet, same as {@code ProjectDocsController#extractSnippet}. */
    private String snippet(String content, String query) {
        if (content == null) return "";
        int idx = content.toLowerCase().indexOf(query.toLowerCase());
        if (idx < 0) {
            return content.length() > 200 ? content.substring(0, 200) + "…" : content;
        }
        int start = Math.max(0, idx - 80);
        int end = Math.min(content.length(), idx + query.length() + 80);
        return (start > 0 ? "…" : "") + content.substring(start, end) + (end < content.length() ? "…" : "");
    }

    private Map<String, String> stringMap(Map<Object, Object> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k != null && v != null) out.put(k.toString(), v.toString());
        });
        return out;
    }

    private List<String> parseToolIds(String toolIdsJson) {
        if (toolIdsJson == null || toolIdsJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(toolIdsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String stringArg(Object value) {
        return value == null ? null : value.toString();
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private ToolResult truncate(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_PAYLOAD_BYTES) {
            return ToolResult.ok(json);
        }
        String clipped = new String(bytes, 0, MAX_PAYLOAD_BYTES, StandardCharsets.UTF_8) + "\n…[truncated]";
        return ToolResult.ok(clipped, true);
    }
}
