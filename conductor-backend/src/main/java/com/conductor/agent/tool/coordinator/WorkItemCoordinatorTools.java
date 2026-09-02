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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.conductor.agent.tool.coordinator.CoordinatorToolSupport.clampLimit;
import static com.conductor.agent.tool.coordinator.CoordinatorToolSupport.objectSchema;
import static com.conductor.agent.tool.coordinator.CoordinatorToolSupport.stringArg;
import static com.conductor.agent.tool.coordinator.CoordinatorToolSupport.truncate;

/**
 * The Work Item tools: {@code create_work_item}, {@code list_work_items}, {@code get_work_item}. Every
 * tool composes {@link WorkItemService} read-only or writes through it, same "compose the owning
 * context's service" discipline as every other class in this package -- see {@link
 * CoordinatorToolProvider}'s class javadoc.
 *
 * <p>{@link ProjectRepository} is still reached directly (only for {@link Project#getKey()}, to render
 * a Work Item's {@code displayId}): no service exposes a lean id-to-key lookup for a machine caller, and
 * adding one just for this would be a bigger seam than the one-line query it replaces.
 */
final class WorkItemCoordinatorTools {

    private static final Logger log = LoggerFactory.getLogger(WorkItemCoordinatorTools.class);

    static final String CREATE_WORK_ITEM = "create_work_item";
    static final String LIST_WORK_ITEMS = "list_work_items";
    static final String GET_WORK_ITEM = "get_work_item";
    private static final int LIST_WORK_ITEMS_CAP = 50;

    private final WorkItemService workItemService;
    private final AgentService agentService;
    private final ProjectRepository projectRepository;
    private final ObjectMapper objectMapper;

    WorkItemCoordinatorTools(WorkItemService workItemService, AgentService agentService,
                             ProjectRepository projectRepository, ObjectMapper objectMapper) {
        this.workItemService = workItemService;
        this.agentService = agentService;
        this.projectRepository = projectRepository;
        this.objectMapper = objectMapper;
    }

    List<AgentTool> tools() {
        return List.of(new CreateWorkItemTool(), new ListWorkItemsTool(), new GetWorkItemTool());
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

                String callerSlug;
                try {
                    callerSlug = agentService.get(context.projectId(), context.agentId()).getSlug();
                } catch (EntityNotFoundException e) {
                    callerSlug = context.agentId();
                }
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
                log.warn("WorkItemCoordinatorTools create_work_item failed: {}", e.getMessage());
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

                List<WorkItem> items = workItemService.listWorkItemsForAgent(
                        context.projectId(), type, status, workflow, limit);
                String projectKey = projectKey(context.projectId());

                List<Map<String, Object>> rows = new ArrayList<>();
                for (WorkItem item : items) {
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
                log.warn("WorkItemCoordinatorTools list_work_items failed: {}", e.getMessage());
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
                WorkItem item;
                try {
                    item = workItemService.resolveByReference(context.projectId(), ref,
                            ProjectActor.agent("agent:" + context.agentId()));
                } catch (EntityNotFoundException e) {
                    return ToolResult.error("Work Item not found: " + ref);
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.getId());
                row.put("displayId", projectKey(context.projectId()) + "-" + item.getSequenceNumber());
                row.put("title", item.getTitle());
                row.put("description", item.getDescription());
                row.put("status", item.getCurrentStatus());
                row.put("type", item.getType());
                row.put("workflow", item.getWorkflow());
                return truncate(objectMapper.writeValueAsString(row));
            } catch (Exception e) {
                log.warn("WorkItemCoordinatorTools get_work_item failed: {}", e.getMessage());
                return ToolResult.error("get_work_item failed: " + e.getMessage());
            }
        }
    }

    private String projectKey(String projectId) {
        return projectRepository.findById(projectId).map(Project::getKey).orElse(projectId);
    }
}
