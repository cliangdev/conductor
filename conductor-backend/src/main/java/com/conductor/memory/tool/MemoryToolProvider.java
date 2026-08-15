package com.conductor.memory.tool;

import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.AgentToolProvider;
import com.conductor.agent.tool.ToolInvocationContext;
import com.conductor.agent.tool.ToolResult;
import com.conductor.memory.AgentMemory;
import com.conductor.memory.AgentMemoryRepository;
import com.conductor.memory.MemoryRetriever;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tool source {@code "memory"} -- exposes read-only access to the workspace's long-term memory (see
 * {@code com.conductor.memory}) as a single {@link AgentTool}, mirroring {@link
 * com.conductor.knowledge.tool.KnowledgeToolProvider}'s shape. Retrieval always goes through {@link
 * MemoryRetriever} (never {@link AgentMemoryRepository}'s search methods directly), matching {@link
 * MemoryRetriever}'s own contract. Gated on {@code conductor.memory.enabled} -- {@link #available}
 * returns nothing when the feature is off.
 *
 * <p>No {@link #claudeCodeToolName} override: this tool has no Conductor MCP twin in v1 (memory is only
 * reachable via the {@code api} runtime today), so the {@link AgentToolProvider} default of {@link
 * Optional#empty()} already gives the correct answer -- same convention {@code CoordinatorToolProvider}
 * uses for {@code ask_agent} and friends.
 */
@Component
public class MemoryToolProvider implements AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(MemoryToolProvider.class);
    private static final String SOURCE_ID = "memory";
    private static final String SEARCH = "search_memory";
    private static final int DEFAULT_LIMIT = 8;
    private static final int MAX_LIMIT = 20;

    private final MemoryRetriever retriever;
    private final AgentMemoryRepository repository;
    private final ObjectMapper objectMapper;
    private final boolean enabled;

    public MemoryToolProvider(MemoryRetriever retriever, AgentMemoryRepository repository,
                               ObjectMapper objectMapper,
                               @Value("${conductor.memory.enabled:true}") boolean enabled) {
        this.retriever = retriever;
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<AgentTool> available(String projectId) {
        if (!enabled) {
            return List.of();
        }
        return List.of(new SearchMemoryTool());
    }

    @Override
    public Optional<AgentTool> resolve(String projectId, String toolId) {
        if (!enabled) {
            return Optional.empty();
        }
        return available(projectId).stream().filter(t -> t.id().equals(toolId)).findFirst();
    }

    private final class SearchMemoryTool implements AgentTool {

        @Override
        public String id() {
            return SOURCE_ID + ":" + SEARCH;
        }

        @Override
        public String name() {
            return SEARCH;
        }

        @Override
        public String description() {
            return "Search the workspace's long-term memory (facts, decisions, preferences, events "
                    + "extracted from past agent conversations). Returns scored matches; memories may be "
                    + "stale -- prefer the knowledge base for authoritative documentation.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("q", Map.of("type", "string", "description", "Search query."));
            properties.put("limit", Map.of("type", "integer",
                    "description", "Max results (default " + DEFAULT_LIMIT + ", max " + MAX_LIMIT + ")."));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", properties);
            schema.put("required", List.of("q"));
            return schema;
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                String q = arguments.get("q") == null ? null : arguments.get("q").toString();
                int limit = arguments.get("limit") instanceof Number n
                        ? Math.min(Math.max(n.intValue(), 1), MAX_LIMIT)
                        : DEFAULT_LIMIT;

                List<MemoryRetriever.ScoredMemory> results = retriever.retrieve(context.projectId(), q, limit);

                List<Map<String, Object>> rows = new ArrayList<>();
                List<String> ids = new ArrayList<>();
                for (MemoryRetriever.ScoredMemory scored : results) {
                    rows.add(toRow(scored));
                    ids.add(scored.memory().getId());
                }
                if (!ids.isEmpty()) {
                    repository.bumpAccess(ids);
                }
                return ToolResult.ok(objectMapper.writeValueAsString(rows));
            } catch (Exception e) {
                log.warn("MemoryToolProvider search_memory failed: {}", e.getMessage());
                return ToolResult.error("search_memory failed: " + e.getMessage());
            }
        }

        private Map<String, Object> toRow(MemoryRetriever.ScoredMemory scored) {
            AgentMemory memory = scored.memory();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", memory.getId());
            row.put("content", memory.getContent());
            row.put("type", memory.getMemoryType().name());
            row.put("status", memory.getStatus().name());
            row.put("importance", memory.getImportance());
            row.put("agentId", memory.getAgentId());
            row.put("createdAt", memory.getCreatedAt() == null ? null : memory.getCreatedAt().toString());
            row.put("score", scored.score());
            return row;
        }
    }
}
