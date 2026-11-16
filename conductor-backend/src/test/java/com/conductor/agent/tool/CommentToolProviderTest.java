package com.conductor.agent.tool;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectDoc;
import com.conductor.exception.ConflictException;
import com.conductor.security.WorkflowRunAuthenticationToken;
import com.conductor.service.CommentService;
import com.conductor.service.CommentTarget;
import com.conductor.service.DocFolderService;
import com.conductor.service.DocumentContent;
import com.conductor.service.DocumentService;
import com.conductor.service.ProjectActor;
import com.conductor.service.ProjectDocService;
import com.conductor.service.view.CommentAnchor;
import com.conductor.service.view.CommentKind;
import com.conductor.service.view.CommentReplyView;
import com.conductor.service.view.CommentWithRepliesView;
import com.conductor.service.view.DocumentView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The in-process comment/document tools: same names and shapes as the MCP ones, honouring baseContentHash. */
class CommentToolProviderTest {

    static final String PROJECT = "p1";
    static final ToolInvocationContext CTX = new ToolInvocationContext(PROJECT, "a-1", "agent-run-1");

    CommentService commentService = mock(CommentService.class);
    DocumentService documentService = mock(DocumentService.class);
    ProjectDocService projectDocService = mock(ProjectDocService.class);
    DocFolderService docFolderService = mock(DocFolderService.class);
    AgentRepository agentRepository = mock(AgentRepository.class);
    ObjectMapper mapper = new ObjectMapper();
    CommentToolProvider provider = new CommentToolProvider(commentService, documentService, projectDocService,
            docFolderService, agentRepository, mapper, mock(PlatformTransactionManager.class));

