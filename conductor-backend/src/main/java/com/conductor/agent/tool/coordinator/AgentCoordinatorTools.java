package com.conductor.agent.tool.coordinator;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentService;
import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.ToolInvocationContext;
import com.conductor.agent.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.conductor.agent.tool.coordinator.CoordinatorToolSupport.objectSchema;
import static com.conductor.agent.tool.coordinator.CoordinatorToolSupport.truncate;

/**
 * The Agent tool: {@code list_agents}. Composes {@link AgentService#list(String)} rather than {@code
 * AgentRepository} directly -- see {@link CoordinatorToolProvider}'s class javadoc.
 */
final class AgentCoordinatorTools {

    private static final Logger log = LoggerFactory.getLogger(AgentCoordinatorTools.class);

    static final String LIST_AGENTS = "list_agents";

    private final AgentService agentService;
    private final ObjectMapper objectMapper;

    AgentCoordinatorTools(AgentService agentService, ObjectMapper objectMapper) {
        this.agentService = agentService;
        this.objectMapper = objectMapper;
    }

    List<AgentTool> tools() {
        return List.of(new ListAgentsTool());
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
                List<Agent> agents = agentService.list(context.projectId());
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
                log.warn("AgentCoordinatorTools list_agents failed: {}", e.getMessage());
                return ToolResult.error("list_agents failed: " + e.getMessage());
            }
        }
    }
}
