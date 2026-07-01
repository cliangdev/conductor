package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.fasterxml.jackson.databind.JsonNode;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDispatcher;
import com.conductor.notification.NotificationEvent;
import com.conductor.repository.CommentRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartTransition;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class WorkItemService {

    private static final Logger log = LoggerFactory.getLogger(WorkItemService.class);

    // COND-18: the status state machine and the legal status/type vocabulary live in the bound Workflow
    // definition (statechart), not in this class or a DB enum; transitions and types are validated by
    // WorkItemWorkflowService against the resolved Statechart.

    private final WorkItemRepository workItemRepository;
    private final ProjectRepository projectRepository;
    private final ProjectSecurityService projectSecurityService;
    private final ProjectMemberRepository projectMemberRepository;
    private final NotificationDispatcher notificationDispatcher;
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final WorkItemWorkflowService workItemWorkflowService;
    private final AssetService assetService;

    public WorkItemService(
            WorkItemRepository workItemRepository,
            ProjectRepository projectRepository,
            ProjectSecurityService projectSecurityService,
            ProjectMemberRepository projectMemberRepository,
            NotificationDispatcher notificationDispatcher,
            CommentRepository commentRepository,
            UserRepository userRepository,
            WorkItemWorkflowService workItemWorkflowService,
            AssetService assetService) {
        this.workItemRepository = workItemRepository;
        this.projectRepository = projectRepository;
        this.projectSecurityService = projectSecurityService;
        this.projectMemberRepository = projectMemberRepository;
        this.notificationDispatcher = notificationDispatcher;
        this.commentRepository = commentRepository;
        this.userRepository = userRepository;
        this.workItemWorkflowService = workItemWorkflowService;
        this.assetService = assetService;
    }

    /**
     * Canonical create-Work-Item business logic, returning the persisted entity. The v2 controller maps the
     * entity to its response DTO. Takes plain fields so the service stays decoupled from any generated DTO.
     */
    @Transactional
    public WorkItem createWorkItem(String projectId, String type, String title, String description,
                                   String workflowSlug, User caller) {
        verifyMembership(projectId, caller.getId());

        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));

        // COND-18: bind the Work Item to its Workflow (defaults to the built-in ENGINEERING) and validate
        // its type + initial status against that Workflow's definition.
        String workflow = workflowSlug != null && !workflowSlug.isBlank()
                ? workflowSlug : WorkItemWorkflowService.DEFAULT_WORKFLOW;
        workItemWorkflowService.validateType(projectId, workflow, type);

        WorkItem issue = new WorkItem();
        issue.setProject(project);
        issue.setType(type);
        issue.setTitle(title);
        issue.setDescription(description);
        issue.setCreatedBy(caller);
        issue.setWorkflow(workflow);
        issue.setWorkflowVersion(workItemWorkflowService.boundVersion(projectId, workflow));
        issue.setCurrentStatus(workItemWorkflowService.initialStatus(projectId, workflow));

        Integer nextSeq = workItemRepository.findMaxSequenceNumberByProjectId(projectId) + 1;
        issue.setSequenceNumber(nextSeq);

        workItemRepository.save(issue);
        return issue;
    }

    /**
     * Canonical patch-Work-Item business logic, returning the persisted entity. The v2 controller maps the
     * entity to its response DTO. Each nullable field follows PATCH semantics: {@code null} means "field
     * absent — leave unchanged"; for {@code assigneeId} a blank string unassigns. Takes plain fields so the
     * service stays decoupled from any generated DTO version.
     */
    @Transactional
    public WorkItem patchWorkItem(String projectId, String workItemId, String title, String description,
                                  String status, String assigneeId, User caller) {
        verifyMembership(projectId, caller.getId());
        WorkItem issue = findIssueInProject(projectId, workItemId);

        if (title != null) {
            issue.setTitle(title);
        }
        if (description != null) {
            issue.setDescription(description);
        }
        if (assigneeId != null) {
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
        String previousStatus = issue.getCurrentStatus();
        boolean statusChanged = false;
        if (status != null) {
            verifyCallerCanChangeStatus(projectId, caller.getId());
            workItemWorkflowService.validateTransition(projectId, issue, status);
            issue.setCurrentStatus(status);
            statusChanged = true;
        }

        workItemRepository.save(issue);

        if (statusChanged) {
            // PR link (if any) lives in github_pr Assets now, not on the issue; surfaced on the merge event.
            dispatchStatusChanged(projectId, issue, previousStatus, issue.getCurrentStatus(), null);
        }

        return issue;
    }

    /**
     * Read a single Work Item entity in a project, applying the same membership/read-access check the v1
     * {@code getIssue} uses. Used by the v2 controller, which maps the entity to its v2 response (so it can
     * surface the bound {@code workflow}).
     */
    @Transactional(readOnly = true)
    public WorkItem getWorkItemEntity(String projectId, String workItemId, User caller) {
        verifyReadAccess(projectId, caller.getId());
        return findIssueInProject(projectId, workItemId);
    }

    /**
     * List Work Item entities in a project with the same optional type/status/workflow filters as v1
     * {@code listIssues}. Used by the v2 controller.
     */
    @Transactional(readOnly = true)
    public List<WorkItem> listWorkItemEntities(String projectId, String type, String status, String workflow,
                                               User caller) {
        verifyReadAccess(projectId, caller.getId());
        String typeFilter = (type != null && !type.isBlank()) ? type : null;
        String statusFilter = (status != null && !status.isBlank()) ? status : null;
        String workflowFilter = (workflow != null && !workflow.isBlank()) ? workflow : null;
        return workItemRepository.findByProjectFiltered(projectId, typeFilter, statusFilter, workflowFilter);
    }

    /**
     * Resolve a Work Item by its human-readable display id (e.g. "COND-42"). The trailing integer after the
     * last {@code -} is the project-scoped sequence number; the project key prefix is informational. Applies
     * the same read-access check as {@link #getWorkItemEntity}. Throws {@link EntityNotFoundException} (→ 404)
     * when the display id is malformed, the sequence number does not exist, or it belongs to another project.
     */
    @Transactional(readOnly = true)
    public WorkItem resolveByDisplayId(String projectId, String displayId, User caller) {
        verifyReadAccess(projectId, caller.getId());
        Integer sequenceNumber = parseSequenceNumber(displayId);
        if (sequenceNumber == null) {
            throw new EntityNotFoundException("Work Item not found");
        }
        return workItemRepository.findByProjectIdAndSequenceNumber(projectId, sequenceNumber)
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
    }

    private static Integer parseSequenceNumber(String displayId) {
        if (displayId == null) {
            return null;
        }
        int dash = displayId.lastIndexOf('-');
        String tail = dash >= 0 ? displayId.substring(dash + 1) : displayId;
        try {
            return Integer.valueOf(tail.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Unresolved comment count for a single Work Item (optional enrichment for v2 responses). */
    @Transactional(readOnly = true)
    public long unresolvedCommentCount(String workItemId) {
        return commentRepository.countUnresolvedByWorkItemId(workItemId);
    }

    /** Unresolved comment counts keyed by Work Item id, for a batch (optional enrichment for v2 lists). */
    @Transactional(readOnly = true)
    public Map<String, Long> unresolvedCommentCounts(List<String> workItemIds) {
        Map<String, Long> counts = new HashMap<>();
        if (workItemIds != null && !workItemIds.isEmpty()) {
            commentRepository.countUnresolvedByWorkItemIds(workItemIds).forEach(row ->
                    counts.put((String) row[0], (Long) row[1]));
        }
        return counts;
    }

    /**
     * System-initiated transition when a Work Item's linked pull request merges (GitHub webhook automation).
     * There is intentionally NO {@code User caller} and NO caller-role / Review-gate check: this is an
     * automated system action and the merge is the authority.
     *
     * <p><b>Transition policy:</b> the merge advances the Work Item through the Workflow edge declared with
     * {@code trigger: pr_merged} from its current status (for ENGINEERING that is {@code CODE_REVIEW → DONE}).
     * If the bound Workflow declares no such edge from the current status (e.g. the issue is already terminal,
     * or skipped the review status), the PR is recorded as an Asset and the status is left unchanged. This is
     * the statechart-driven generalization of the former "force DONE from any state".
     *
     * @param projectId      the project the connection belongs to (cross-project guard already applied by caller)
     * @param projectKey     the issue's project key (from the PR body "closes conductor/KEY-N")
     * @param sequenceNumber the issue sequence number
     * @param pullRequestUrl the merged PR's html_url (may be null/blank → not stored)
     */
    @Transactional
    public void completeFromPullRequest(String projectId, String projectKey, int sequenceNumber,
                                        String pullRequestUrl) {
        WorkItem issue = workItemRepository.findByProjectKeyAndSequenceNumber(projectKey, sequenceNumber)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No issue found for " + projectKey + "-" + sequenceNumber));
        if (!issue.getProject().getId().equals(projectId)) {
            throw new EntityNotFoundException(
                    projectKey + "-" + sequenceNumber + " does not belong to project " + projectId);
        }

        String previousStatus = issue.getCurrentStatus();
        Optional<StatechartTransition> applied = workItemWorkflowService.applySystemTransition(
                projectId, issue, WorkItemWorkflowService.TRIGGER_PR_MERGED);
        workItemRepository.save(issue);

        // COND-18 E5: record the merged PR as a github_pr Asset. Idempotent on (issue, type, ref).
        assetService.recordPullRequestAsset(issue, pullRequestUrl);

        if (applied.isPresent()) {
            dispatchStatusChanged(projectId, issue, previousStatus, issue.getCurrentStatus(), pullRequestUrl);
        } else {
            log.info("PR merge for {}-{}: workflow {} declares no '{}' transition from status {}; "
                            + "recorded PR asset only, status unchanged",
                    projectKey, sequenceNumber, issue.getWorkflow(),
                    WorkItemWorkflowService.TRIGGER_PR_MERGED, previousStatus);
        }
    }

    /**
     * Fire the single, Workflow-agnostic {@link EventType#ISSUE_STATUS_CHANGED} event, enriched with the
     * Workflow's {@code noun} and the target status's display label/category so the notification provider can
     * format it for any Workflow without hardcoded status names.
     */
    private void dispatchStatusChanged(String projectId, WorkItem issue, String fromStatus, String toStatus,
                                       String prUrl) {
        Statechart statechart = workItemWorkflowService.resolveFor(projectId, issue);
        Map<String, String> meta = new HashMap<>();
        meta.put("issueId", issue.getId());
        meta.put("issueTitle", issue.getTitle());
        meta.put("projectId", projectId);
        if (issue.getWorkflow() != null) {
            meta.put("workflow", issue.getWorkflow());
        }
        meta.put("noun", statechart.noun());
        meta.put("fromStatus", fromStatus);
        meta.put("toStatus", toStatus);
        statechart.status(toStatus).ifPresent(s -> {
            meta.put("toStatusLabel", s.displayLabel());
            if (s.category() != null) {
                meta.put("toCategory", s.category());
            }
        });
        if (issue.getAssignee() != null) {
            User a = issue.getAssignee();
            meta.put("assigneeName", a.getName() != null ? a.getName() : a.getEmail());
        }
        if (prUrl != null && !prUrl.isBlank()) {
            meta.put("prUrl", prUrl);
        }
        notificationDispatcher.dispatch(NotificationEvent.of(EventType.ISSUE_STATUS_CHANGED, projectId, meta));
    }

    @Transactional
    public void saveIssueTasks(String issueId, JsonNode tasks) {
        WorkItem issue = workItemRepository.findById(issueId)
                .orElseThrow(() -> new EntityNotFoundException("Issue not found"));
        issue.setIssueTasks(tasks);
        workItemRepository.save(issue);
    }

    @Transactional(readOnly = true)
    public JsonNode getIssueTasks(String issueId) {
        WorkItem issue = workItemRepository.findById(issueId)
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
        WorkItem issue = findIssueInProject(projectId, issueId);
        workItemRepository.delete(issue);
    }

    private WorkItem findIssueInProject(String projectId, String issueId) {
        WorkItem issue = workItemRepository.findById(issueId)
                .orElseThrow(() -> new EntityNotFoundException("Issue not found"));
        if (!issue.getProject().getId().equals(projectId)) {
            throw new EntityNotFoundException("Issue not found");
        }
        return issue;
    }
}
