package com.conductor.service;

import com.conductor.entity.Issue;
import com.conductor.entity.IssueStatus;
import com.conductor.entity.IssueType;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
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
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class IssueService {

    private static final Map<IssueStatus, Set<IssueStatus>> VALID_TRANSITIONS = Map.of(
        IssueStatus.DRAFT, EnumSet.of(IssueStatus.IN_REVIEW, IssueStatus.CLOSED),
        IssueStatus.IN_REVIEW, EnumSet.of(IssueStatus.READY_FOR_DEVELOPMENT, IssueStatus.DRAFT, IssueStatus.CLOSED),
        IssueStatus.READY_FOR_DEVELOPMENT, EnumSet.of(IssueStatus.IN_PROGRESS, IssueStatus.CLOSED),
        IssueStatus.IN_PROGRESS, EnumSet.of(IssueStatus.CODE_REVIEW, IssueStatus.CLOSED),
        IssueStatus.CODE_REVIEW, EnumSet.of(IssueStatus.DONE, IssueStatus.CLOSED),
        IssueStatus.DONE, EnumSet.noneOf(IssueStatus.class),
        IssueStatus.CLOSED, EnumSet.noneOf(IssueStatus.class)
    );

    private final IssueRepository issueRepository;
    private final ProjectRepository projectRepository;
    private final ProjectSecurityService projectSecurityService;
    private final ProjectMemberRepository projectMemberRepository;
    private final NotificationDispatcher notificationDispatcher;
    private final CommentRepository commentRepository;

    public IssueService(
            IssueRepository issueRepository,
            ProjectRepository projectRepository,
            ProjectSecurityService projectSecurityService,
            ProjectMemberRepository projectMemberRepository,
            NotificationDispatcher notificationDispatcher,
            CommentRepository commentRepository) {
        this.issueRepository = issueRepository;
        this.projectRepository = projectRepository;
        this.projectSecurityService = projectSecurityService;
        this.projectMemberRepository = projectMemberRepository;
        this.notificationDispatcher = notificationDispatcher;
        this.commentRepository = commentRepository;
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
        verifyMembership(projectId, caller.getId());

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
        verifyMembership(projectId, caller.getId());
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
        IssueStatus previousStatus = issue.getStatus();
        if (request.getStatus() != null) {
            verifyCallerCanChangeStatus(projectId, caller.getId());
            IssueStatus newStatus = toEntityIssueStatus(request.getStatus());
            validateTransition(issue.getStatus(), newStatus);
            issue.setStatus(newStatus);
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

    private void verifyMembership(String projectId, String userId) {
        if (!projectSecurityService.isProjectMember(projectId, userId)) {
            throw new EntityNotFoundException("Project not found");
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

    private void validateTransition(IssueStatus from, IssueStatus to) {
        Set<IssueStatus> allowed = VALID_TRANSITIONS.getOrDefault(from, EnumSet.noneOf(IssueStatus.class));
        if (!allowed.contains(to)) {
            throw new BusinessException("Invalid status transition from " + from + " to " + to);
        }
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
                .description(issue.getDescription());
    }
}
