package com.conductor.agent.tool;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.entity.DocFolder;
import com.conductor.entity.ProjectDoc;
import com.conductor.exception.ConflictException;
import com.conductor.security.WorkflowRunAuthenticationToken;
import com.conductor.service.CommentService;
import com.conductor.service.CommentTarget;
import com.conductor.service.DocFolderService;
import com.conductor.service.DocumentService;
import com.conductor.service.ProjectActor;
import com.conductor.service.ProjectDocService;
import com.conductor.service.view.CommentReplyView;
import com.conductor.service.view.CommentWithRepliesView;
import com.conductor.service.view.DocumentView;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tool source {@code "comment"}: the in-process twins of the Conductor MCP comment and document tools an agent
 * needs to work through a comment thread ({@code conductor-tools/src/mcp/tools/comments.ts},
 * {@code documents.ts}, {@code project-docs.ts}). Names and argument shapes are the MCP ones, so the same task
 * prompt works on the {@code api} runtime (this provider) and the {@code claude-code} runtime
 * ({@link #claudeCodeToolName} maps 1:1 to {@code mcp__conductor__<name>}).
 *
 * <p>Tools: {@code list_comments}, {@code respond_to_comment}, {@code read_document}, {@code write_document},
 * {@code read_project_doc}, {@code write_project_doc}. Every write that takes {@code baseContentHash} honours it
 * and returns a plain-words conflict (nothing written) when the document changed since the read, exactly as the
 * REST API's 409 does. Whatever the agent writes is attributed to it: replies carry its id, name and avatar.
 *
 * <p>An agent can be bound to these like any other tool; the comment-agent workflow grants them to one run with
 * {@code with.grant_tools}.
 */
@Component
public class CommentToolProvider implements AgentToolProvider {

    private static final Logger log = LoggerFactory.getLogger(CommentToolProvider.class);
    static final String SOURCE_ID = "comment";

    static final String LIST_COMMENTS = "list_comments";
    static final String RESPOND_TO_COMMENT = "respond_to_comment";
    static final String READ_DOCUMENT = "read_document";
    static final String WRITE_DOCUMENT = "write_document";
    static final String READ_PROJECT_DOC = "read_project_doc";
    static final String WRITE_PROJECT_DOC = "write_project_doc";
    private static final List<String> NAMES = List.of(LIST_COMMENTS, RESPOND_TO_COMMENT, READ_DOCUMENT,
            WRITE_DOCUMENT, READ_PROJECT_DOC, WRITE_PROJECT_DOC);

    static final String CONFLICT_MESSAGE =
            "Document changed since you read it — read it again, redo your edit on the new content, then write once more.";
    private static final int QUOTE_PREVIEW_CHARS = 200;

    private final CommentService commentService;
    private final DocumentService documentService;
    private final ProjectDocService projectDocService;
    private final DocFolderService docFolderService;
    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate readTx;

    public CommentToolProvider(CommentService commentService, DocumentService documentService,
                               ProjectDocService projectDocService, DocFolderService docFolderService,
                               AgentRepository agentRepository, ObjectMapper objectMapper,
                               PlatformTransactionManager transactionManager) {
        this.commentService = commentService;
        this.documentService = documentService;
        this.projectDocService = projectDocService;
        this.docFolderService = docFolderService;
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setReadOnly(true);
    }

    @Override
    public String sourceId() {
        return SOURCE_ID;
    }

    @Override
    public List<AgentTool> available(String projectId) {
        return List.of(new ListCommentsTool(), new RespondTool(), new ReadDocumentTool(), new WriteDocumentTool(),
                new ReadProjectDocTool(), new WriteProjectDocTool());
    }

    @Override
    public Optional<AgentTool> resolve(String projectId, String toolId) {
        return available(projectId).stream().filter(t -> t.id().equals(toolId)).findFirst();
    }

    @Override
    public Optional<String> claudeCodeToolName(String toolId) {
        if (toolId == null || !toolId.startsWith(SOURCE_ID + ":")) {
            return Optional.empty();
        }
        String bare = toolId.substring(SOURCE_ID.length() + 1);
        return NAMES.contains(bare) ? Optional.of("mcp__conductor__" + bare) : Optional.empty();
    }

    // ---- shared ----

    private abstract class CommentAgentTool implements AgentTool {
        private final String bareName;

        CommentAgentTool(String bareName) {
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

        @Override
        public ToolResult invoke(Map<String, Object> arguments, ToolInvocationContext context) {
            try {
                return run(arguments == null ? Map.of() : arguments, context);
            } catch (ConflictException e) {
                return ToolResult.error(CONFLICT_MESSAGE);
            } catch (Exception e) {
                log.warn("CommentToolProvider {} failed: {}", bareName, e.getMessage());
                return ToolResult.error(bareName + " failed: " + e.getMessage());
            }
        }

        abstract ToolResult run(Map<String, Object> args, ToolInvocationContext context) throws Exception;
    }

    private ProjectActor actorFor(ToolInvocationContext context) {
        Agent agent = context.agentId() == null ? null : agentRepository.findById(context.agentId()).orElse(null);
        return agent == null ? ProjectActor.agent("Agent") : ProjectActor.agent(agent.getName(), agent.getId());
    }

    private ToolResult json(Object value) throws Exception {
        return ToolResult.ok(objectMapper.writeValueAsString(value));
    }

    private static String str(Object value) {
        return value == null ? null : value.toString();
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean b ? b : value != null && Boolean.parseBoolean(value.toString());
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    /**
     * Runs {@code write} as a project-scoped machine, for the one service call that checks the caller's
     * credentials (a Work Item document write requires an editor, and a machine credential passes). An agent run
     * in-process has no HTTP request, so there is no security context to find; the previous one is restored.
     */
    private <T> T asProjectMachine(String projectId, String runId, java.util.function.Supplier<T> write) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext machine = new SecurityContextImpl();
        machine.setAuthentication(new WorkflowRunAuthenticationToken(projectId, runId));
        SecurityContextHolder.setContext(machine);
        try {
            return write.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    // ---- list_comments ----

    private final class ListCommentsTool extends CommentAgentTool {
        ListCommentsTool() { super(LIST_COMMENTS); }

        @Override
        public String description() {
            return "List comment threads on one document: a Work Item document (issueId, optionally filename — omit "
                    + "it for every document of the item) or a project doc (docId). Open threads by default. Each "
                    + "thread has id, kind (QUOTE, LINE or DOCUMENT), the quote or line it is on, author, body, "
                    + "resolved and replies. Answer with respond_to_comment.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("issueId", string("Work Item ID"));
            p.put("filename", string("Document filename on the Work Item"));
            p.put("docId", string("Project doc ID — use instead of issueId"));
            p.put("status", Map.of("type", "string", "enum", List.of("open", "resolved", "all"),
                    "description", "Default open"));
            return objectSchema(p, List.of());
        }

        @Override
        ToolResult run(Map<String, Object> args, ToolInvocationContext context) throws Exception {
            String issueId = str(args.get("issueId"));
            String filename = str(args.get("filename"));
            String docId = str(args.get("docId"));
            String status = args.get("status") == null ? "open" : str(args.get("status"));
            CommentTarget target;
            if (docId != null && !docId.isBlank()) {
                target = new CommentTarget.ProjectDoc(docId);
            } else if (issueId != null && !issueId.isBlank()) {
                String documentId = filename == null || filename.isBlank() ? null
                        : documentService.getDocumentByFilename(context.projectId(), issueId, filename).id();
                target = new CommentTarget.WorkItemDocument(issueId, documentId);
            } else {
                return ToolResult.error("Provide issueId (with filename) for a Work Item document, or docId for a project doc");
            }
            CommentService.StatusFilter filter;
            try {
                filter = CommentService.StatusFilter.valueOf(status.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ToolResult.error("status must be open, resolved or all");
            }
            List<CommentWithRepliesView> threads =
                    commentService.listThreads(context.projectId(), target, filter, actorFor(context));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("count", threads.size());
            out.put("status", status);
            out.put("threads", threads.stream().map(CommentToolProvider.this::compact).toList());
            return json(out);
        }
    }

    private Map<String, Object> compact(CommentWithRepliesView t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", t.id());
        out.put("kind", t.kind().name());
        out.put("author", byline(t.authorName(), t.authorKind()));
        out.put("body", t.content());
        out.put("resolved", t.resolvedAt() != null);
        if (t.documentName() != null) {
            out.put("document", t.documentName());
        }
        String quote = t.anchor() != null ? t.anchor().exact() : t.quotedText();
        switch (t.kind()) {
            case QUOTE -> out.put("quote", clip(quote == null ? "" : quote));
            case LINE -> {
                out.put("line", t.lineNumber());
                if (quote != null && !quote.isEmpty()) {
                    out.put("lineText", clip(quote));
                }
            }
            default -> { }
        }
        if (t.anchorStatus() != null) {
            out.put("anchorStatus", t.anchorStatus());
        }
        if (t.lineStale() || t.outdated()) {
            out.put("stale", true);
        }
        if (!t.replies().isEmpty()) {
            List<Map<String, Object>> replies = new ArrayList<>();
            for (CommentReplyView r : t.replies()) {
                Map<String, Object> reply = new LinkedHashMap<>();
                reply.put("author", byline(r.authorName(), r.authorKind()));
                reply.put("body", r.content());
                replies.add(reply);
            }
            out.put("replies", replies);
        }
        return out;
    }

    private static String byline(String name, String kind) {
        return name == null ? null : name + ("AGENT".equals(kind) ? " (agent)" : "");
    }

    private static String clip(String s) {
        return s.length() > QUOTE_PREVIEW_CHARS ? s.substring(0, QUOTE_PREVIEW_CHARS) + "…" : s;
    }

    // ---- respond_to_comment ----

    private final class RespondTool extends CommentAgentTool {
        RespondTool() { super(RESPOND_TO_COMMENT); }

        @Override
        public String description() {
            return "Answer a comment thread: post a reply, resolve it, reopen it, or a reply plus one of those. "
                    + "Provide at least one of reply, resolve, reopen (not both resolve and reopen). Verify with "
                    + "list_comments.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("commentId", string("Thread ID (from list_comments)"));
            p.put("reply", string("Reply text"));
            p.put("resolve", Map.of("type", "boolean", "description", "true to resolve the thread"));
            p.put("reopen", Map.of("type", "boolean", "description", "true to reopen a resolved thread"));
            return objectSchema(p, List.of("commentId"));
        }

        @Override
        ToolResult run(Map<String, Object> args, ToolInvocationContext context) throws Exception {
            String commentId = str(args.get("commentId"));
            String reply = str(args.get("reply"));
            boolean resolve = bool(args.get("resolve"));
            boolean reopen = bool(args.get("reopen"));
            boolean hasReply = reply != null && !reply.isBlank();
            if (commentId == null || commentId.isBlank()) {
                return ToolResult.error("commentId is required");
            }
            if (resolve && reopen) {
                return ToolResult.error("Pass resolve or reopen, not both");
            }
            if (!hasReply && !resolve && !reopen) {
                return ToolResult.error("Provide reply, resolve: true, reopen: true, or a combination");
            }
            ProjectActor actor = actorFor(context);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("commentId", commentId);
            if (hasReply) {
                commentService.addReply(context.projectId(), commentId, reply, actor);
                out.put("replied", true);
            }
            if (resolve || reopen) {
                CommentWithRepliesView updated =
                        commentService.updateComment(context.projectId(), commentId, null, resolve, actor);
                out.put("resolved", updated.resolvedAt() != null);
            }
            return json(out);
        }
    }

    // ---- read_document / write_document ----

    private final class ReadDocumentTool extends CommentAgentTool {
        ReadDocumentTool() { super(READ_DOCUMENT); }

        @Override
        public String description() {
            return "Read a Work Item document's full text and its contentHash. Pass that hash as baseContentHash to "
                    + "write_document so the write is refused if someone edited the document meanwhile. For project "
                    + "docs use read_project_doc.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("issueId", string("Work Item ID"));
            p.put("filename", string("Document filename"));
            return objectSchema(p, List.of("issueId", "filename"));
        }

        @Override
        ToolResult run(Map<String, Object> args, ToolInvocationContext context) throws Exception {
            String issueId = str(args.get("issueId"));
            String filename = str(args.get("filename"));
            if (issueId == null || filename == null) {
                return ToolResult.error("issueId and filename are required");
            }
            DocumentView doc = documentService.getDocumentByFilename(context.projectId(), issueId, filename);
            if (doc.content() == null) {
                return ToolResult.error("\"" + filename + "\" has no readable text content (content type "
                        + doc.contentType() + ")");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("documentId", doc.id());
            out.put("filename", doc.filename());
            out.put("contentType", doc.contentType());
            out.put("contentHash", doc.contentHash());
            out.put("content", doc.content());
            return json(out);
        }
    }

    private final class WriteDocumentTool extends CommentAgentTool {
        WriteDocumentTool() { super(WRITE_DOCUMENT); }

        @Override
        public String description() {
            return "Create or update a Work Item document by filename with full content (upsert). Pass "
                    + "baseContentHash (from read_document) when revising an existing document; if the document "
                    + "changed since, nothing is written and you must read it again and redo the edit.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("issueId", string("Work Item (issue) ID"));
            p.put("filename", string("Document filename (e.g., prd.html, prd.md)"));
            p.put("content", string("Full document content"));
            p.put("contentType", string("MIME type (optional; defaults from the filename extension)"));
            p.put("baseContentHash", string("contentHash from read_document. If the document changed since, the "
                    + "write is refused — read again and redo"));
            return objectSchema(p, List.of("issueId", "filename", "content"));
        }

        @Override
        ToolResult run(Map<String, Object> args, ToolInvocationContext context) throws Exception {
            String issueId = str(args.get("issueId"));
            String filename = str(args.get("filename"));
            String content = str(args.get("content"));
            if (issueId == null || filename == null || content == null) {
                return ToolResult.error("issueId, filename and content are required");
            }
            String contentType = str(args.get("contentType")) != null ? str(args.get("contentType"))
                    : contentTypeFor(filename);
            String baseHash = str(args.get("baseContentHash"));
            boolean created = asProjectMachine(context.projectId(), context.agentRunId(),
                    () -> documentService.upsertDocumentByFilename(context.projectId(), issueId, filename, content,
                            contentType, baseHash == null || baseHash.isBlank() ? null : baseHash));
            DocumentView written = documentService.getDocumentByFilename(context.projectId(), issueId, filename);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("documentId", written.id());
            out.put("filename", filename);
            out.put("created", created);
            out.put("contentHash", written.contentHash());
            return json(out);
        }
    }

    /** The defaults {@code documents.ts#contentTypeFor} applies. */
    static String contentTypeFor(String filename) {
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "text/markdown";
        if (lower.endsWith(".json")) return "application/json";
        return "text/plain";
    }

    // ---- read_project_doc / write_project_doc ----

    private record PathTarget(String folderId, String title) { }

    /** Splits {@code Plans/Q3 Roadmap} into its folder (every folder must exist) and title. */
    private PathTarget resolvePath(String projectId, String path) {
        List<String> segments = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isBlank()) {
                segments.add(part.trim());
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Path is empty — expected something like \"Plans/Q3 Roadmap\"");
        }
        String title = segments.remove(segments.size() - 1);
        // Parent ids are read inside one transaction, so no lazy proxy is touched outside it.
        String folderId = readTx.execute(status -> {
            String parentId = null;
            List<DocFolder> all = docFolderService.getFolders(projectId);
            for (String segment : segments) {
                String wantedParent = parentId;
                DocFolder match = all.stream()
                        .filter(f -> f.getName().equals(segment))
                        .filter(f -> java.util.Objects.equals(
                                f.getParent() == null ? null : f.getParent().getId(), wantedParent))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Folder \"" + segment + "\" not found in path \"" + path + "\""));
                parentId = match.getId();
            }
            return parentId;
        });
        return new PathTarget(folderId, title);
    }

    private ProjectDoc findDoc(String projectId, String path, String docId) {
        if (docId != null && !docId.isBlank()) {
            return projectDocService.getDoc(projectId, docId);
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Provide path or docId");
        }
        PathTarget target = resolvePath(projectId, path);
        return projectDocService.getDocs(projectId, target.folderId(), false).stream()
                .filter(d -> d.getTitle().equals(target.title()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No project doc at \"" + path + "\""));
    }

    private final class ReadProjectDocTool extends CommentAgentTool {
        ReadProjectDocTool() { super(READ_PROJECT_DOC); }

        @Override
        public String description() {
            return "Read one project document by path (\"Plans/Q3 Roadmap\") or docId. Returns full Markdown content "
                    + "and contentHash; pass the hash as baseContentHash to write_project_doc.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("path", string("Document path, e.g. \"Plans/Q3 Roadmap\""));
            p.put("docId", string("Document ID — use instead of path when the title contains \"/\""));
            return objectSchema(p, List.of());
        }

        @Override
        ToolResult run(Map<String, Object> args, ToolInvocationContext context) throws Exception {
            ProjectDoc doc = findDoc(context.projectId(), str(args.get("path")), str(args.get("docId")));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("docId", doc.getId());
            out.put("title", doc.getTitle());
            // Stored content, image markers and all: it must round-trip through write_project_doc unchanged.
            out.put("content", doc.getContent() == null ? "" : doc.getContent());
            out.put("contentHash", ProjectDocService.contentHash(doc));
            return json(out);
        }
    }

    private final class WriteProjectDocTool extends CommentAgentTool {
        WriteProjectDocTool() { super(WRITE_PROJECT_DOC); }

        @Override
        public String description() {
            return "Create or replace a project document at a path (upsert; folders must already exist). Replaces the "
                    + "whole document. Pass baseContentHash (from read_project_doc) when revising; if the doc "
                    + "changed since, nothing is written and you must read it again and redo the edit.";
        }

        @Override
        public Map<String, Object> inputSchema() {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("path", string("Destination path, e.g. \"Plans/Q3 Roadmap\""));
            p.put("docId", string("Document ID — optional; use instead of path to revise a doc whose title contains \"/\""));
            p.put("content", string("Full Markdown content"));
            p.put("baseContentHash", string("contentHash from read_project_doc. If the doc changed since, the write "
                    + "is refused — read again and redo"));
            return objectSchema(p, List.of("content"));
        }

        @Override
        ToolResult run(Map<String, Object> args, ToolInvocationContext context) throws Exception {
            String projectId = context.projectId();
            String path = str(args.get("path"));
            String docId = str(args.get("docId"));
            String content = str(args.get("content"));
            if (content == null) {
                return ToolResult.error("content is required");
            }
            if ((path == null || path.isBlank()) && (docId == null || docId.isBlank())) {
                return ToolResult.error("Provide path or docId");
            }
            String baseHash = str(args.get("baseContentHash"));
            if (baseHash != null && baseHash.isBlank()) {
                baseHash = null;
            }
            ProjectActor actor = actorFor(context);

            ProjectDoc existing = null;
            PathTarget target = null;
            if (docId != null && !docId.isBlank()) {
                existing = projectDocService.getDoc(projectId, docId);
            } else {
                target = resolvePath(projectId, path);
                PathTarget wanted = target;
                existing = projectDocService.getDocs(projectId, wanted.folderId(), false).stream()
                        .filter(d -> d.getTitle().equals(wanted.title())).findFirst().orElse(null);
            }
            ProjectDoc written;
            boolean created = existing == null;
            if (existing != null) {
                written = projectDocService.updateDoc(projectId, existing.getId(), content, actor, baseHash);
            } else {
                written = projectDocService.createDoc(projectId, target.folderId(), target.title(), content, actor);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("docId", written.getId());
            out.put("created", created);
            out.put("contentHash", ProjectDocService.contentHash(written));
            return json(out);
        }
    }
}
