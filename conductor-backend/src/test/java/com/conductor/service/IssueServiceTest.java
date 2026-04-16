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
import com.conductor.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.conductor.notification.EventType;
import com.conductor.notification.NotificationEvent;

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

    @Mock
    private UserRepository userRepository;

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
        project.setKey("TEST");
        project.setCreatedBy(caller);
        project.setCreatedAt(OffsetDateTime.now());
        project.setUpdatedAt(OffsetDateTime.now());

        testIssue = new Issue();
        testIssue.setId("issue-1");
        testIssue.setProject(project);
        testIssue.setType(IssueType.PRD);
        testIssue.setTitle("Test Issue");
        testIssue.setStatus(IssueStatus.DRAFT);
        testIssue.setSequenceNumber(1);
        testIssue.setCreatedBy(caller);
        testIssue.setCreatedAt(OffsetDateTime.now());
        testIssue.setUpdatedAt(OffsetDateTime.now());
    }

    @Test
    void createIssueSetsDraftStatus() {
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
        when(issueRepository.findMaxSequenceNumberByProjectId("proj-1")).thenReturn(0);
        when(issueRepository.save(any(Issue.class))).thenAnswer(invocation -> {
            Issue i = invocation.getArgument(0);
            if (i.getId() == null) i.setId("new-issue-id");
            if (i.getCreatedAt() == null) i.setCreatedAt(OffsetDateTime.now());
            if (i.getUpdatedAt() == null) i.setUpdatedAt(OffsetDateTime.now());
            if (i.getSequenceNumber() == null) i.setSequenceNumber(1);
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
    void patchIssueInvalidTransitionDraftToReadyForDevelopmentThrows400() {
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));

        PatchIssueRequest request = new PatchIssueRequest()
                .status(com.conductor.generated.model.IssueStatus.READY_FOR_DEVELOPMENT);

        assertThatThrownBy(() -> issueService.patchIssue("proj-1", "issue-1", request, caller))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid status transition from DRAFT to READY_FOR_DEVELOPMENT");
    }

    @Test
    void patchIssueInvalidTransitionReadyForDevelopmentToDraftThrows400() {
        testIssue.setStatus(IssueStatus.READY_FOR_DEVELOPMENT);
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));

        PatchIssueRequest request = new PatchIssueRequest()
                .status(com.conductor.generated.model.IssueStatus.DRAFT);

        assertThatThrownBy(() -> issueService.patchIssue("proj-1", "issue-1", request, caller))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid status transition from READY_FOR_DEVELOPMENT to DRAFT");
    }

    @Test
    void patchIssueValidTransitionInReviewToReadyForDevelopment() {
        testIssue.setStatus(IssueStatus.IN_REVIEW);
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        PatchIssueRequest request = new PatchIssueRequest()
                .status(com.conductor.generated.model.IssueStatus.READY_FOR_DEVELOPMENT);

        issueService.patchIssue("proj-1", "issue-1", request, caller);

        assertThat(testIssue.getStatus()).isEqualTo(IssueStatus.READY_FOR_DEVELOPMENT);
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
    void patchIssueToCodeReviewWithPrUrlIncludesPrUrlInMetadata() {
        testIssue.setStatus(IssueStatus.IN_PROGRESS);
        testIssue.setGithubPrUrl("https://github.com/org/repo/pull/42");
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        PatchIssueRequest request = new PatchIssueRequest()
                .status(com.conductor.generated.model.IssueStatus.CODE_REVIEW);

        issueService.patchIssue("proj-1", "issue-1", request, caller);

        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationDispatcher, org.mockito.Mockito.atLeastOnce()).dispatch(eventCaptor.capture());

        NotificationEvent codeReviewEvent = eventCaptor.getAllValues().stream()
                .filter(e -> e.getEventType() == EventType.ISSUE_IN_CODE_REVIEW)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ISSUE_IN_CODE_REVIEW event not dispatched"));

        assertThat(codeReviewEvent.getMetadata()).containsEntry("prUrl", "https://github.com/org/repo/pull/42");
    }

    @Test
    void patchIssueToCodeReviewWithoutPrUrlOmitsPrUrlFromMetadata() {
        testIssue.setStatus(IssueStatus.IN_PROGRESS);
        testIssue.setGithubPrUrl(null);
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        PatchIssueRequest request = new PatchIssueRequest()
                .status(com.conductor.generated.model.IssueStatus.CODE_REVIEW);

        issueService.patchIssue("proj-1", "issue-1", request, caller);

        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationDispatcher, org.mockito.Mockito.atLeastOnce()).dispatch(eventCaptor.capture());

        NotificationEvent codeReviewEvent = eventCaptor.getAllValues().stream()
                .filter(e -> e.getEventType() == EventType.ISSUE_IN_CODE_REVIEW)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ISSUE_IN_CODE_REVIEW event not dispatched"));

        assertThat(codeReviewEvent.getMetadata()).doesNotContainKey("prUrl");
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

    @Test
    void patchIssueToInProgressWithAssigneeIncludesAssigneeNameInMetadata() {
        testIssue.setStatus(IssueStatus.READY_FOR_DEVELOPMENT);

        User assignee = new User();
        assignee.setId("assignee-1");
        assignee.setName("Alice Smith");
        assignee.setEmail("alice@example.com");
        testIssue.setAssignee(assignee);

        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        PatchIssueRequest request = new PatchIssueRequest()
                .status(com.conductor.generated.model.IssueStatus.IN_PROGRESS);

        issueService.patchIssue("proj-1", "issue-1", request, caller);

        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationDispatcher, org.mockito.Mockito.atLeastOnce()).dispatch(eventCaptor.capture());

        NotificationEvent inProgressEvent = eventCaptor.getAllValues().stream()
                .filter(e -> e.getEventType() == com.conductor.notification.EventType.ISSUE_IN_PROGRESS)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ISSUE_IN_PROGRESS event not dispatched"));

        assertThat(inProgressEvent.getMetadata()).containsEntry("assigneeName", "Alice Smith");
    }

    @Test
    void patchIssueToInProgressWithAssigneeAndNoNameFallsBackToEmail() {
        testIssue.setStatus(IssueStatus.READY_FOR_DEVELOPMENT);

        User assignee = new User();
        assignee.setId("assignee-2");
        assignee.setName(null);
        assignee.setEmail("bob@example.com");
        testIssue.setAssignee(assignee);

        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        PatchIssueRequest request = new PatchIssueRequest()
                .status(com.conductor.generated.model.IssueStatus.IN_PROGRESS);

        issueService.patchIssue("proj-1", "issue-1", request, caller);

        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationDispatcher, org.mockito.Mockito.atLeastOnce()).dispatch(eventCaptor.capture());

        NotificationEvent inProgressEvent = eventCaptor.getAllValues().stream()
                .filter(e -> e.getEventType() == com.conductor.notification.EventType.ISSUE_IN_PROGRESS)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ISSUE_IN_PROGRESS event not dispatched"));

        assertThat(inProgressEvent.getMetadata()).containsEntry("assigneeName", "bob@example.com");
    }

    @Test
    void patchIssueToInProgressWithoutAssigneeExcludesAssigneeNameFromMetadata() {
        testIssue.setStatus(IssueStatus.READY_FOR_DEVELOPMENT);
        testIssue.setAssignee(null);

        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        PatchIssueRequest request = new PatchIssueRequest()
                .status(com.conductor.generated.model.IssueStatus.IN_PROGRESS);

        issueService.patchIssue("proj-1", "issue-1", request, caller);

        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationDispatcher, org.mockito.Mockito.atLeastOnce()).dispatch(eventCaptor.capture());

        NotificationEvent inProgressEvent = eventCaptor.getAllValues().stream()
                .filter(e -> e.getEventType() == com.conductor.notification.EventType.ISSUE_IN_PROGRESS)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ISSUE_IN_PROGRESS event not dispatched"));

        assertThat(inProgressEvent.getMetadata()).doesNotContainKey("assigneeName");
    }
}
