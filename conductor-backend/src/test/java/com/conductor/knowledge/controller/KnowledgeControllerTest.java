package com.conductor.knowledge.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.User;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.KnowledgeSourceCountsView;
import com.conductor.knowledge.KnowledgeSourceView;
import com.conductor.knowledge.SourceReceipt;
import com.conductor.knowledge.page.FrontmatterException;
import com.conductor.knowledge.page.KnowledgeConflictException;
import com.conductor.knowledge.page.KnowledgePageService;
import com.conductor.knowledge.page.KnowledgeSearchService;
import com.conductor.knowledge.page.PageWriteResult;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice for {@link KnowledgeController}: page batch-write (happy path, conflict, invalid
 * frontmatter, non-member) and a lighter pass on source submission (ACCEPTED/DUPLICATE passthrough).
 * Follows the security-filter setup precedent in {@code WorkflowControllerTest}/{@code IntegrationControllerTest}.
 */
@WebMvcTest(KnowledgeController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class KnowledgeControllerTest {

    private static final String PROJECT_ID = "proj-1";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private KnowledgeIngestionService ingestionService;
    @MockitoBean private KnowledgePageService pageService;
    @MockitoBean private KnowledgeSearchService searchService;
    @MockitoBean private ProjectSecurityService projectSecurityService;

    // Security-filter collaborators
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean private UserApiKeyRepository userApiKeyRepository;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId("user-1");
        user.setEmail("user@example.com");
        when(jwtService.validateToken("valid-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid-token")).thenReturn("user-1");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
    }

    // ---- batchWriteKnowledgePages ----

    @Test
    void batchWrite_happyPath_returns200WithResults() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(pageService.batchWrite(eq(PROJECT_ID), anyList(), any(), any()))
                .thenReturn(List.of(new PageWriteResult("dir/page.md", 1, "abc123")));

        String body = """
                {"writes":[{"path":"dir/page.md","content":"---\\ntype: doc\\n---\\n\\nhello","baseVersion":null}]}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/pages/batch-write")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(1))
                .andExpect(jsonPath("$.results[0].path").value("dir/page.md"))
                .andExpect(jsonPath("$.results[0].version").value(1))
                .andExpect(jsonPath("$.results[0].contentHash").value("abc123"));
    }

    @Test
    void batchWrite_conflict_returns409WithConflictsArray() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(pageService.batchWrite(eq(PROJECT_ID), anyList(), any(), any()))
                .thenThrow(new KnowledgeConflictException(List.of(
                        new KnowledgeConflictException.Conflict("dir/page.md", 3, "---\ntype: doc\n---\n\nold"))));

        String body = """
                {"writes":[{"path":"dir/page.md","content":"---\\ntype: doc\\n---\\n\\nnew","baseVersion":1}]}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/pages/batch-write")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflicts.length()").value(1))
                .andExpect(jsonPath("$.conflicts[0].path").value("dir/page.md"))
                .andExpect(jsonPath("$.conflicts[0].currentVersion").value(3))
                .andExpect(jsonPath("$.conflicts[0].currentContent").value("---\ntype: doc\n---\n\nold"));
    }

    @Test
    void batchWrite_invalidFrontmatter_returns422() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(pageService.batchWrite(eq(PROJECT_ID), anyList(), any(), any()))
                .thenThrow(new FrontmatterException("Document is empty -- every page needs a frontmatter 'type'"));

        String body = """
                {"writes":[{"path":"dir/page.md","content":"not frontmatter"}]}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/pages/batch-write")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void batchWrite_nonMember_returns403() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(false);

        String body = """
                {"writes":[{"path":"dir/page.md","content":"---\\ntype: doc\\n---\\n\\nhello"}]}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/pages/batch-write")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    // ---- submitKnowledgeSource ----

    @Test
    void submitSource_accepted_returns202() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(ingestionService.submit(any())).thenReturn(new SourceReceipt("src-1", SourceReceipt.Status.ACCEPTED));

        String body = """
                {"sourceType":"manual_note","payload":"hello"}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/sources")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceId").value("src-1"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void submitSource_duplicate_returns202WithDuplicateStatus() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(ingestionService.submit(any())).thenReturn(new SourceReceipt("src-1", SourceReceipt.Status.DUPLICATE));

        String body = """
                {"sourceType":"manual_note","payload":"hello"}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/sources")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.sourceId").value("src-1"))
                .andExpect(jsonPath("$.status").value("DUPLICATE"));
    }

    @Test
    void listSources_defaultsToPendingStatus() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(ingestionService.listSources(eq(PROJECT_ID), eq(com.conductor.knowledge.KnowledgeSourceStatus.PENDING)))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/knowledge/sources")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listSources_byIds_callsGetSources() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(ingestionService.getSources(eq(PROJECT_ID), any()))
                .thenReturn(List.of(new KnowledgeSourceView("src-1", PROJECT_ID, "manual_note", null, null,
                        null, "hello", false, null, null, null, null,
                        com.conductor.knowledge.KnowledgeSourceStatus.PENDING, 0, null, null)));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/knowledge/sources?ids=src-1")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("src-1"));
    }

    // ---- getKnowledgeSourceCounts ----

    @Test
    void sourceCounts_happyPath_returnsCountsPerStatus() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(ingestionService.getSourceCounts(PROJECT_ID))
                .thenReturn(new KnowledgeSourceCountsView(3, 1, 42, 2));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/knowledge/sources/counts")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(3))
                .andExpect(jsonPath("$.processing").value(1))
                .andExpect(jsonPath("$.processed").value(42))
                .andExpect(jsonPath("$.dead").value(2));
    }

    @Test
    void sourceCounts_zeroDefaultsWhenProjectHasNoSources() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(ingestionService.getSourceCounts(PROJECT_ID))
                .thenReturn(new KnowledgeSourceCountsView(0, 0, 0, 0));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/knowledge/sources/counts")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(0))
                .andExpect(jsonPath("$.processing").value(0))
                .andExpect(jsonPath("$.processed").value(0))
                .andExpect(jsonPath("$.dead").value(0));
    }

    @Test
    void sourceCounts_nonMember_returns403() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/knowledge/sources/counts")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }
}