    @BeforeEach
    void setUp() {
        Agent agent = new Agent();
        agent.setId("a-1");
        agent.setName("Editor Bot");
        when(agentRepository.findById("a-1")).thenReturn(Optional.of(agent));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private ToolResult call(String name, Map<String, Object> args) {
        return provider.resolve(PROJECT, "comment:" + name).orElseThrow().invoke(args, CTX);
    }

    private JsonNode json(ToolResult result) throws Exception {
        assertThat(result.ok()).as(result.payload()).isTrue();
        return mapper.readTree(result.payload());
    }

    // ---- registry ----

    @Test
    void offersExactlyTheSixToolsUnderTheCommentSource() {
        assertThat(provider.sourceId()).isEqualTo("comment");
        assertThat(provider.available(PROJECT)).extracting(AgentTool::id).containsExactly(
                "comment:list_comments", "comment:respond_to_comment", "comment:read_document",
                "comment:write_document", "comment:read_project_doc", "comment:write_project_doc");
        assertThat(provider.available(PROJECT)).extracting(AgentTool::name).containsExactly(
                "list_comments", "respond_to_comment", "read_document", "write_document", "read_project_doc",
                "write_project_doc");
    }

    @Test
    void everyToolMapsOneToOneOntoItsMcpTwin() {
        for (AgentTool tool : provider.available(PROJECT)) {
            assertThat(provider.claudeCodeToolName(tool.id())).contains("mcp__conductor__" + tool.name());
        }
        assertThat(provider.claudeCodeToolName("comment:add_comment")).isEmpty();
        assertThat(provider.claudeCodeToolName("knowledge:read_knowledge_pages")).isEmpty();
        assertThat(provider.resolve(PROJECT, "comment:nope")).isEmpty();
    }

    @Test
    void writeToolsAdvertiseBaseContentHashAndMatchTheMcpArgumentNames() {
        @SuppressWarnings("unchecked")
        Map<String, Object> docProps = (Map<String, Object>) provider.resolve(PROJECT, "comment:write_document")
                .orElseThrow().inputSchema().get("properties");
        assertThat(docProps).containsKeys("issueId", "filename", "content", "contentType", "baseContentHash");
        @SuppressWarnings("unchecked")
        Map<String, Object> projectProps = (Map<String, Object>) provider.resolve(PROJECT, "comment:write_project_doc")
                .orElseThrow().inputSchema().get("properties");
        assertThat(projectProps).containsKeys("path", "content", "baseContentHash");
        @SuppressWarnings("unchecked")
        Map<String, Object> respondProps = (Map<String, Object>) provider.resolve(PROJECT, "comment:respond_to_comment")
                .orElseThrow().inputSchema().get("properties");
        assertThat(respondProps).containsKeys("commentId", "reply", "resolve", "reopen");
    }

    // ---- list_comments ----

    @Test
    void listCommentsOnAProjectDocReturnsCompactThreadsAsTheAgent() throws Exception {
        CommentWithRepliesView thread = new CommentWithRepliesView(
                "c-1", null, "u-1", "tighten this", OffsetDateTime.now(), "Ann", 0, "Our goals", false,
                new CommentAnchor("Our goals", "p", "s", null, null), false, "Roadmap", null,
                List.of(new CommentReplyView("r-1", "c-1", null, "Which goals?", OffsetDateTime.now(), "Editor Bot",
                        "AGENT", null, null, List.of())),
                CommentKind.QUOTE, null, "pd-1", "h", "USER", null, null, null, null, "ANCHORED", List.of(), List.of(), null);
        when(commentService.listThreads(eq(PROJECT), any(), eq(CommentService.StatusFilter.OPEN), any()))
                .thenReturn(List.of(thread));

        JsonNode out = json(call("list_comments", Map.of("docId", "pd-1")));

        ArgumentCaptor<CommentTarget> target = ArgumentCaptor.forClass(CommentTarget.class);
        ArgumentCaptor<ProjectActor> actor = ArgumentCaptor.forClass(ProjectActor.class);
        verify(commentService).listThreads(eq(PROJECT), target.capture(), eq(CommentService.StatusFilter.OPEN),
                actor.capture());
        assertThat(target.getValue()).isEqualTo(new CommentTarget.ProjectDoc("pd-1"));
        assertThat(actor.getValue().agentId()).isEqualTo("a-1");
        assertThat(actor.getValue().label()).isEqualTo("Editor Bot");

        assertThat(out.get("count").asInt()).isEqualTo(1);
        JsonNode t = out.get("threads").get(0);
        assertThat(t.get("id").asText()).isEqualTo("c-1");
        assertThat(t.get("kind").asText()).isEqualTo("QUOTE");
        assertThat(t.get("quote").asText()).isEqualTo("Our goals");
        assertThat(t.get("author").asText()).isEqualTo("Ann");
        assertThat(t.get("resolved").asBoolean()).isFalse();
        assertThat(t.get("replies").get(0).get("author").asText()).isEqualTo("Editor Bot (agent)");
    }

    @Test
    void listCommentsOnAWorkItemDocumentResolvesTheFilename() throws Exception {
        when(documentService.getDocumentByFilename(PROJECT, "wi-1", "prd.md")).thenReturn(view("d-1", "prd.md", "x", "h"));
        when(commentService.listThreads(eq(PROJECT), any(), any(), any())).thenReturn(List.of());

        call("list_comments", Map.of("issueId", "wi-1", "filename", "prd.md", "status", "all"));

        verify(commentService).listThreads(eq(PROJECT), eq(new CommentTarget.WorkItemDocument("wi-1", "d-1")),
                eq(CommentService.StatusFilter.ALL), any());
    }

    @Test
    void listCommentsNeedsATargetAndAValidStatus() {
        assertThat(call("list_comments", Map.of()).ok()).isFalse();
        assertThat(call("list_comments", Map.of("docId", "pd-1", "status", "bogus")).payload())
                .contains("open, resolved or all");
    }

    // ---- respond_to_comment ----

    @Test
    void respondRepliesAsTheAgentAndNeverResolvesUnlessAsked() throws Exception {
        JsonNode out = json(call("respond_to_comment", Map.of("commentId", "c-1", "reply", "Changed: tightened.")));

        ArgumentCaptor<ProjectActor> actor = ArgumentCaptor.forClass(ProjectActor.class);
        verify(commentService).addReply(eq(PROJECT), eq("c-1"), eq("Changed: tightened."), actor.capture());
        assertThat(actor.getValue().agentId()).isEqualTo("a-1");
        verify(commentService, never()).updateComment(any(), any(), any(), any(), any());
        assertThat(out.get("replied").asBoolean()).isTrue();
        assertThat(out.has("resolved")).isFalse();
    }

    @Test
    void respondCanResolveOrReopenAndValidatesItsArguments() throws Exception {
        CommentWithRepliesView resolved = new CommentWithRepliesView("c-1", null, null, "x", OffsetDateTime.now(), "A",
                0, "", false, null, false, null, OffsetDateTime.now(), List.of());
        when(commentService.updateComment(eq(PROJECT), eq("c-1"), isNull(), eq(true), any())).thenReturn(resolved);

        JsonNode out = json(call("respond_to_comment", Map.of("commentId", "c-1", "resolve", true)));
        assertThat(out.get("resolved").asBoolean()).isTrue();

        assertThat(call("respond_to_comment", Map.of("commentId", "c-1")).ok()).isFalse();
        assertThat(call("respond_to_comment", Map.of("commentId", "c-1", "resolve", true, "reopen", true)).ok()).isFalse();
        assertThat(call("respond_to_comment", Map.of("reply", "x")).ok()).isFalse();
    }

    // ---- documents ----

    @Test
    void readDocumentReturnsTheTextAndItsHash() throws Exception {
        when(documentService.getDocumentByFilename(PROJECT, "wi-1", "prd.md"))
                .thenReturn(view("d-1", "prd.md", "# Goals", "abc123"));

        JsonNode out = json(call("read_document", Map.of("issueId", "wi-1", "filename", "prd.md")));

        assertThat(out.get("contentHash").asText()).isEqualTo("abc123");
        assertThat(out.get("content").asText()).isEqualTo("# Goals");
        assertThat(out.get("documentId").asText()).isEqualTo("d-1");
    }

    @Test
    void writeDocumentPassesTheBaseHashAndRunsAsAProjectMachineThenRestoresTheContext() throws Exception {
        SecurityContextHolder.clearContext();
        java.util.concurrent.atomic.AtomicReference<Object> seen = new java.util.concurrent.atomic.AtomicReference<>();
        when(documentService.upsertDocumentByFilename(eq(PROJECT), eq("wi-1"), eq("prd.md"), eq("new"),
                eq("text/markdown"), eq("abc123"))).thenAnswer(inv -> {
                    seen.set(SecurityContextHolder.getContext().getAuthentication());
                    return false;
                });
        when(documentService.getDocumentByFilename(PROJECT, "wi-1", "prd.md")).thenReturn(view("d-1", "prd.md", "new", "def456"));

        JsonNode out = json(call("write_document", Map.of("issueId", "wi-1", "filename", "prd.md", "content", "new",
                "baseContentHash", "abc123")));

        assertThat(seen.get()).isInstanceOf(WorkflowRunAuthenticationToken.class);
        assertThat(((WorkflowRunAuthenticationToken) seen.get()).getProjectId()).isEqualTo(PROJECT);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).as("restored").isNull();
        assertThat(out.get("contentHash").asText()).isEqualTo("def456");
        assertThat(out.get("created").asBoolean()).isFalse();
    }

