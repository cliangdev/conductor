package com.conductor.service;

import com.conductor.entity.Issue;
import com.conductor.entity.IssueStatus;
import com.conductor.entity.IssueType;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.fasterxml.jackson.databind.JsonNode;
import com.conductor.generated.model.CreateIssueRequest;
import com.conductor.generated.model.IssueAssignee;
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
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class IssueService {

    // COND-18 E2: the status state machine moved out of this hardcoded map into the Workflow definition;
    // transitions are now validated by WorkItemTransitionService against the bound Statechart.

    private final IssueRepository issueRepository;
    private final ProjectRepository projectRepository;
    private final ProjectSecurityService projectSecurityService;
    private final ProjectMemberRepository projectMemberRepository;
    private final NotificationDispatcher notificationDispatcher;
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final WorkItemTransitionService workItemTransitionService;

    public IssueService(
            IssueRepository issueRepository,
            ProjectRepository projectRepository,
            ProjectSecurityService projectSecurityService,
            ProjectMemberRepository projectMemberRepository,
            NotificationDispatcher notificationDispatcher,
            CommentRepository commentRepository,
            UserRepository userRepository,
            WorkItemTransitionService workItemTransitionService) {
        this.issueRepository = issueRepository;
        this.projectRepository = projectRepository;
        this.projectSecurityService = projectSecurityService;
        this.projectMemberRepository = projectMemberRepository;
        this.notificationDispatcher = notificationDispatcher;
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
        this.workItemTransitionService = workItemTransitionService;
    }

    @Transactional
    public IssueResponse createIssue(String projectId, CreateIssueRequest request, User caller) {
        verifyMembership(projectId, caller.getId());

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        Issue issue = new Issue();
        issue.setProject(project);
        issue.setType(toEntityIssueType(request.getType()));
        issue.setTitle(request.getTitle());
        issue.setDescription(request.getDescription());
        issue.setCreatedBy(caller);
        issue.setStatus(IssueStatus.DRAFT);
        // COND-18: bind the Work Item to its Workflow (defaults to the built-in ENGINEERING).
        String workflow = request.getWorkflow() != null && !request.getWorkflow().isBlank()
                ? request.getWorkflow() : WorkItemTransitionService.DEFAULT_WORKFLOW;
        issue.setWorkflow(workflow);
        issue.setWorkflowVersion(1);
        issue.setCurrentStatus(IssueStatus.DRAFT.name());

        Integer nextSeq = issueRepository.findMaxSequenceNumberByProjectId(projectId) + 1;
        issue.setSequenceNumber(nextSeq);

        issueRepository.save(issue);
        return toIssueResponse(issue);
    }

    @Transactional(readOnly = true)
    public List<IssueResponse> listIssues(
            String projectId,
            com.conductor.generated.model.IssueType type,
            com.conductor.generated.model.IssueStatus status,
            User caller) {
        verifyReadAccess(projectId, caller.getId());

        IssueType entityType = type != null ? toEntityIssueType(type) : null;
        IssueStatus entityStatus = status != null ? toEntityIssueStatus(status) : null;

        List<Issue> issues;
        if (entityType != null && entityStatus != null) {
            issues = issueRepository.findByProjectIdAndTypeAndStatus(projectId, entityType, entityStatus);
        } else if (entityType != null) {
            issues = issueRepository.findByProjectIdAndType(projectId, entityType);
        } else if (entityStatus != null) {
            issues = issueRepository.findByProjectIdAndStatus(projectId, entityStatus);
        } else {
            issues = issueRepository.findByProjectId(projectId);
        }

        List<String> issueIds = issues.stream().map(Issue::getId).toList();
        Map<String, Long> unresolvedCounts = new HashMap<>();
        if (!issueIds.isEmpty()) {
            commentRepository.countUnresolvedByIssueIds(issueIds).forEach(row ->
                unresolvedCounts.put((String) row[0], (Long) row[1]));
        }

        return issues.stream()
                .map(issue -> toIssueResponse(issue)
                        .unresolvedCommentCount(unresolvedCounts.getOrDefault(issue.getId(), 0L).intValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public IssueResponse getIssue(String projectId, String issueId, User caller) {
        verifyReadAccess(projectId, caller.getId());
        Issue issue = findIssueInProject(projectId, issueId);
        long count = commentRepository.countUnresolvedByIssueId(issue.getId());
        return toIssueResponse(issue).unresolvedCommentCount((int) count);
    }

    @Transactional
    public IssueResponse patchIssue(String projectId, String issueId, PatchIssueRequest request, User caller) {
        verifyMembership(projectId, caller.getId());
        Issue issue = findIssueInProject(projectId, issueId);

        if (request.getTitle() != null) {
            issue.setTitle(request.getTitle());
        }
        if (request.getDescription() != null) {
            issue.setDescription(request.getDescription());
        }
        if (request.getAssigneeId() != null) {
            String assigneeId = request.getAssigneeId();
            if (assigneeId.isBlank()) {
                issue.setAssignee(null);
            } else {
                User assignee = userRepository.findById(assigneeId)
                        .orElseThrow(() -> new BusinessException("Assignee user not found"));
                if (!projectSecurityService.isProjectMember(projectId, assigneeId)) {
                    throw new BusinessException("Assignee must be a project member");
                }
                issue.setAssignee(assignee);
            }
        }
        IssueStatus previousStatus = issue.getStatus();
        if (request.getStatus() != null) {
            verifyCallerCanChangeStatus(projectId, caller.getId());
            IssueStatus newStatus = toEntityIssueStatus(request.getStatus());
            workItemTransitionService.validateTransition(projectId, issue, newStatus);
            issue.setStatus(newStatus);
            issue.setCurrentStatus(newStatus.name());
        }

        issueRepository.save(issue);

        if (request.getStatus() != null) {
            IssueStatus newStatus = issue.getStatus();
            if (newStatus == IssueStatus.IN_REVIEW && previousStatus != IssueStatus.IN_REVIEW) {
                notificationDispatcher.dispatch(NotificationEvent.of(
                        EventType.ISSUE_SUBMITTED, projectId,
                        Map.of("issueId", issue.getId(), "issueTitle", issue.getTitle())));
            } else if (newStatus == IssueStatus.READY_FOR_DEVELOPMENT) {
                notificationDispatcher.dispatch(NotificationEvent.of(
                        EventType.ISSUE_APPROVED, projectId,
                        Map.of("issueId", issue.getId(), "issueTitle", issue.getTitle())));
            } else if (newStatus == IssueStatus.IN_PROGRESS) {
                Map<String, String> inProgressMeta = new HashMap<>();
                inProgressMeta.put("issueId", issue.getId());
                inProgressMeta.put("issueTitle", issue.getTitle());
                if (issue.getAssignee() != null) {
                    User assignee = issue.getAssignee();
                    String assigneeName = assignee.getName() != null ? assignee.getName() : assignee.getEmail();
                    inProgressMeta.put("assigneeName", assigneeName);
                }
                notificationDispatcher.dispatch(NotificationEvent.of(
                        EventType.ISSUE_IN_PROGRESS, projectId, inProgressMeta));
            } else if (newStatus == IssueStatus.CODE_REVIEW) {
                Map<String, String> codeReviewMeta = new HashMap<>();
                codeReviewMeta.put("issueId", issue.getId());
                codeReviewMeta.put("issueTitle", issue.getTitle());
                if (issue.getGithubPrUrl() != null) {
                    codeReviewMeta.put("prUrl", issue.getGithubPrUrl());
                }
                notificationDispatcher.dispatch(NotificationEvent.of(
                        EventType.ISSUE_IN_CODE_REVIEW, projectId,
                        codeReviewMeta));
            } else if (newStatus == IssueStatus.DONE) {
                notificationDispatcher.dispatch(NotificationEvent.of(
                        EventType.ISSUE_COMPLETED, projectId,
                        Map.of("issueId", issue.getId(), "issueTitle", issue.getTitle())));
            }
            notificationDispatcher.dispatch(NotificationEvent.of(
                    EventType.ISSUE_STATUS_CHANGED, projectId,
                    Map.of(
                            "issueId", issue.getId(),
                            "issueTitle", issue.getTitle(),
                            "projectId", projectId,
                            "fromStatus", previousStatus.name(),
                            "toStatus", newStatus.name()
                    )));
        }

        long count = commentRepository.countUnresolvedByIssueId(issue.getId());
        return toIssueResponse(issue).unresolvedCommentCount((int) count);
    }

    /**
     * System-initiated completion of an issue when its linked pull request merges (GitHub webhook
     * automation). There is intentionally NO {@code User caller} and NO
     * {@link #verifyCallerCanChangeStatus} / {@link #validateTransition} check: this is an automated,
     * system action, not a human status edit.
     *
     * <p><b>Transition policy:</b> a merged PR marks the issue DONE regardless of its current status
     * (DONE is allowed here from ANY non-terminal state, not only CODE_REVIEW as the human
     * {@link #patchIssue} flow enforces). This deliberately preserves the pre-refactor real-world
     * behavior — the old GitHub connector force-set DONE from any state — so that merging a PR is never
     * silently rejected because the issue skipped CODE_REVIEW. Issues already DONE or CLOSED are left
     * untouched (CLOSED is terminal and must not be reopened/overwritten by automation).
     *
     * <p>Fires the same DONE notifications {@link #patchIssue} fires for a DONE transition
     * ({@link EventType#ISSUE_COMPLETED} + {@link EventType#ISSUE_STATUS_CHANGED}).
     *
     * @param projectId     the project the connection belongs to (cross-project guard already applied by caller)
     * @param projectKey    the issue's project key (from the PR body "closes conductor/KEY-N")
     * @param sequenceNumber the issue sequence number
     * @param pullRequestUrl the merged PR's html_url (may be null/blank → not stored)
     */
    @Transactional
    public void completeFromPullRequest(String projectId, String projectKey, int sequenceNumber,
                                        String pullRequestUrl) {
        Issue issue = issueRepository.findByProjectKeyAndSequenceNumber(projectKey, sequenceNumber)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No issue found for " + projectKey + "-" + sequenceNumber));
        if (!issue.getProject().getId().equals(projectId)) {
            throw new EntityNotFoundException(
                    projectKey + "-" + sequenceNumber + " does not belong to project " + projectId);
        }

        if (pullRequestUrl != null && !pullRequestUrl.isBlank()) {
            issue.setGithubPrUrl(pullRequestUrl);
        }

        IssueStatus previousStatus = issue.getStatus();
        boolean alreadyTerminal = previousStatus == IssueStatus.DONE || previousStatus == IssueStatus.CLOSED;
        if (!alreadyTerminal) {
            issue.setStatus(IssueStatus.DONE);
        }
        issueRepository.save(issue);

        if (!alreadyTerminal) {
            notificationDispatcher.dispatch(NotificationEvent.of(
                    EventType.ISSUE_COMPLETED, projectId,
                    Map.of("issueId", issue.getId(), "issueTitle", issue.getTitle())));
            notificationDispatcher.dispatch(NotificationEvent.of(
                    EventType.ISSUE_STATUS_CHANGED, projectId,
                    Map.of(
                            "issueId", issue.getId(),
                            "issueTitle", issue.getTitle(),
                            "projectId", projectId,
                            "fromStatus", previousStatus.name(),
                            "toStatus", IssueStatus.DONE.name()
                    )));
        }
    }

    @Transactional
    public void saveIssueTasks(String issueId, JsonNode tasks) {
        Issue issue = issueRepository.findById(issueId)
                .orElseThrow(() -> new EntityNotFoundException("Issue not found"));
        issue.setIssueTasks(tasks);
        issueRepository.save(issue);
    }

    @Transactional(readOnly = true)
    public JsonNode getIssueTasks(String issueId) {
        Issue issue = issueRepository.findById(issueId)
                .orElseThrow(() -> new EntityNotFoundException("Issue not found"));
        JsonNode tasks = issue.getIssueTasks();
        if (tasks == null) {
            throw new EntityNotFoundException("No tasks found for issue " + issueId);
        }
        return tasks;
    }

    private void verifyMembership(String projectId, String userId) {
        if (!projectSecurityService.isProjectMember(projectId, userId)) {
            throw new ForbiddenException("You must be a project member to perform this action");
        }
    }

    private void verifyReadAccess(String projectId, String userId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));
        if (!projectSecurityService.isProjectMember(projectId, userId)) {
            throw new ForbiddenException("You do not have access to this project");
        }
    }

    private void verifyCallerCanChangeStatus(String projectId, String callerId) {
        projectMemberRepository.findByProjectIdAndUserId(projectId, callerId)
                .ifPresent(member -> {
                    if (member.getRole() == MemberRole.REVIEWER) {
                        throw new ForbiddenException("REVIEWER role cannot change issue status");
                    }
                });
    }

    @Transactional
    public void deleteIssue(String projectId, String issueId) {
        Issue issue = findIssueInProject(projectId, issueId);
        issueRepository.delete(issue);
    }

    private Issue findIssueInProject(String projectId, String issueId) {
        Issue issue = issueRepository.findById(issueId)
                .orElseThrow(() -> new EntityNotFoundException("Issue not found"));
        if (!issue.getProject().getId().equals(projectId)) {
            throw new EntityNotFoundException("Issue not found");
        }
        return issue;
    }

    private IssueType toEntityIssueType(com.conductor.generated.model.IssueType type) {
        return IssueType.valueOf(type.getValue());
    }

    private IssueStatus toEntityIssueStatus(com.conductor.generated.model.IssueStatus status) {
        return IssueStatus.valueOf(status.getValue());
    }

    private com.conductor.generated.model.IssueType toApiIssueType(IssueType type) {
        return com.conductor.generated.model.IssueType.fromValue(type.name());
    }

    private com.conductor.generated.model.IssueStatus toApiIssueStatus(IssueStatus status) {
        return com.conductor.generated.model.IssueStatus.fromValue(status.name());
    }

    private IssueResponse toIssueResponse(Issue issue) {
        String displayId = issue.getProject().getKey() + "-" + issue.getSequenceNumber();
        IssueAssignee assignee = null;
        if (issue.getAssignee() != null) {
            User a = issue.getAssignee();
            assignee = new IssueAssignee(a.getId(), a.getName()).avatarUrl(a.getAvatarUrl());
        }
        return new IssueResponse(
                issue.getId(),
                issue.getProject().getId(),
                toApiIssueType(issue.getType()),
                issue.getTitle(),
                toApiIssueStatus(issue.getStatus()),
                issue.getCreatedBy().getId(),
                issue.getCreatedAt(),
                issue.getUpdatedAt(),
                issue.getSequenceNumber(),
                displayId)
                .description(issue.getDescription())
                .assignee(assignee)
                .githubPrUrl(issue.getGithubPrUrl());
    }
}
