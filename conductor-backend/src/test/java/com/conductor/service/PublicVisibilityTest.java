package com.conductor.service;

import com.conductor.entity.Issue;
import com.conductor.entity.IssueStatus;
import com.conductor.entity.IssueType;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectVisibility;
import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.CreateCommentRequest;
import com.conductor.generated.model.CreateIssueRequest;
import com.conductor.generated.model.ProjectDetail;
import com.conductor.repository.CommentReplyRepository;
import com.conductor.repository.CommentRepository;
import com.conductor.repository.DocumentRepository;
import com.conductor.repository.IssueRepository;
import com.conductor.repository.OrgMemberRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.TeamMemberRepository;
import com.conductor.repository.UserRepository;
import com.conductor.notification.NotificationDispatcher;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Tests for PUBLIC project visibility:
 * - Read access: any authenticated user can read a PUBLIC project
 * - Write access: only explicit project members can write; non-members get 403
 */
@ExtendWith(MockitoExtension.class)
class PublicVisibilityTest {

    // --- ProjectService mocks ---
    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private ProjectSecurityService projectSecurityService;

    @Mock
    private OrgMemberRepository orgMemberRepository;

    @Mock
    private TeamMemberRepository teamMemberRepository;

    @InjectMocks
    private ProjectService projectService;

    // --- IssueService (constructed manually to inject shared projectService) ---
    @Mock
    private IssueRepository issueRepository;

    @Mock
    private NotificationDispatcher notificationDispatcher;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private UserRepository userRepository;

    private IssueService issueService;

    // --- CommentService (constructed manually to inject shared projectService) ---
    @Mock
    private CommentReplyRepository commentReplyRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private StorageService storageService;

    private CommentService commentService;

    // --- Entities ---
    private User projectMember;
    private User nonMember;
    private Project publicProject;

    @BeforeEach
    void setUp() {
        projectMember = new User();
        projectMember.setId("member-id");
        projectMember.setEmail("member@example.com");
        projectMember.setName("Project Member");

        nonMember = new User();
        nonMember.setId("nonmember-id");
        nonMember.setEmail("nonmember@example.com");
        nonMember.setName("Non Member");

        publicProject = new Project();
        publicProject.setId("proj-public");
        publicProject.setName("Public Project");
        publicProject.setKey("PUB");
        publicProject.setOrgId("org-1");
        publicProject.setVisibility(ProjectVisibility.PUBLIC);
        publicProject.setCreatedBy(projectMember);
        publicProject.setCreatedAt(OffsetDateTime.now());
        publicProject.setUpdatedAt(OffsetDateTime.now());

        issueService = new IssueService(
                issueRepository,
                projectRepository,
                projectSecurityService,
                projectMemberRepository,
                notificationDispatcher,
                commentRepository,
                userRepository,
                projectService);

        commentService = new CommentService(
                commentRepository,
                commentReplyRepository,
                issueRepository,
                documentRepository,
                projectMemberRepository,
                storageService,
                notificationDispatcher,
                projectRepository,
                projectService);
    }

    // --- canUserAccessProject: PUBLIC returns true for any authenticated user ---

    @Test
    void publicProject_accessibleToUserWithNoOrgMembership() {
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-public", "nonmember-id")).thenReturn(false);

        boolean result = projectService.canUserAccessProject("nonmember-id", publicProject);

        assertThat(result).isTrue();
    }

    @Test
    void publicProject_accessibleToExplicitProjectMember() {
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-public", "member-id")).thenReturn(true);

        boolean result = projectService.canUserAccessProject("member-id", publicProject);

        assertThat(result).isTrue();
    }

    // --- getProject: PUBLIC project readable by non-member ---

    @Test
    void getProject_publicProjectAccessibleToNonMember() {
        when(projectRepository.findById("proj-public")).thenReturn(Optional.of(publicProject));
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-public", "nonmember-id")).thenReturn(false);
        when(projectMemberRepository.findByProjectId("proj-public")).thenReturn(List.of());

        ProjectDetail detail = projectService.getProject("proj-public", nonMember);

        assertThat(detail).isNotNull();
        assertThat(detail.getId()).isEqualTo("proj-public");
    }

    // --- IssueService.createIssue: non-member gets 403 on PUBLIC project ---

    @Test
    void createIssue_nonMemberOnPublicProjectThrowsForbidden() {
        when(projectSecurityService.isProjectMember("proj-public", "nonmember-id")).thenReturn(false);

        CreateIssueRequest request = new CreateIssueRequest(
                com.conductor.generated.model.IssueType.PRD, "New Issue");

        assertThatThrownBy(() -> issueService.createIssue("proj-public", request, nonMember))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("project member");
    }

    @Test
    void createIssue_explicitMemberOnPublicProjectSucceeds() {
        when(projectSecurityService.isProjectMember("proj-public", "member-id")).thenReturn(true);
        when(projectRepository.findById("proj-public")).thenReturn(Optional.of(publicProject));
        when(issueRepository.findMaxSequenceNumberByProjectId("proj-public")).thenReturn(0);
        when(issueRepository.save(org.mockito.ArgumentMatchers.any(Issue.class))).thenAnswer(inv -> {
            Issue i = inv.getArgument(0);
            i.setId("issue-1");
            i.setCreatedAt(OffsetDateTime.now());
            i.setUpdatedAt(OffsetDateTime.now());
            return i;
        });

        CreateIssueRequest request = new CreateIssueRequest(
                com.conductor.generated.model.IssueType.PRD, "New Issue");

        var response = issueService.createIssue("proj-public", request, projectMember);

        assertThat(response).isNotNull();
        assertThat(response.getTitle()).isEqualTo("New Issue");
    }

    // --- CommentService.createComment: non-member gets 403 on PUBLIC project ---

    @Test
    void createComment_nonMemberOnPublicProjectThrowsForbidden() {
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-public", "nonmember-id")).thenReturn(false);

        CreateCommentRequest request = new CreateCommentRequest("doc-1", "comment text", 5);

        assertThatThrownBy(() -> commentService.createComment("proj-public", "issue-1", request, nonMember))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("project member");
    }
}
