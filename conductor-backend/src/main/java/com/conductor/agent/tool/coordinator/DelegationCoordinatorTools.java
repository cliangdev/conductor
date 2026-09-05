package com.conductor.agent.tool.coordinator;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentService;
import com.conductor.agent.run.AgentExecutionService;
import com.conductor.agent.run.AgentRun;
import com.conductor.agent.run.AgentRunResult;
import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.ToolInvocationContext;
import com.conductor.agent.tool.ToolResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.conductor.agent.tool.coordinator.CoordinatorToolSupport.SOURCE_ID;
import static com.conductor.agent.tool.coordinator.CoordinatorToolSupport.objectSchema;
import static com.conductor.agent.tool.coordinator.CoordinatorToolSupport.stringArg;
import static com.conductor.agent.tool.coordinator.CoordinatorToolSupport.truncate;

/**
 * The delegation tool: {@code ask_agent}. Composes {@link AgentService#list(String)} to resolve the
 * target (rather than {@code AgentRepository} directly -- see {@link CoordinatorToolProvider}'s class
 * javadoc) and {@link AgentExecutionService} to run it.
 */
final class DelegationCoordinatorTools {

    private static final Logger log = LoggerFactory.getLogger(DelegationCoordinatorTools.class);

    static final String ASK_AGENT = "ask_agent";

    private final AgentService agentService;
    private final AgentExecutionService agentExecutionService;
    private final ObjectMapper objectMapper;

    DelegationCoordinatorTools(AgentService agentService, AgentExecutionService agentExecutionService,
                               ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.agentExecutionService = agentExecutionService;
        this.objectMapper = objectMapper;
    }

    List<AgentTool> tools() {
        return List.of(new AskAgentTool());
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

                AgentRunResult result = agentExecutionService.run(context.projectId(), target.getId(), task,
                        Map.of(), null);

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
                log.warn("DelegationCoordinatorTools ask_agent failed: {}", e.getMessage());
                return ToolResult.error("ask_agent failed: " + e.getMessage());
            }
        }
    }

    /** Slug match (case-insensitive) first, then display-name match (case-insensitive); any ACTIVE
     *  agent is eligible -- unlike {@code AddressableAgentResolver}, {@code ask_agent} is not restricted
     *  to agents opted into direct human/chat addressing. Null when zero or multiple matches. */
    private Agent resolveAnyActiveAgent(String projectId, String ref) {
        List<Agent> active = agentService.list(projectId).stream()
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

    private List<String> parseToolIds(String toolIdsJson) {
        if (toolIdsJson == null || toolIdsJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(toolIdsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
