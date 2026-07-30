package com.conductor.knowledge.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.User;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.KnowledgeSourceCountsView;
import com.conductor.knowledge.KnowledgeSourceView;
import com.conductor.knowledge.SourceReceipt;
import com.conductor.knowledge.domain.KnowledgeDomainService;
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
import com.conductor.workflow.RunTokenService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    @MockitoBean private KnowledgeDomainService domainService;
    @MockitoBean private ProjectSecurityService projectSecurityService;

    // Security-filter collaborators
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean private UserApiKeyRepository userApiKeyRepository;
    @MockitoBean private RunTokenService runTokenService;

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
        when(pageService.batchWrite(eq(PROJECT_ID), anyList(), any(), any(), any()))
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
        when(pageService.batchWrite(eq(PROJECT_ID), anyList(), any(), any(), any()))
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
        when(pageService.batchWrite(eq(PROJECT_ID), anyList(), any(), any(), any()))
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

    @Test
    void batchWrite_acceptsAndForwardsSkipped() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(pageService.batchWrite(eq(PROJECT_ID), anyList(), any(), anyList(), any()))
                .thenReturn(List.of());

        String body = """
                {"writes":[],"skipped":[{"sourceId":"src-1","reason":"not material"}]}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/pages/batch-write")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(0));

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<com.conductor.knowledge.page.SkippedSource>> skippedCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(pageService)
                .batchWrite(eq(PROJECT_ID), anyList(), any(), skippedCaptor.capture(), any());
        assertThat(skippedCaptor.getValue()).containsExactly(
                new com.conductor.knowledge.page.SkippedSource("src-1", "not material"));
    }

    @Test
    void batchWrite_sourceIdInBothListsReturns400() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(pageService.batchWrite(eq(PROJECT_ID), anyList(), any(), anyList(), any()))
                .thenThrow(new com.conductor.exception.BusinessException(
                        "Source src-1 appears in both sourceIds and skipped"));

        String body = """
                {"writes":[],"sourceIds":["src-1"],"skipped":[{"sourceId":"src-1","reason":"not material"}]}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/pages/batch-write")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ---- dismissKnowledgePage ("Not worth filing") ----

    @Test
    void dismiss_happyPath_returns200WithResponseBody() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(pageService.dismissPage(eq(PROJECT_ID), eq("engineering/work-items/cx-14.md"), eq(1),
                eq("Not worth filing."), anyString(), any()))
                .thenReturn(new KnowledgePageService.DismissResult(
                        "engineering/work-items/cx-14.md", 2, "engineering/_curation.md", 3));

        String body = """
                {"path":"engineering/work-items/cx-14.md","baseVersion":1,"reason":"Not worth filing."}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/pages/dismiss")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("engineering/work-items/cx-14.md"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.curationPagePath").value("engineering/_curation.md"))
                .andExpect(jsonPath("$.curationPageVersion").value(3));
    }

    @Test
    void dismiss_blankReason_returns400() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(pageService.dismissPage(eq(PROJECT_ID), anyString(), any(), anyString(), anyString(), any()))
                .thenThrow(new com.conductor.exception.BusinessException("dismiss requires a non-blank reason"));

        String body = """
                {"path":"notes/a.md","baseVersion":1,"reason":"   "}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/pages/dismiss")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void dismiss_nonMember_returns403() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(false);

        String body = """
                {"path":"notes/a.md","baseVersion":1,"reason":"Not worth filing."}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/pages/dismiss")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void dismiss_unknownPath_returns404() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(pageService.dismissPage(eq(PROJECT_ID), anyString(), any(), anyString(), anyString(), any()))
                .thenThrow(new jakarta.persistence.EntityNotFoundException(
                        "No live knowledge page at path: notes/missing.md"));

        String body = """
                {"path":"notes/missing.md","baseVersion":1,"reason":"Not worth filing."}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/pages/dismiss")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void dismiss_conflict_returns409WithConflictsArray() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(pageService.dismissPage(eq(PROJECT_ID), anyString(), any(), anyString(), anyString(), any()))
                .thenThrow(new KnowledgeConflictException(
                        "This page changed since you opened it — reload and try again.",
                        List.of(new KnowledgeConflictException.Conflict("notes/a.md", 3, "---\ntype: note\n---\n\nold"))));

        String body = """
                {"path":"notes/a.md","baseVersion":1,"reason":"Not worth filing."}
                """;

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/pages/dismiss")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.conflicts.length()").value(1))
                .andExpect(jsonPath("$.conflicts[0].path").value("notes/a.md"));
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
        when(ingestionService.listSources(eq(PROJECT_ID), eq(com.conductor.knowledge.KnowledgeSourceStatus.PENDING), isNull()))
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
                        com.conductor.knowledge.KnowledgeSourceStatus.PENDING, 0, null, null, null, null)));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/knowledge/sources?ids=src-1")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("src-1"));
    }

    /**
     * The generated OpenAPI {@code KnowledgeSourceStatus} enum and the domain
     * {@code com.conductor.knowledge.KnowledgeSourceStatus} enum are two separate types bridged by
     * name via {@code valueOf} in {@link KnowledgeController#listKnowledgeSources} -- adding SKIPPED
     * to only one of them (e.g. the domain enum but not the OpenAPI spec) would 400 or 500 here rather
     * than compile-fail, since the bridge is a runtime name lookup, not a shared type.
     */
    @Test
    void listSources_statusSkipped_roundTripsToDomainEnum() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(ingestionService.listSources(eq(PROJECT_ID), eq(com.conductor.knowledge.KnowledgeSourceStatus.SKIPPED), isNull()))
                .thenReturn(List.of(new KnowledgeSourceView("src-1", PROJECT_ID, "manual_note", null, null,
                        null, null, false, null, null, null, null,
                        com.conductor.knowledge.KnowledgeSourceStatus.SKIPPED, 0, null, "not material", null, null)));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/knowledge/sources?status=SKIPPED")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("SKIPPED"))
                .andExpect(jsonPath("$[0].skipReason").value("not material"));
    }

    // ---- getKnowledgeSourceCounts ----

    @Test
    void sourceCounts_happyPath_returnsCountsPerStatus() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(ingestionService.getSourceCounts(PROJECT_ID))
                .thenReturn(new KnowledgeSourceCountsView(3, 1, 42, 4, 2));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/knowledge/sources/counts")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(3))
                .andExpect(jsonPath("$.processing").value(1))
                .andExpect(jsonPath("$.processed").value(42))
                .andExpect(jsonPath("$.skipped").value(4))
                .andExpect(jsonPath("$.dead").value(2));
    }

    @Test
    void sourceCounts_zeroDefaultsWhenProjectHasNoSources() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        when(ingestionService.getSourceCounts(PROJECT_ID))
                .thenReturn(new KnowledgeSourceCountsView(0, 0, 0, 0, 0));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/knowledge/sources/counts")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(0))
                .andExpect(jsonPath("$.processing").value(0))
                .andExpect(jsonPath("$.processed").value(0))
                .andExpect(jsonPath("$.skipped").value(0))
                .andExpect(jsonPath("$.dead").value(0));
    }

    @Test
    void sourceCounts_nonMember_returns403() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/knowledge/sources/counts")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }

    // ---- retryDeadKnowledgeSources ----

    @Test
    void retryDeadSources_admin_returns200WithCount() throws Exception {
        when(projectSecurityService.isProjectAdmin(PROJECT_ID, "user-1")).thenReturn(true);
        when(ingestionService.retryDeadSources(PROJECT_ID)).thenReturn(3);

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/sources/retry")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retried").value(3));
    }

    @Test
    void retryDeadSources_nonAdmin_returns403() throws Exception {
        when(projectSecurityService.isProjectAdmin(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/sources/retry")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void retryDeadSources_projectScopedMcpToken_returns403() throws Exception {
        // Same rationale as updateDomain/createSpecialist: an ops recovery action requires an actual
        // User principal with ADMIN role, not just a project-scoped machine principal.
        when(runTokenService.parseMcpToken("eyJ.mcp.matching"))
                .thenReturn(Optional.of(new RunTokenService.McpTokenClaims(PROJECT_ID, "run-1")));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/sources/retry")
                        .header("Authorization", "Bearer eyJ.mcp.matching"))
                .andExpect(status().isForbidden());
    }

    // ---- knowledge domains ----

    @Test
    void listDomains_returnsRegistryWithCounts() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        com.conductor.knowledge.domain.KnowledgeDomain engineering = new com.conductor.knowledge.domain.KnowledgeDomain();
        engineering.setSlug("engineering");
        engineering.setDisplayName("Engineering");
        engineering.setPathPrefix("engineering/");
        engineering.setSchemaPagePath("engineering/_schema.md");
        engineering.setSourceTypePatterns(List.of("github.*"));
        engineering.setState(com.conductor.knowledge.domain.KnowledgeDomainState.ACTIVE);
        when(domainService.list(PROJECT_ID)).thenReturn(List.of(engineering));
        when(ingestionService.getDomainCounts(PROJECT_ID))
                .thenReturn(java.util.Map.of("engineering", new KnowledgeSourceCountsView(2, 1, 5, 0, 0)));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/knowledge/domains")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].slug").value("engineering"))
                .andExpect(jsonPath("$[0].sourceTypePatterns[0]").value("github.*"))
                .andExpect(jsonPath("$[0].pendingCount").value(2))
                .andExpect(jsonPath("$[0].processingCount").value(1))
                .andExpect(jsonPath("$[0].processedCount").value(5));
    }

    @Test
    void updateDomain_admin_updatesAndReturns200() throws Exception {
        when(projectSecurityService.isProjectAdmin(PROJECT_ID, "user-1")).thenReturn(true);
        com.conductor.knowledge.domain.KnowledgeDomain updated = new com.conductor.knowledge.domain.KnowledgeDomain();
        updated.setSlug("engineering");
        updated.setDisplayName("Eng");
        updated.setPathPrefix("engineering/");
        updated.setSchemaPagePath("engineering/_schema.md");
        updated.setSourceTypePatterns(List.of("github.*"));
        updated.setState(com.conductor.knowledge.domain.KnowledgeDomainState.ACTIVE);
        when(domainService.applyPatch(eq(PROJECT_ID), eq("engineering"), eq("Eng"), any(), any(), any(),
                eq(false), any()))
                .thenReturn(updated);
        when(ingestionService.getDomainCounts(PROJECT_ID)).thenReturn(java.util.Map.of());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + PROJECT_ID + "/knowledge/domains/engineering")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Eng\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Eng"));
    }

    @Test
    void updateDomain_nonAdmin_returns403() throws Exception {
        when(projectSecurityService.isProjectAdmin(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + PROJECT_ID + "/knowledge/domains/engineering")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Eng\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createDomain_member_landsSuggestedWith201WhenNew() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        com.conductor.knowledge.domain.KnowledgeDomain suggested = new com.conductor.knowledge.domain.KnowledgeDomain();
        suggested.setSlug("legal");
        suggested.setDisplayName("Legal");
        suggested.setPathPrefix("legal/");
        suggested.setSchemaPagePath("legal/_schema.md");
        suggested.setSourceTypePatterns(List.of());
        suggested.setState(com.conductor.knowledge.domain.KnowledgeDomainState.SUGGESTED);
        when(domainService.suggest(eq(PROJECT_ID), eq("legal"), eq("Legal"), any(), eq("reason"), any(), eq("user-1")))
                .thenReturn(new com.conductor.knowledge.domain.KnowledgeDomainService.SuggestResult(suggested, true));
        when(ingestionService.getDomainCounts(PROJECT_ID)).thenReturn(java.util.Map.of());

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/domains")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"legal\",\"displayName\":\"Legal\",\"reason\":\"reason\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.slug").value("legal"))
                .andExpect(jsonPath("$.state").value("SUGGESTED"));
    }

    @Test
    void createDomain_existingSlug_returns200NotCreated() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(true);
        com.conductor.knowledge.domain.KnowledgeDomain existing = new com.conductor.knowledge.domain.KnowledgeDomain();
        existing.setSlug("engineering");
        existing.setDisplayName("Engineering");
        existing.setPathPrefix("engineering/");
        existing.setSchemaPagePath("engineering/_schema.md");
        existing.setSourceTypePatterns(List.of());
        existing.setState(com.conductor.knowledge.domain.KnowledgeDomainState.ACTIVE);
        when(domainService.suggest(eq(PROJECT_ID), eq("engineering"), any(), any(), any(), any(), eq("user-1")))
                .thenReturn(new com.conductor.knowledge.domain.KnowledgeDomainService.SuggestResult(existing, false));
        when(ingestionService.getDomainCounts(PROJECT_ID)).thenReturn(java.util.Map.of());

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/domains")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"engineering\",\"displayName\":\"Engineering\",\"reason\":\"r\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("ACTIVE"));
    }

    @Test
    void createDomain_nonMember_returns403() throws Exception {
        when(projectSecurityService.isProjectMember(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/domains")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"slug\":\"legal\",\"displayName\":\"Legal\",\"reason\":\"r\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createSpecialist_admin_returns200() throws Exception {
        when(projectSecurityService.isProjectAdmin(PROJECT_ID, "user-1")).thenReturn(true);
        com.conductor.knowledge.domain.KnowledgeDomain domain = new com.conductor.knowledge.domain.KnowledgeDomain();
        domain.setSlug("engineering");
        domain.setDisplayName("Engineering");
        domain.setPathPrefix("engineering/");
        domain.setSchemaPagePath("engineering/_schema.md");
        domain.setSourceTypePatterns(List.of());
        domain.setState(com.conductor.knowledge.domain.KnowledgeDomainState.ACTIVE);
        domain.setOwningAgentSlug("knowledge-engineering");
        when(domainService.createSpecialist(PROJECT_ID, "engineering")).thenReturn(domain);
        when(ingestionService.getDomainCounts(PROJECT_ID)).thenReturn(java.util.Map.of());

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/domains/engineering/specialist")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.owningAgentSlug").value("knowledge-engineering"));
    }

    @Test
    void createSpecialist_nonAdmin_returns403() throws Exception {
        when(projectSecurityService.isProjectAdmin(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/domains/engineering/specialist")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateDomain_projectScopedMcpToken_returns403() throws Exception {
        // A project-scoped machine principal (project API key / run-scoped MCP token) passes the
        // generic membership check other knowledge endpoints use, but the domain-admin gate requires an
        // actual User principal with ADMIN role -- machine callers have no role to check.
        when(runTokenService.parseMcpToken("eyJ.mcp.matching"))
                .thenReturn(Optional.of(new RunTokenService.McpTokenClaims(PROJECT_ID, "run-1")));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/projects/" + PROJECT_ID + "/knowledge/domains/engineering")
                        .header("Authorization", "Bearer eyJ.mcp.matching")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Eng\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void createSpecialist_projectScopedMcpToken_returns403() throws Exception {
        when(runTokenService.parseMcpToken("eyJ.mcp.matching"))
                .thenReturn(Optional.of(new RunTokenService.McpTokenClaims(PROJECT_ID, "run-1")));

        mockMvc.perform(post("/api/v1/projects/" + PROJECT_ID + "/knowledge/domains/engineering/specialist")
                        .header("Authorization", "Bearer eyJ.mcp.matching"))
                .andExpect(status().isForbidden());
    }

    // ---- run-scoped MCP token auth ----

    @Test
    void sourceCounts_mcpTokenMatchingProject_returns200() throws Exception {
        when(runTokenService.parseMcpToken("eyJ.mcp.matching"))
                .thenReturn(Optional.of(new RunTokenService.McpTokenClaims(PROJECT_ID, "run-1")));
        when(ingestionService.getSourceCounts(PROJECT_ID))
                .thenReturn(new KnowledgeSourceCountsView(1, 0, 0, 0, 0));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/knowledge/sources/counts")
                        .header("Authorization", "Bearer eyJ.mcp.matching"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pending").value(1));
    }

    @Test
    void sourceCounts_mcpTokenMismatchedProject_returns403() throws Exception {
        when(runTokenService.parseMcpToken("eyJ.mcp.mismatched"))
                .thenReturn(Optional.of(new RunTokenService.McpTokenClaims("other-project", "run-1")));

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/knowledge/sources/counts")
                        .header("Authorization", "Bearer eyJ.mcp.mismatched"))
                .andExpect(status().isForbidden());
    }
}
