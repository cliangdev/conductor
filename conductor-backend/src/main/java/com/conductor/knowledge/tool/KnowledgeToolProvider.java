package com.conductor.knowledge.tool;

import com.conductor.agent.tool.AgentTool;
import com.conductor.agent.tool.AgentToolProvider;
import com.conductor.agent.tool.ToolInvocationContext;
import com.conductor.agent.tool.ToolResult;
import com.conductor.knowledge.Actor;
import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.KnowledgeSourceView;
import com.conductor.knowledge.domain.KnowledgeDomain;
import com.conductor.knowledge.domain.KnowledgeDomainService;
import com.conductor.knowledge.page.KnowledgeConflictException;
import com.conductor.knowledge.page.KnowledgePageService;
import com.conductor.knowledge.page.KnowledgeSearchService;
import com.conductor.knowledge.page.PageView;
import com.conductor.knowledge.page.PageWrite;
import com.conductor.knowledge.page.PageWriteResult;
import com.conductor.knowledge.page.SearchHit;
import com.conductor.knowledge.page.SkippedSource;
import com.conductor.service.ProjectSettingsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tool source {@code "knowledge"} — exposes the Knowledge Center's six MCP tools
 * ({@code conductor-tools/src/mcp/tools/knowledge.ts}) as {@link AgentTool}s, so the
 * {@code knowledge-librarian} agent (see {@code KnowledgeWorkflowProvisioner}) can call them on either
 * {@code agent}-step runtime. Tool names are bare (no {@code mcp__conductor__} prefix) so the same
 * system prompt works whether the agent runs the {@code api} runtime (this provider's {@link
 * #invoke}) or the {@code claude-code} runtime ({@link #claudeCodeToolName} maps to the equivalent MCP
 * tool the container's Conductor MCP server exposes).
 *
 * <p>Gated on {@link ProjectSettingsService#isKnowledgeEnabled}: {@link #available} returns nothing
 * (and {@link #resolve} nothing) for a project that hasn't turned on the Knowledge Center, mirroring
 * every other knowledge producer's gate.
 *
 * <p>No tool truncates its result — unlike {@code ConnectorToolProvider}'s ~8KB clamp on arbitrary
 * connector payloads, every result here is JSON the model must parse back in full (page content it
 * must merge, source payloads it must file, the conflict shape), and a byte-sliced JSON fragment is
 * strictly worse than a large one. The MCP equivalents never truncate either; the librarian's batch
 * (≤10 sources × ≤64KB) is the sizing contract on both runtimes.
 */
@Component
public class KnowledgeToolProvider implements AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeToolProvider.class);
    private static final String SOURCE_ID = "knowledge";

    private static final String READ_PAGES = "read_knowledge_pages";
    private static final String READ_SOURCES = "read_knowledge_sources";
    private static final String SEARCH = "search_knowledge";
    private static final String WRITE_PAGES = "write_knowledge_pages";
    private static final String LIST_DOMAINS = "list_knowledge_domains";
    private static final String SUGGEST_DOMAIN = "suggest_knowledge_domain";

    /** The conflict-result {@code message} field, verbatim from {@code knowledge.ts#parseConflictBody}'s
     *  caller — the model-facing guidance is identical regardless of which runtime hit the conflict. */
    private static final String CONFLICT_MESSAGE =
            "Version conflict on one or more pages — re-read them with read_knowledge_pages, merge your "
                    + "changes into the returned content, and retry write_knowledge_pages once with the "
                    + "returned currentVersion as baseVersion.";

    private final ProjectSettingsService projectSettingsService;
    private final KnowledgePageService pageService;
    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeSearchService searchService;
    private final KnowledgeDomainService domainService;
    private final ObjectMapper objectMapper;

    public KnowledgeToolProvider(ProjectSettingsService projectSettingsService,
                                 KnowledgePageService pageService,
                                 KnowledgeIngestionService ingestionService,
                                 KnowledgeSearchService searchService,
                                 KnowledgeDomainService domainService,
                                 ObjectMapper objectMapper) {
        this.projectSettingsService = projectSettingsService;
        this.pageService = pageService;
        this.ingestionService = ingestionService;
        this.searchService = searchService;
        this.domainService = domainService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<AgentTool> available(String projectId) {
        if (!projectSettingsService.isKnowledgeEnabled(projectId)) {
            return List.of();
        }
        return List.of(new ReadPagesTool(), new ReadSourcesTool(), new SearchTool(), new WritePagesTool(),
                new ListDomainsTool(), new SuggestDomainTool());
    }

    @Override
    public Optional<AgentTool> resolve(String projectId, String toolId) {
        if (!projectSettingsService.isKnowledgeEnabled(projectId)) {
            return Optional.empty();
        }
        return available(projectId).stream().filter(t -> t.id().equals(toolId)).findFirst();
    }

    @Override
    public Optional<String> claudeCodeToolName(String toolId) {
        String bareName = bareNameOf(toolId);
        return bareName == null ? Optional.empty() : Optional.of("mcp__conductor__" + bareName);
    }

    private String bareNameOf(String toolId) {
        if (toolId == null || !toolId.startsWith(SOURCE_ID + ":")) return null;
        String bare = toolId.substring(SOURCE_ID.length() + 1);
        return switch (bare) {
            case READ_PAGES, READ_SOURCES, SEARCH, WRITE_PAGES, LIST_DOMAINS, SUGGEST_DOMAIN -> bare;
            default -> null;
        };
    }

    // ---- tools ----

    private abstract class KnowledgeAgentTool implements AgentTool {
        private final String bareName;

        KnowledgeAgentTool(String bareName) {
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

    private final class ReadPagesTool extends KnowledgeAgentTool {
        ReadPagesTool() { super(READ_PAGES); }

        @Override
        public String description() {
            return "Fetch full Knowledge Center wiki page content by path. Pass [\"index.md\"] or "
                    + "[\"log.md\"] for the virtual orientation pages. Each returned page's `version` "
                    + "feeds `baseVersion` on a subsequent write_knowledge_pages call.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("paths", stringArraySchema("Page paths to fetch."));
            return objectSchema(properties, List.of("paths"));
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                List<String> paths = stringList(arguments.get("paths"));
                List<PageView> pages = pageService.getPages(context.projectId(), paths);
                return ToolResult.ok(objectMapper.writeValueAsString(pages));
            } catch (Exception e) {
                log.warn("KnowledgeToolProvider read_knowledge_pages failed: {}", e.getMessage());
                return ToolResult.error("read_knowledge_pages failed: " + e.getMessage());
            }
        }
    }

    private final class ReadSourcesTool extends KnowledgeAgentTool {
        ReadSourcesTool() { super(READ_SOURCES); }

        @Override
        public String description() {
            return "Fetch inbox knowledge sources by id, with offloaded payloads resolved inline.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("ids", stringArraySchema("Source ids to fetch."));
            return objectSchema(properties, List.of("ids"));
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                List<String> ids = stringList(arguments.get("ids"));
                List<KnowledgeSourceView> sources = ingestionService.getSources(context.projectId(), ids);
                return ToolResult.ok(objectMapper.writeValueAsString(sources));
            } catch (Exception e) {
                log.warn("KnowledgeToolProvider read_knowledge_sources failed: {}", e.getMessage());
                return ToolResult.error("read_knowledge_sources failed: " + e.getMessage());
            }
        }
    }

    private final class SearchTool extends KnowledgeAgentTool {
        SearchTool() { super(SEARCH); }

        @Override
        public String description() {
            return "Full-text search over the wiki bundle — path, type, title, description, snippet, "
                    + "rank. Use for orientation before reading a page.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("q", Map.of("type", "string", "description", "Search query."));
            properties.put("type", Map.of("type", "string", "description", "Optional page-type filter."));
            properties.put("pathPrefix", Map.of("type", "string", "description", "Optional path-prefix filter."));
            properties.put("limit", Map.of("type", "integer", "description", "Max results (default 20)."));
            return objectSchema(properties, List.of("q"));
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                String q = stringArg(arguments.get("q"));
                String type = stringArg(arguments.get("type"));
                String pathPrefix = stringArg(arguments.get("pathPrefix"));
                Integer limit = arguments.get("limit") instanceof Number n ? n.intValue() : null;
                List<SearchHit> hits = searchService.search(context.projectId(), q, type, pathPrefix, limit);
                return ToolResult.ok(objectMapper.writeValueAsString(hits));
            } catch (Exception e) {
                log.warn("KnowledgeToolProvider search_knowledge failed: {}", e.getMessage());
                return ToolResult.error("search_knowledge failed: " + e.getMessage());
            }
        }
    }

    private final class WritePagesTool extends KnowledgeAgentTool {
        WritePagesTool() { super(WRITE_PAGES); }

        @Override
        public String description() {
            return "Atomic batch create/update/delete of wiki pages, plus settling the ingestion "
                    + "sources this batch reviewed. `sourceIds` are sources you filed into a page "
                    + "(marked PROCESSED); `skipped` (with a required reason each) are sources you "
                    + "reviewed and deliberately did not file (marked SKIPPED — the reason is shown to "
                    + "a human in the Inbox). Both may be set in one call; a source id belongs to "
                    + "exactly one — the same id in both is rejected. `writes` may be empty when "
                    + "`sourceIds`/`skipped` cover the batch, to settle sources with no page change. A "
                    + "stale write (baseVersion mismatch) returns {conflict: true, conflicts: [...]} "
                    + "instead of throwing — merge and retry once. Verify with read_knowledge_sources.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> writeItemProps = new LinkedHashMap<>();
            writeItemProps.put("path", Map.of("type", "string"));
            writeItemProps.put("content", Map.of("type", "string"));
            writeItemProps.put("baseVersion", Map.of("type", "integer"));
            writeItemProps.put("delete", Map.of("type", "boolean"));
            Map<String, Object> writeItemSchema = objectSchema(writeItemProps, List.of("path"));

            Map<String, Object> skippedItemProps = new LinkedHashMap<>();
            skippedItemProps.put("sourceId", Map.of("type", "string"));
            skippedItemProps.put("reason", Map.of("type", "string",
                    "description", "Why this source wasn't filed — required, shown to a human in the Inbox."));
            Map<String, Object> skippedItemSchema = objectSchema(skippedItemProps, List.of("sourceId", "reason"));

            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("writes", Map.of("type", "array", "items", writeItemSchema));
            properties.put("sourceIds", stringArraySchema("Ingestion source ids this batch resolves; "
                    + "atomically marks them PROCESSED."));
            properties.put("skipped", Map.of("type", "array", "items", skippedItemSchema, "description",
                    "Ingestion sources this batch reviewed and deliberately did not file; atomically "
                            + "marks them SKIPPED with the given reason."));
            return objectSchema(properties, List.of("writes"));
        }

        @Override
        @SuppressWarnings("unchecked")
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                List<Object> rawWrites = arguments.get("writes") instanceof List<?> l
                        ? (List<Object>) l : List.of();
                List<PageWrite> writes = new ArrayList<>();
                for (Object raw : rawWrites) {
                    if (!(raw instanceof Map<?, ?> m)) continue;
                    String path = stringArg(m.get("path"));
                    String content = stringArg(m.get("content"));
                    Integer baseVersion = m.get("baseVersion") instanceof Number n ? n.intValue() : null;
                    boolean delete = Boolean.TRUE.equals(m.get("delete"));
                    writes.add(new PageWrite(path, content, baseVersion, delete));
                }
                List<String> sourceIds = stringList(arguments.get("sourceIds"));

                List<Object> rawSkipped = arguments.get("skipped") instanceof List<?> l
                        ? (List<Object>) l : List.of();
                List<SkippedSource> skipped = new ArrayList<>();
                for (Object raw : rawSkipped) {
                    if (!(raw instanceof Map<?, ?> m)) continue;
                    skipped.add(new SkippedSource(stringArg(m.get("sourceId")), stringArg(m.get("reason"))));
                }

                Actor actor = new Actor("agent", context.agentId(), null);
                List<PageWriteResult> results =
                        pageService.batchWrite(context.projectId(), writes, sourceIds, skipped, actor);

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("results", results);
                return ToolResult.ok(objectMapper.writeValueAsString(response));

            } catch (KnowledgeConflictException e) {
                return ToolResult.ok(conflictJson(e));
            } catch (Exception e) {
                log.warn("KnowledgeToolProvider write_knowledge_pages failed: {}", e.getMessage());
                return ToolResult.error("write_knowledge_pages failed: " + e.getMessage());
            }
        }

        /** Mirrors {@code knowledge.ts#writeKnowledgePages}'s conflict result shape exactly — key names
         *  and structure the librarian's system prompt (and any other caller) already expects. */
        private String conflictJson(KnowledgeConflictException e) {
            List<Map<String, Object>> conflicts = new ArrayList<>();
            for (KnowledgeConflictException.Conflict c : e.conflicts()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("path", c.path());
                m.put("currentVersion", c.currentVersion());
                m.put("currentContent", c.currentContent());
                conflicts.add(m);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("conflict", true);
            response.put("conflicts", conflicts);
            response.put("message", CONFLICT_MESSAGE);
            try {
                return objectMapper.writeValueAsString(response);
            } catch (Exception ex) {
                return "{\"conflict\":true,\"message\":\"" + CONFLICT_MESSAGE + "\"}";
            }
        }
    }

    private final class ListDomainsTool extends KnowledgeAgentTool {
        ListDomainsTool() { super(LIST_DOMAINS); }

        @Override
        public String description() {
            return "List this project's knowledge domains — slug, displayName, description, pathPrefix, "
                    + "schemaPagePath, sourceTypePatterns, state, owningAgentSlug. Call before "
                    + "suggest_knowledge_domain to check whether a domain (including a DISMISSED one) "
                    + "already exists, and to see each domain's filing conventions.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            return objectSchema(Map.of(), List.of());
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                List<Map<String, Object>> rows = domainService.list(context.projectId()).stream()
                        .map(KnowledgeToolProvider.this::toDomainRow)
                        .toList();
                return ToolResult.ok(objectMapper.writeValueAsString(rows));
            } catch (Exception e) {
                log.warn("KnowledgeToolProvider list_knowledge_domains failed: {}", e.getMessage());
                return ToolResult.error("list_knowledge_domains failed: " + e.getMessage());
            }
        }
    }

    private final class SuggestDomainTool extends KnowledgeAgentTool {
        SuggestDomainTool() { super(SUGGEST_DOMAIN); }

        @Override
        public String description() {
            return "Raise a gap report for a domain not yet in the registry. Claim-or-return on slug — "
                    + "calling this again for the same slug is safe and returns the existing row instead "
                    + "of erroring or resetting it. A DISMISSED result means an admin already declined "
                    + "this slug — do not call again for it. Verify with list_knowledge_domains.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("slug", Map.of("type", "string",
                    "description", "Lowercase, hyphenated (^[a-z0-9][a-z0-9-]*$) — becomes the domain's wiki path prefix."));
            properties.put("displayName", Map.of("type", "string"));
            properties.put("reason", Map.of("type", "string",
                    "description", "Why this domain is needed — shown to the admin reviewing the gap report."));
            properties.put("description", Map.of("type", "string", "description", "Optional longer description of the domain."));
            properties.put("sourceTypePatterns",
                    stringArraySchema("Optional glob patterns to seed routing with, if known upfront."));
            return objectSchema(properties, List.of("slug", "displayName", "reason"));
        }

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                String slug = stringArg(arguments.get("slug"));
                String displayName = stringArg(arguments.get("displayName"));
                String reason = stringArg(arguments.get("reason"));
                String description = stringArg(arguments.get("description"));
                List<String> patterns = stringList(arguments.get("sourceTypePatterns"));
                KnowledgeDomainService.SuggestResult result = domainService.suggest(context.projectId(), slug,
                        displayName, description, reason, patterns, context.agentId());

                Map<String, Object> response = new LinkedHashMap<>();
                response.put("slug", result.domain().getSlug());
                response.put("state", result.domain().getState().name());
                response.put("created", result.created());
                return ToolResult.ok(objectMapper.writeValueAsString(response));
            } catch (Exception e) {
                log.warn("KnowledgeToolProvider suggest_knowledge_domain failed: {}", e.getMessage());
                return ToolResult.error("suggest_knowledge_domain failed: " + e.getMessage());
            }
        }
    }

    private Map<String, Object> toDomainRow(KnowledgeDomain d) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("slug", d.getSlug());
        row.put("displayName", d.getDisplayName());
        row.put("description", d.getDescription());
        row.put("pathPrefix", d.getPathPrefix());
        row.put("schemaPagePath", d.getSchemaPagePath());
        row.put("sourceTypePatterns", d.getSourceTypePatterns());
        row.put("state", d.getState().name());
        row.put("owningAgentSlug", d.getOwningAgentSlug());
        return row;
    }

    // ---- schema/arg helpers ----

    private Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private Map<String, Object> stringArraySchema(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "array");
        schema.put("items", Map.of("type", "string"));
        schema.put("description", description);
        return schema;
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object v : list) {
            if (v != null) out.add(v.toString());
        }
        return out;
    }

    private String stringArg(Object value) {
        return value == null ? null : value.toString();
    }
}
