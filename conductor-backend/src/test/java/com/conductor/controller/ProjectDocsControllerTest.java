package com.conductor.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectApiKey;
import com.conductor.entity.ProjectDoc;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.repository.DocCommentReplyRepository;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.ProjectActor;
import com.conductor.service.DocCommentService;
import com.conductor.service.DocFolderService;
import com.conductor.service.DocVersionService;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectDocService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.StorageService;
import com.conductor.workflow.RunTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the doc task-toggle endpoint's routing and authorization. The flip itself is unit-tested in
 * {@code ProjectDocServiceTest}.
 */
@WebMvcTest(ProjectDocsController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ProjectSecurityService.class})
class ProjectDocsControllerTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String DOC_ID = "doc-1";
    private static final String PATH = "/api/v1/projects/" + PROJECT_ID + "/docs/" + DOC_ID + "/tasks/";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private DocFolderService docFolderService;
    @MockitoBean private ProjectDocService projectDocService;
    @MockitoBean private DocVersionService docVersionService;
    @MockitoBean private DocCommentService docCommentService;
    @MockitoBean private DocCommentReplyRepository docCommentReplyRepository;
    @MockitoBean private StorageService storageService;
    @MockitoBean private ProjectMemberRepository projectMemberRepository;

    // Security filter chain collaborators
    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean private UserApiKeyRepository userApiKeyRepository;
    @MockitoBean private RunTokenService runTokenService;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setId("user-1");
        user.setEmail("ada@example.com");
        user.setName("Ada Admin");

        when(jwtService.validateToken("member-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("member-token")).thenReturn("user-1");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        memberWithRole(MemberRole.REVIEWER);

        Project project = new Project();
        project.setId(PROJECT_ID);
        ProjectApiKey apiKey = new ProjectApiKey();
        apiKey.setId("key-1");
        apiKey.setProject(project);
        apiKey.setName("ci-key");
        apiKey.setKeyValue("project-api-key");
        when(projectApiKeyRepository.findByKeyValueWithProject("project-api-key"))
                .thenReturn(Optional.of(apiKey));
    }

    /** Puts user-1 in the project with the given role, which is what the real gate reads. */
    private void memberWithRole(MemberRole role) {
        ProjectMember member = new ProjectMember();
        member.setRole(role);
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(true);
        when(projectMemberRepository.findByProjectIdAndUserId(PROJECT_ID, "user-1"))
                .thenReturn(Optional.of(member));
    }

    private ProjectDoc storedDoc() {
        Project project = new Project();
        project.setId(PROJECT_ID);

        User author = new User();
        author.setId("user-1");
        author.setName("Ada Admin");

        ProjectDoc doc = new ProjectDoc();
        doc.setId(DOC_ID);
        doc.setProject(project);
        doc.setTitle("Checklist");
        doc.setContent("- [x] alpha");
        doc.setCreatedBy(author);
        doc.setUpdatedBy(author);
        doc.setCreatedAt(OffsetDateTime.now());
        doc.setUpdatedAt(OffsetDateTime.now());
        return doc;
    }

    @Test
    void creatorTogglesACheckbox_returns200WithUpdatedContent() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(projectDocService.setTaskState(eq(PROJECT_ID), eq(DOC_ID), eq(1), eq(true), any(ProjectActor.class)))
                .thenReturn(storedDoc());

        mockMvc.perform(patch(PATH + "1")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checked\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(DOC_ID))
                .andExpect(jsonPath("$.content").value("- [x] alpha"));
    }

    @Test
    void reviewerCannotToggle_returns403AndNeverTouchesTheDoc() throws Exception {
        memberWithRole(MemberRole.REVIEWER);

        mockMvc.perform(patch(PATH + "1")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checked\":true}"))
                .andExpect(status().isForbidden());

        verify(projectDocService, never())
                .setTaskState(anyString(), anyString(), anyInt(), anyBoolean(), any());
    }

    /**
     * A project-scoped credential is how a headless agent ticks off work it has finished. It has no
     * user behind it, so the doc is attributed to a label instead — see {@code ProjectActor}.
     */
    @Test
    void projectApiKeyMayToggle_andIsAttributedToALabel() throws Exception {
        when(projectDocService.setTaskState(eq(PROJECT_ID), eq(DOC_ID), eq(1), eq(true), any(ProjectActor.class)))
                .thenReturn(storedDoc());

        mockMvc.perform(patch(PATH + "1")
                        .header("Authorization", "Bearer project-api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checked\":true}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ProjectActor> actor = ArgumentCaptor.forClass(ProjectActor.class);
        verify(projectDocService).setTaskState(eq(PROJECT_ID), eq(DOC_ID), eq(1), eq(true), actor.capture());
        assertThat(actor.getValue().user()).isNull();
        assertThat(actor.getValue().label()).isEqualTo("Agent");
    }

    @Test
    void projectApiKeyForAnotherProject_returns403() throws Exception {
        Project other = new Project();
        other.setId("proj-2");
        ProjectApiKey otherKey = new ProjectApiKey();
        otherKey.setId("key-2");
        otherKey.setProject(other);
        otherKey.setName("other-key");
        otherKey.setKeyValue("other-project-key");
        when(projectApiKeyRepository.findByKeyValueWithProject("other-project-key"))
                .thenReturn(Optional.of(otherKey));

        mockMvc.perform(patch(PATH + "1")
                        .header("Authorization", "Bearer other-project-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checked\":true}"))
                .andExpect(status().isForbidden());

        verify(projectDocService, never())
                .setTaskState(anyString(), anyString(), anyInt(), anyBoolean(), any());
    }

    @Test
    void nonMemberCannotReadDocs_returns403() throws Exception {
        when(projectMemberRepository.existsByProjectIdAndUserId(PROJECT_ID, "user-1")).thenReturn(false);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/docs")
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isForbidden());

        verify(projectDocService, never()).getDocs(anyString(), anyString(), anyBoolean());
    }

    @Test
    void agentAuthoredDocStillRendersAByline() throws Exception {
        ProjectDoc doc = storedDoc();
        doc.setCreatedBy(null);
        doc.setCreatedByLabel("Agent (run abc12345)");
        doc.setUpdatedBy(null);
        doc.setUpdatedByLabel("Agent (run abc12345)");
        when(projectDocService.getDoc(PROJECT_ID, DOC_ID)).thenReturn(doc);

        mockMvc.perform(get("/api/v1/projects/" + PROJECT_ID + "/docs/" + DOC_ID)
                        .header("Authorization", "Bearer member-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdByName").value("Agent (run abc12345)"))
                .andExpect(jsonPath("$.updatedByName").value("Agent (run abc12345)"));
    }

    @Test
    void staleLine_surfacesAs409() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(projectDocService.setTaskState(eq(PROJECT_ID), eq(DOC_ID), eq(4), eq(true), any(ProjectActor.class)))
                .thenThrow(new ConflictException("Line 4 is no longer a task list item"));

        mockMvc.perform(patch(PATH + "4")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checked\":true}"))
                .andExpect(status().isConflict());
    }

    /**
     * The service owns range validation rather than a bean-validation {@code @Min} on the path
     * variable: a constraint violation on a path variable raises ConstraintViolationException, which
     * GlobalExceptionHandler doesn't map, so it would surface as a 500 instead of a 400.
     */
    @Test
    void outOfRangeLineNumber_returns400NotServerError() throws Exception {
        memberWithRole(MemberRole.CREATOR);
        when(projectDocService.setTaskState(eq(PROJECT_ID), eq(DOC_ID), eq(0), eq(true), any(ProjectActor.class)))
                .thenThrow(new BusinessException("Line 0 is out of range for this document"));

        mockMvc.perform(patch(PATH + "0")
                        .header("Authorization", "Bearer member-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"checked\":true}"))
                .andExpect(status().isBadRequest());
    }
}