    @Test
    void aStaleBaseHashIsRefusedWithTheRedoInstructionAndNothingIsClaimedWritten() {
        when(documentService.upsertDocumentByFilename(any(), any(), any(), any(), any(), eq("old")))
                .thenThrow(new ConflictException("Document changed since you read it — read it again before writing."));

        ToolResult result = call("write_document", Map.of("issueId", "wi-1", "filename", "prd.md", "content", "x",
                "baseContentHash", "old"));

        assertThat(result.ok()).isFalse();
        assertThat(result.payload()).isEqualTo(CommentToolProvider.CONFLICT_MESSAGE).contains("read it again");
        verify(documentService, never()).getDocumentByFilename(any(), any(), any());
    }

    @Test
    void contentTypeDefaultsFromTheFilenameLikeTheMcpTool() {
        assertThat(CommentToolProvider.contentTypeFor("a.html")).isEqualTo("text/html");
        assertThat(CommentToolProvider.contentTypeFor("A.MD")).isEqualTo("text/markdown");
        assertThat(CommentToolProvider.contentTypeFor("a.json")).isEqualTo("application/json");
        assertThat(CommentToolProvider.contentTypeFor("a.txt")).isEqualTo("text/plain");
    }

    // ---- project docs ----

    @Test
    void readProjectDocReturnsStoredContentAndHash() throws Exception {
        ProjectDoc doc = projectDoc("pd-1", "Roadmap", "alpha beta");
        when(projectDocService.getDoc(PROJECT, "pd-1")).thenReturn(doc);

        JsonNode out = json(call("read_project_doc", Map.of("docId", "pd-1")));

        assertThat(out.get("content").asText()).isEqualTo("alpha beta");
        assertThat(out.get("contentHash").asText()).isEqualTo(ProjectDocService.contentHash(doc));
    }

