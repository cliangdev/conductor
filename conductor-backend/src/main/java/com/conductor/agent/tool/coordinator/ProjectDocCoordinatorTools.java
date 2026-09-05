package com.conductor.agent.tool.coordinator;

import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.ToolInvocationContext;
import com.conductor.agent.tool.ToolResult;
import com.conductor.entity.ProjectDoc;
import com.conductor.service.DocImageMarkers;
import com.conductor.service.ProjectDocService;
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
 * The project docs tools: {@code search_project_docs}, {@code read_project_doc}. Both apply {@link
 * DocImageMarkers#summarize} to a doc's content before it reaches the model -- the REST path ({@code
 * ProjectDocsController}) always runs a doc through {@code summarize} (search) or {@code render} (full
 * read) before returning it; skipping that step here would leak internal {@code conductor-image:projects/
 * ...} storage-path markers into a model's context and, via a Discord conversation, an end user.
 */
final class ProjectDocCoordinatorTools {

    private static final Logger log = LoggerFactory.getLogger(ProjectDocCoordinatorTools.class);

    static final String SEARCH_PROJECT_DOCS = "search_project_docs";
    static final String READ_PROJECT_DOC = "read_project_doc";

    private final ProjectDocService projectDocService;
    private final ObjectMapper objectMapper;

    ProjectDocCoordinatorTools(ProjectDocService projectDocService, ObjectMapper objectMapper) {
        this.projectDocService = projectDocService;
        this.objectMapper = objectMapper;
    }

    List<AgentTool> tools() {
        return List.of(new SearchProjectDocsTool(), new ReadProjectDocTool());
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
                    row.put("snippet", ProjectDocService.extractSnippet(doc.getContent(), q));
                    rows.add(row);
                }
                return truncate(objectMapper.writeValueAsString(rows));
            } catch (Exception e) {
                log.warn("ProjectDocCoordinatorTools search_project_docs failed: {}", e.getMessage());
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
                row.put("content", DocImageMarkers.summarize(doc.getContent()));
                return truncate(objectMapper.writeValueAsString(row));
            } catch (EntityNotFoundException e) {
                return ToolResult.error("Project doc not found: " + arguments.get("id"));
            } catch (Exception e) {
                log.warn("ProjectDocCoordinatorTools read_project_doc failed: {}", e.getMessage());
                return ToolResult.error("read_project_doc failed: " + e.getMessage());
            }
        }
    }
}
