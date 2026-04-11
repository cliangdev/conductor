package com.conductor.service;

import com.conductor.entity.Issue;
import com.conductor.entity.IssueStatus;
import com.conductor.entity.IssueType;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.CreateIssueRequest;
import com.conductor.generated.model.IssueResponse;
import com.conductor.generated.model.PatchIssueRequest;
import com.conductor.notification.NotificationDispatcher;
import com.conductor.repository.CommentRepository;
import com.conductor.repository.IssueRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IssueServiceTest {

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private ProjectSecurityService projectSecurityService;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @Mock
    private NotificationDispatcher notificationDispatcher;

    @Mock
    private CommentRepository commentRepository;

    @InjectMocks
    private IssueService issueService;

    private User caller;
    private Project project;
    private Issue testIssue;

    @BeforeEach
    void setUp() {
        caller = new User();
        caller.setId("user-1");
        caller.setEmail("user@example.com");

        project = new Project();
        project.setId("proj-1");
        project.setName("Test Project");
        project.setCreatedBy(caller);
        project.setCreatedAt(OffsetDateTime.now());
        project.setUpdatedAt(OffsetDateTime.now());

        testIssue = new Issue();
        testIssue.setId("issue-1");
        testIssue.setProject(project);
        testIssue.setType(IssueType.PRD);
        testIssue.setTitle("Test Issue");
        testIssue.setStatus(IssueStatus.DRAFT);
        testIssue.setCreatedBy(caller);
        testIssue.setCreatedAt(OffsetDateTime.now());
        testIssue.setUpdatedAt(OffsetDateTime.now());
    }

    @Test
    void createIssueSetsDraftStatus() {
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
        when(issueRepository.save(any(Issue.class))).thenAnswer(invocation -> {
            Issue i = invocation.getArgument(0);
            if (i.getId() == null) i.setId("new-issue-id");
            if (i.getCreatedAt() == null) i.setCreatedAt(OffsetDateTime.now());
            if (i.getUpdatedAt() == null) i.setUpdatedAt(OffsetDateTime.now());
            return i;
        });

        CreateIssueRequest request = new CreateIssueRequest(
                com.conductor.generated.model.IssueType.PRD, "My PRD");

        IssueResponse response = issueService.createIssue("proj-1", request, caller);

        ArgumentCaptor<Issue> captor = ArgumentCaptor.forClass(Issue.class);
        verify(issueRepository).save(captor.capture());
        Issue saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo(IssueStatus.DRAFT);
        assertThat(saved.getType()).isEqualTo(IssueType.PRD);
        assertThat(saved.getTitle()).isEqualTo("My PRD");
        assertThat(response.getStatus().getValue()).isEqualTo("DRAFT");
    }

    @Test
    void createIssueThrowsNotFoundForNonMember() {
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(false);

        assertThatThrownBy(() -> issueService.createIssue("proj-1",
                new CreateIssueRequest(com.conductor.generated.model.IssueType.PRD, "title"), caller))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void listIssuesFiltersByType() {
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findByProjectIdAndType("proj-1", IssueType.PRD))
                .thenReturn(List.of(testIssue));

        List<IssueResponse> results = issueService.listIssues(
                "proj-1",
                com.conductor.generated.model.IssueType.PRD,
                null,
                caller);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getType().getValue()).isEqualTo("PRD");
    }

    @Test
    void listIssuesFiltersByStatus() {
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findByProjectIdAndStatus("proj-1", IssueStatus.DRAFT))
                .thenReturn(List.of(testIssue));

        List<IssueResponse> results = issueService.listIssues(
                "proj-1",
                null,
                com.conductor.generated.model.IssueStatus.DRAFT,
                caller);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getStatus().getValue()).isEqualTo("DRAFT");
    }

    @Test
    void listIssuesFiltersByTypeAndStatus() {
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findByProjectIdAndTypeAndStatus("proj-1", IssueType.PRD, IssueStatus.DRAFT))
                .thenReturn(List.of(testIssue));

        List<IssueResponse> results = issueService.listIssues(
                "proj-1",
                com.conductor.generated.model.IssueType.PRD,
                com.conductor.generated.model.IssueStatus.DRAFT,
                caller);

        assertThat(results).hasSize(1);
    }

    @Test
    void patchIssueValidTransitionDraftToInReviewSucceeds() {
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        PatchIssueRequest request = new PatchIssueRequest()
                .status(com.conductor.generated.model.IssueStatus.IN_REVIEW);

        IssueResponse response = issueService.patchIssue("proj-1", "issue-1", request, caller);

        assertThat(testIssue.getStatus()).isEqualTo(IssueStatus.IN_REVIEW);
    }

    @Test
    void patchIssueInvalidTransitionDraftToApprovedThrows400() {
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));

        PatchIssueRequest request = new PatchIssueRequest()
                .status(com.conductor.generated.model.IssueStatus.APPROVED);

        assertThatThrownBy(() -> issueService.patchIssue("proj-1", "issue-1", request, caller))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid status transition from DRAFT to APPROVED");
    }

    @Test
    void patchIssueInvalidTransitionApprovedToDraftThrows400() {
        testIssue.setStatus(IssueStatus.APPROVED);
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));

        PatchIssueRequest request = new PatchIssueRequest()
                .status(com.conductor.generated.model.IssueStatus.DRAFT);

        assertThatThrownBy(() -> issueService.patchIssue("proj-1", "issue-1", request, caller))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid status transition from APPROVED to DRAFT");
    }

    @Test
    void patchIssueValidTransitionChangesRequestedToInReview() {
        testIssue.setStatus(IssueStatus.CHANGES_REQUESTED);
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        PatchIssueRequest request = new PatchIssueRequest()
                .status(com.conductor.generated.model.IssueStatus.IN_REVIEW);

        issueService.patchIssue("proj-1", "issue-1", request, caller);

        assertThat(testIssue.getStatus()).isEqualTo(IssueStatus.IN_REVIEW);
    }

    @Test
    void patchIssueDraftToInReviewSucceeds() {
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        ProjectMember adminMember = new ProjectMember();
        adminMember.setRole(MemberRole.ADMIN);
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "user-1"))
                .thenReturn(Optional.of(adminMember));

        PatchIssueRequest request = new PatchIssueRequest()
                .status(com.conductor.generated.model.IssueStatus.IN_REVIEW);

        IssueResponse response = issueService.patchIssue("proj-1", "issue-1", request, caller);

        assertThat(testIssue.getStatus()).isEqualTo(IssueStatus.IN_REVIEW);
    }

    @Test
    void patchIssueReviewerRoleAttemptingStatusChangeThrows403() {
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));

        ProjectMember reviewerMember = new ProjectMember();
        reviewerMember.setRole(MemberRole.REVIEWER);
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "user-1"))
                .thenReturn(Optional.of(reviewerMember));

        PatchIssueRequest request = new PatchIssueRequest()
                .status(com.conductor.generated.model.IssueStatus.IN_REVIEW);

        assertThatThrownBy(() -> issueService.patchIssue("proj-1", "issue-1", request, caller))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("REVIEWER role cannot change issue status");
    }
}
