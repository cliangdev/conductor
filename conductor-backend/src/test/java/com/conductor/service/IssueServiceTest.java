package com.conductor.service;

import com.conductor.entity.Issue;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.CreateIssueRequest;
import com.conductor.generated.model.IssueResponse;
import com.conductor.generated.model.PatchIssueRequest;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDispatcher;
import com.conductor.notification.NotificationEvent;
import com.conductor.repository.CommentRepository;
import com.conductor.repository.IssueRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartTransition;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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

    @Mock
    private WorkItemWorkflowService workItemWorkflowService;

    @Mock
    private AssetService assetService;

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
        testIssue.setType("PRD");
        testIssue.setTitle("Test Issue");
        testIssue.setWorkflow("ENGINEERING");
        testIssue.setWorkflowVersion(1);
        testIssue.setCurrentStatus("DRAFT");
        testIssue.setSequenceNumber(1);
        testIssue.setCreatedBy(caller);
        testIssue.setCreatedAt(OffsetDateTime.now());
        testIssue.setUpdatedAt(OffsetDateTime.now());

        // A status change resolves the bound Workflow to enrich the notification; back it with the real
        // built-in ENGINEERING statechart. Lenient because read-only / create tests never reach that path.
        Statechart engineering = new WorkflowDefinitionResolver(
                Mockito.mock(WorkflowDefinitionVersionRepository.class), new ObjectMapper())
                .resolveRequired("proj-1", "ENGINEERING");
        lenient().when(workItemWorkflowService.resolveFor(any(), any())).thenReturn(engineering);
    }

    @Test
    void createIssueSetsInitialStatusFromWorkflow() {
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
        when(issueRepository.findMaxSequenceNumberByProjectId("proj-1")).thenReturn(0);
        when(workItemWorkflowService.initialStatus("proj-1", "ENGINEERING")).thenReturn("DRAFT");
        when(issueRepository.save(any(Issue.class))).thenAnswer(invocation -> {
            Issue i = invocation.getArgument(0);
            if (i.getId() == null) i.setId("new-issue-id");
            if (i.getCreatedAt() == null) i.setCreatedAt(OffsetDateTime.now());
            if (i.getUpdatedAt() == null) i.setUpdatedAt(OffsetDateTime.now());
            if (i.getSequenceNumber() == null) i.setSequenceNumber(1);
            return i;
        });

        CreateIssueRequest request = new CreateIssueRequest("PRD", "My PRD");

        IssueResponse response = issueService.createIssue("proj-1", request, caller);

        ArgumentCaptor<Issue> captor = ArgumentCaptor.forClass(Issue.class);
        verify(issueRepository).save(captor.capture());
        Issue saved = captor.getValue();

        assertThat(saved.getCurrentStatus()).isEqualTo("DRAFT");
        assertThat(saved.getType()).isEqualTo("PRD");
        assertThat(saved.getTitle()).isEqualTo("My PRD");
        assertThat(saved.getWorkflow()).isEqualTo("ENGINEERING");
        assertThat(response.getStatus()).isEqualTo("DRAFT");
        // type is validated against the bound Workflow at creation
        verify(workItemWorkflowService).validateType("proj-1", "ENGINEERING", "PRD");
    }

    @Test
    void createIssueThrowsForbiddenForNonMember() {
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(false);

        assertThatThrownBy(() -> issueService.createIssue("proj-1",
                new CreateIssueRequest("PRD", "title"), caller))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void listIssuesFiltersByType() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findByProjectFiltered("proj-1", "PRD", null, null))
                .thenReturn(List.of(testIssue));

        List<IssueResponse> results = issueService.listIssues("proj-1", "PRD", null, null, caller);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getType()).isEqualTo("PRD");
    }

    @Test
    void listIssuesFiltersByStatus() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findByProjectFiltered("proj-1", null, "DRAFT", null))
                .thenReturn(List.of(testIssue));

        List<IssueResponse> results = issueService.listIssues("proj-1", null, "DRAFT", null, caller);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getStatus()).isEqualTo("DRAFT");
    }

    @Test
    void listIssuesFiltersByTypeAndStatus() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findByProjectFiltered("proj-1", "PRD", "DRAFT", null))
                .thenReturn(List.of(testIssue));

        List<IssueResponse> results = issueService.listIssues("proj-1", "PRD", "DRAFT", null, caller);

        assertThat(results).hasSize(1);
    }

    @Test
    void listIssuesFiltersByWorkflow() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(project));
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findByProjectFiltered("proj-1", null, null, "ENGINEERING"))
                .thenReturn(List.of(testIssue));

        List<IssueResponse> results = issueService.listIssues("proj-1", null, null, "ENGINEERING", caller);

        assertThat(results).hasSize(1);
    }

    @Test
    void patchIssueValidTransitionDraftToInReviewSucceeds() {
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        PatchIssueRequest request = new PatchIssueRequest().status("IN_REVIEW");

        issueService.patchIssue("proj-1", "issue-1", request, caller);

        assertThat(testIssue.getCurrentStatus()).isEqualTo("IN_REVIEW");
        verify(workItemWorkflowService).validateTransition("proj-1", testIssue, "IN_REVIEW");
    }

    @Test
    void patchIssueInvalidTransitionDraftToReadyForDevelopmentThrows400() {
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        doThrow(new BusinessException("Invalid status transition from DRAFT to READY_FOR_DEVELOPMENT"))
                .when(workItemWorkflowService).validateTransition(any(), any(), any());

        PatchIssueRequest request = new PatchIssueRequest().status("READY_FOR_DEVELOPMENT");

        assertThatThrownBy(() -> issueService.patchIssue("proj-1", "issue-1", request, caller))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid status transition from DRAFT to READY_FOR_DEVELOPMENT");
    }

    @Test
    void patchIssueInvalidTransitionReadyForDevelopmentToDraftThrows400() {
        testIssue.setCurrentStatus("READY_FOR_DEVELOPMENT");
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        doThrow(new BusinessException("Invalid status transition from READY_FOR_DEVELOPMENT to DRAFT"))
                .when(workItemWorkflowService).validateTransition(any(), any(), any());

        PatchIssueRequest request = new PatchIssueRequest().status("DRAFT");

        assertThatThrownBy(() -> issueService.patchIssue("proj-1", "issue-1", request, caller))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid status transition from READY_FOR_DEVELOPMENT to DRAFT");
    }

    @Test
    void patchIssueValidTransitionInReviewToReadyForDevelopment() {
        testIssue.setCurrentStatus("IN_REVIEW");
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        PatchIssueRequest request = new PatchIssueRequest().status("READY_FOR_DEVELOPMENT");

        issueService.patchIssue("proj-1", "issue-1", request, caller);

        assertThat(testIssue.getCurrentStatus()).isEqualTo("READY_FOR_DEVELOPMENT");
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

        PatchIssueRequest request = new PatchIssueRequest().status("IN_REVIEW");

        issueService.patchIssue("proj-1", "issue-1", request, caller);

        assertThat(testIssue.getCurrentStatus()).isEqualTo("IN_REVIEW");
    }

    @Test
    void patchIssueStatusChangeFiresSingleStatusChangedEvent() {
        testIssue.setCurrentStatus("IN_PROGRESS");
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        PatchIssueRequest request = new PatchIssueRequest().status("CODE_REVIEW");

        issueService.patchIssue("proj-1", "issue-1", request, caller);

        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationDispatcher).dispatch(eventCaptor.capture());

        NotificationEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(EventType.ISSUE_STATUS_CHANGED);
        assertThat(event.getMetadata()).containsEntry("fromStatus", "IN_PROGRESS");
        assertThat(event.getMetadata()).containsEntry("toStatus", "CODE_REVIEW");
        assertThat(event.getMetadata()).containsEntry("toStatusLabel", "Code Review");
        assertThat(event.getMetadata()).containsEntry("toCategory", "in_progress");
    }

    @Test
    void patchIssueStatusChangeOmitsPrUrlFromMetadata() {
        // PR links live in github_pr Assets now; a human status change carries no prUrl.
        testIssue.setCurrentStatus("IN_PROGRESS");
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        PatchIssueRequest request = new PatchIssueRequest().status("CODE_REVIEW");

        issueService.patchIssue("proj-1", "issue-1", request, caller);

        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationDispatcher).dispatch(eventCaptor.capture());

        assertThat(eventCaptor.getValue().getMetadata()).doesNotContainKey("prUrl");
    }

    @Test
    void patchIssueReviewerRoleAttemptingStatusChangeThrows403() {
        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));

        ProjectMember reviewerMember = new ProjectMember();
        reviewerMember.setRole(MemberRole.REVIEWER);
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "user-1"))
                .thenReturn(Optional.of(reviewerMember));

        PatchIssueRequest request = new PatchIssueRequest().status("IN_REVIEW");

        assertThatThrownBy(() -> issueService.patchIssue("proj-1", "issue-1", request, caller))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("REVIEWER role cannot change issue status");
    }

    @Test
    void patchIssueToInProgressWithAssigneeIncludesAssigneeNameInMetadata() {
        testIssue.setCurrentStatus("READY_FOR_DEVELOPMENT");

        User assignee = new User();
        assignee.setId("assignee-1");
        assignee.setName("Alice Smith");
        assignee.setEmail("alice@example.com");
        testIssue.setAssignee(assignee);

        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        PatchIssueRequest request = new PatchIssueRequest().status("IN_PROGRESS");

        issueService.patchIssue("proj-1", "issue-1", request, caller);

        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationDispatcher).dispatch(eventCaptor.capture());

        NotificationEvent event = eventCaptor.getValue();
        assertThat(event.getEventType()).isEqualTo(EventType.ISSUE_STATUS_CHANGED);
        assertThat(event.getMetadata()).containsEntry("assigneeName", "Alice Smith");
    }

    @Test
    void patchIssueToInProgressWithAssigneeAndNoNameFallsBackToEmail() {
        testIssue.setCurrentStatus("READY_FOR_DEVELOPMENT");

        User assignee = new User();
        assignee.setId("assignee-2");
        assignee.setName(null);
        assignee.setEmail("bob@example.com");
        testIssue.setAssignee(assignee);

        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        PatchIssueRequest request = new PatchIssueRequest().status("IN_PROGRESS");

        issueService.patchIssue("proj-1", "issue-1", request, caller);

        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationDispatcher).dispatch(eventCaptor.capture());

        assertThat(eventCaptor.getValue().getMetadata()).containsEntry("assigneeName", "bob@example.com");
    }

    @Test
    void patchIssueToInProgressWithoutAssigneeExcludesAssigneeNameFromMetadata() {
        testIssue.setCurrentStatus("READY_FOR_DEVELOPMENT");
        testIssue.setAssignee(null);

        when(projectSecurityService.isProjectMember("proj-1", "user-1")).thenReturn(true);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        PatchIssueRequest request = new PatchIssueRequest().status("IN_PROGRESS");

        issueService.patchIssue("proj-1", "issue-1", request, caller);

        ArgumentCaptor<NotificationEvent> eventCaptor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationDispatcher).dispatch(eventCaptor.capture());

        assertThat(eventCaptor.getValue().getMetadata()).doesNotContainKey("assigneeName");
    }

    // --- completeFromPullRequest (system-initiated transition on a merged PR; statechart-driven) ---

    @Test
    void completeFromPullRequestAdvancesViaPrMergedAndNotifies() {
        // ENGINEERING declares the pr_merged edge CODE_REVIEW -> DONE.
        testIssue.setCurrentStatus("CODE_REVIEW");
        when(issueRepository.findByProjectKeyAndSequenceNumber("TEST", 1)).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        StatechartTransition merged = new StatechartTransition(
                "CODE_REVIEW", "DONE", "Merge", true, List.of("approve"), "REVIEWER", "pr_merged", List.of());
        when(workItemWorkflowService.applySystemTransition(
                eq("proj-1"), eq(testIssue), eq(WorkItemWorkflowService.TRIGGER_PR_MERGED)))
                .thenAnswer(inv -> {
                    testIssue.setCurrentStatus("DONE");
                    return Optional.of(merged);
                });

        issueService.completeFromPullRequest("proj-1", "TEST", 1, "https://github.com/x/y/pull/9");

        assertThat(testIssue.getCurrentStatus()).isEqualTo("DONE");
        verify(issueRepository).save(testIssue);
        verify(assetService).recordPullRequestAsset(testIssue, "https://github.com/x/y/pull/9");

        ArgumentCaptor<NotificationEvent> captor = ArgumentCaptor.forClass(NotificationEvent.class);
        verify(notificationDispatcher).dispatch(captor.capture());
        NotificationEvent event = captor.getValue();
        assertThat(event.getEventType()).isEqualTo(EventType.ISSUE_STATUS_CHANGED);
        assertThat(event.getMetadata()).containsEntry("toStatus", "DONE");
        assertThat(event.getMetadata()).containsEntry("prUrl", "https://github.com/x/y/pull/9");
    }

    @Test
    void completeFromPullRequestThrowsForIssueInAnotherProject() {
        // Cross-project guard: issue belongs to proj-1 but the webhook came in for another project.
        when(issueRepository.findByProjectKeyAndSequenceNumber("TEST", 1)).thenReturn(Optional.of(testIssue));

        assertThatThrownBy(() ->
                issueService.completeFromPullRequest("other-proj", "TEST", 1, "https://github.com/x/y/pull/9"))
                .isInstanceOf(EntityNotFoundException.class);

        verify(issueRepository, never()).save(any());
        verify(notificationDispatcher, never()).dispatch(any());
    }

    @Test
    void completeFromPullRequestThrowsWhenIssueMissing() {
        when(issueRepository.findByProjectKeyAndSequenceNumber("TEST", 7)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                issueService.completeFromPullRequest("proj-1", "TEST", 7, "https://github.com/x/y/pull/9"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void completeFromPullRequestRecordsAssetButLeavesStatusWhenNoPrMergedEdge() {
        // Already terminal: ENGINEERING has no pr_merged edge from DONE → record the PR asset, status unchanged.
        testIssue.setCurrentStatus("DONE");
        when(issueRepository.findByProjectKeyAndSequenceNumber("TEST", 1)).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        issueService.completeFromPullRequest("proj-1", "TEST", 1, "https://github.com/x/y/pull/9");

        assertThat(testIssue.getCurrentStatus()).isEqualTo("DONE");
        verify(assetService).recordPullRequestAsset(testIssue, "https://github.com/x/y/pull/9");
        verify(notificationDispatcher, never()).dispatch(any());
    }

    @Test
    void completeFromPullRequestDoesNotReopenClosedIssue() {
        testIssue.setCurrentStatus("CLOSED");
        when(issueRepository.findByProjectKeyAndSequenceNumber("TEST", 1)).thenReturn(Optional.of(testIssue));
        when(issueRepository.save(any(Issue.class))).thenReturn(testIssue);

        issueService.completeFromPullRequest("proj-1", "TEST", 1, "https://github.com/x/y/pull/9");

        assertThat(testIssue.getCurrentStatus()).isEqualTo("CLOSED");
        verify(notificationDispatcher, never()).dispatch(any());
    }
}
