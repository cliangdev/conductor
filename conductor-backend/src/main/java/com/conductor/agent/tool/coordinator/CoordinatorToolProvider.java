package com.conductor.agent.tool.coordinator;

import com.conductor.agent.AgentService;
import com.conductor.agent.run.AgentExecutionService;
import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.AgentToolProvider;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.service.ProjectDocService;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkflowService;
import com.conductor.workflow.WorkflowRunQueryService;
import com.conductor.workflow.WorkflowTriggerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Tool source {@code "coordinator"} — the hub-and-spoke surface for an addressable agent (e.g. the CEO
 * agent, Phase 5) to act across Conductor's other bounded contexts: Work Items, Workflows, Agents,
 * project docs, and asking a peer agent. Every tool composes an EXISTING service from the context that
 * owns it, read-only or writing through it — split across five per-context classes ({@link
 * WorkItemCoordinatorTools}, {@link WorkflowCoordinatorTools}, {@link AgentCoordinatorTools}, {@link
 * ProjectDocCoordinatorTools}, {@link DelegationCoordinatorTools}) so each bounded context's surface is
 * reviewable on its own; this class is pure composition — it builds the ten tools once and answers the
 * {@link AgentToolProvider} SPI, holding no tool logic itself. Unlike {@code KnowledgeToolProvider},
 * {@link #available} has no project-level gate: coordination tools are always offered (an agent's own
 * {@code toolIds} binding is what actually grants access).
 *
 * <p>{@code workflowService}, {@code agentService}, and {@code agentExecutionService} are injected
 * {@code @Lazy}: {@code AgentToolRegistry} eagerly collects every {@code AgentToolProvider} bean
 * (including this one), and each of the three depends back on {@code AgentToolRegistry} to resolve
 * tools -- {@code AgentService} directly (its {@code listAvailableTools}), {@code AgentExecutionService}
 * for its own ReAct loop (via {@code AgentStepExecutor}/{@code WorkflowValidator}/{@code
 * WorkflowService}, and directly via {@code ask_agent}). Real cycles, not false positives, only visible
 * when a full Spring context is built (a pure-Mockito unit test never exercises them). Deferring these
 * three to first-use breaks the eager-init cycle without changing behavior; every other dependency here
 * is a plain repository or a service that doesn't loop back through this provider.
 */
@Component
public class CoordinatorToolProvider implements AgentToolProvider {

    private static final String SOURCE_ID = CoordinatorToolSupport.SOURCE_ID;

    private static final String CREATE_WORK_ITEM = WorkItemCoordinatorTools.CREATE_WORK_ITEM;
    private static final String LIST_WORK_ITEMS = WorkItemCoordinatorTools.LIST_WORK_ITEMS;
    private static final String GET_WORK_ITEM = WorkItemCoordinatorTools.GET_WORK_ITEM;
    private static final String LIST_WORKFLOWS = WorkflowCoordinatorTools.LIST_WORKFLOWS;
    private static final String DISPATCH_WORKFLOW = WorkflowCoordinatorTools.DISPATCH_WORKFLOW;
    private static final String GET_WORKFLOW_RUN = WorkflowCoordinatorTools.GET_WORKFLOW_RUN;
    private static final String LIST_AGENTS = AgentCoordinatorTools.LIST_AGENTS;

    /**
     * The subset of {@code coordinator:*} tool ids that write rather than merely read -- Work Item
     * creation and workflow dispatch. Sourced from the same bare-name constants every other id in this
     * class is, not re-typed string literals, so a future write-capable coordinator tool added anywhere
     * in this package can't be silently left out of a caller's write-action gate just by forgetting a
     * second place to list it. This lives here (the coordinator package owns which of its own tools
     * write) rather than in a caller like {@code DiscordAppConnector}, which has no business being the
     * authority on that -- see its Discord {@code /ask} write-action toggle, the first consumer.
     */
    public static final Set<String> WRITE_CAPABLE_TOOL_IDS = Set.of(
            SOURCE_ID + ":" + CREATE_WORK_ITEM,
            SOURCE_ID + ":" + DISPATCH_WORKFLOW);

    /** The ten built {@code coordinator:*} tools -- built once in the constructor rather than per call:
     *  every tool class is stateless (each invocation is scoped entirely by its {@code
     *  ToolInvocationContext} argument), so there's nothing project-specific to rebuild on every {@link
     *  #available}/{@link #resolve} call. */
    private final List<AgentTool> tools;

    public CoordinatorToolProvider(WorkItemService workItemService,
                                   ProjectRepository projectRepository,
                                   @Lazy WorkflowService workflowService,
                                   WorkflowTriggerService workflowTriggerService,
                                   WorkflowRunRepository workflowRunRepository,
                                   WorkflowRunQueryService workflowRunQueryService,
                                   @Lazy AgentService agentService,
                                   ProjectDocService projectDocService,
                                   @Lazy AgentExecutionService agentExecutionService,
                                   ObjectMapper objectMapper) {
        List<AgentTool> built = new ArrayList<>();
        built.addAll(new WorkItemCoordinatorTools(workItemService, agentService, projectRepository, objectMapper)
                .tools());
        built.addAll(new WorkflowCoordinatorTools(workflowService, workflowTriggerService, workflowRunRepository,
                workflowRunQueryService, objectMapper).tools());
        built.addAll(new AgentCoordinatorTools(agentService, objectMapper).tools());
        built.addAll(new ProjectDocCoordinatorTools(projectDocService, objectMapper).tools());
        built.addAll(new DelegationCoordinatorTools(agentService, agentExecutionService, objectMapper).tools());
        this.tools = List.copyOf(built);
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<AgentTool> available(String projectId) {
        return tools;
    }

    @Override
    public Optional<AgentTool> resolve(String projectId, String toolId) {
        return tools.stream().filter(t -> t.id().equals(toolId)).findFirst();
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
}