    @Test
    void writeProjectDocHonoursTheBaseHashAndAttributesTheAgent() throws Exception {
        ProjectDoc doc = projectDoc("pd-1", "Roadmap", "alpha beta");
        ProjectDoc written = projectDoc("pd-1", "Roadmap", "alpha gamma");
        when(projectDocService.getDoc(PROJECT, "pd-1")).thenReturn(doc);
        when(projectDocService.updateDoc(eq(PROJECT), eq("pd-1"), eq("alpha gamma"), any(), eq("h1"))).thenReturn(written);

        JsonNode out = json(call("write_project_doc", Map.of("docId", "pd-1", "content", "alpha gamma",
                "baseContentHash", "h1")));

        ArgumentCaptor<ProjectActor> actor = ArgumentCaptor.forClass(ProjectActor.class);
        verify(projectDocService).updateDoc(eq(PROJECT), eq("pd-1"), eq("alpha gamma"), actor.capture(), eq("h1"));
        assertThat(actor.getValue().agentId()).isEqualTo("a-1");
        assertThat(out.get("created").asBoolean()).isFalse();
        assertThat(out.get("contentHash").asText()).isEqualTo(ProjectDocService.contentHash(written));
    }

    @Test
    void aStaleProjectDocWriteIsRefusedWithTheRedoInstruction() {
        when(projectDocService.getDoc(PROJECT, "pd-1")).thenReturn(projectDoc("pd-1", "Roadmap", "x"));
        when(projectDocService.updateDoc(any(), any(), any(), any(), eq("stale")))
                .thenThrow(new ConflictException("Document changed since you read it — read it again before writing."));

        ToolResult result = call("write_project_doc", Map.of("docId", "pd-1", "content", "y", "baseContentHash", "stale"));

        assertThat(result.ok()).isFalse();
        assertThat(result.payload()).isEqualTo(CommentToolProvider.CONFLICT_MESSAGE);
    }

    @Test
    void writeProjectDocNeedsAPathOrDocIdAndContent() {
        assertThat(call("write_project_doc", Map.of("content", "x")).payload()).contains("path or docId");
        assertThat(call("write_project_doc", Map.of("docId", "pd-1")).payload()).contains("content is required");
        verify(projectDocService, never()).updateDoc(any(), anyString(), any(), any(), any());
    }

    // ---- fixtures ----

    private static DocumentView view(String id, String filename, String content, String hash) {
        return new DocumentView(id, "wi-1", filename, "text/markdown", OffsetDateTime.now(), content, null, null, null,
                OffsetDateTime.now(), hash);
    }

    private static ProjectDoc projectDoc(String id, String title, String content) {
        Project project = new Project();
        project.setId(PROJECT);
        ProjectDoc doc = new ProjectDoc();
        doc.setId(id);
        doc.setTitle(title);
        doc.setContent(content);
        doc.setProject(project);
        return doc;
    }
}
