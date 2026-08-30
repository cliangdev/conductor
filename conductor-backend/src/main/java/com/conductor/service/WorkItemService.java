package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.fasterxml.jackson.databind.JsonNode;
import com.conductor.repository.CommentRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalBus;
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartTransition;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
    private final SignalBus signalBus;
    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final WorkItemWorkflowService workItemWorkflowService;
    private final AssetService assetService;

    public WorkItemService(
            WorkItemRepository workItemRepository,
            ProjectRepository projectRepository,
            ProjectSecurityService projectSecurityService,
            ProjectMemberRepository projectMemberRepository,
            SignalBus signalBus,
            CommentRepository commentRepository,
            UserRepository userRepository,
            WorkItemWorkflowService workItemWorkflowService,
            AssetService assetService) {
        this.workItemRepository = workItemRepository;
        this.projectRepository = projectRepository;
        this.projectSecurityService = projectSecurityService;
        this.projectMemberRepository = projectMemberRepository;
        this.signalBus = signalBus;
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

        WorkItem workItem = new WorkItem();
        workItem.setProject(project);
        workItem.setType(type);
        workItem.setTitle(title);
        workItem.setDescription(description);
        workItem.setCreatedBy(caller);
        workItem.setWorkflow(workflow);
        workItem.setWorkflowVersion(workItemWorkflowService.boundVersion(projectId, workflow));
        workItem.setCurrentStatus(workItemWorkflowService.initialStatus(projectId, workflow));

        Integer nextSeq = workItemRepository.findMaxSequenceNumberByProjectId(projectId) + 1;
        workItem.setSequenceNumber(nextSeq);

        workItemRepository.save(workItem);
        return workItem;
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
        return patchWorkItem(projectId, workItemId, title, description, status, assigneeId, null, null, caller);
    }

    /**
     * Patch overload carrying the generic per-item scheduling fields (V111). {@code scheduledFor} and
     * {@code scheduleTimezone} follow the same PATCH semantics as the rest: {@code null} means "field
     * absent — leave unchanged"; a blank {@code scheduleTimezone} clears the stored zone (mirroring how a
     * blank {@code assigneeId} unassigns). {@code scheduleTimezone} must be a zone {@link ZoneId#of} can
     * resolve — an unknown zone is a {@link BusinessException} (400), raised before anything is written so
     * a rejected patch persists none of its other fields either.
     */
    @Transactional
    public WorkItem patchWorkItem(String projectId, String workItemId, String title, String description,
                                  String status, String assigneeId, OffsetDateTime scheduledFor,
                                  String scheduleTimezone, User caller) {
        verifyMembership(projectId, caller.getId());
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);

        String validatedTimezone = validateTimezone(scheduleTimezone);

        if (scheduledFor != null) {
            workItem.setScheduledFor(scheduledFor);
        }
        if (scheduleTimezone != null) {
            workItem.setScheduleTimezone(validatedTimezone);
        }

        if (title != null) {
            workItem.setTitle(title);
        }
        if (description != null) {
            workItem.setDescription(description);
        }
        if (assigneeId != null) {
            if (assigneeId.isBlank()) {
                workItem.setAssignee(null);
            } else {
                User assignee = userRepository.findById(assigneeId)
                        .orElseThrow(() -> new BusinessException("Assignee user not found"));
                if (!projectSecurityService.isProjectMember(projectId, assigneeId)) {
                    throw new BusinessException("Assignee must be a project member");
                }
                workItem.setAssignee(assignee);
            }
        }
        String previousStatus = workItem.getCurrentStatus();
        boolean statusChanged = false;
        if (status != null) {
            verifyCallerCanChangeStatus(projectId, caller.getId());
            workItemWorkflowService.validateTransition(projectId, workItem, status);
            workItem.setCurrentStatus(status);
            statusChanged = true;
        }

        workItemRepository.save(workItem);

        if (statusChanged) {
            // PR link (if any) lives in github_pr Assets now, not on the Work Item; surfaced on the merge event.
            publishStatusChanged(projectId, workItem, previousStatus, workItem.getCurrentStatus(), null);
        }

        return workItem;
    }

    /**
     * Read a single Work Item entity in a project, applying the standard membership/read-access check.
     * Used by the v2 controller, which maps the entity to its v2 response (so it can surface the bound
     * {@code workflow}).
     */
    @Transactional(readOnly = true)
    public WorkItem getWorkItemEntity(String projectId, String workItemId, User caller) {
        verifyReadAccess(projectId, caller.getId());
        return findWorkItemInProject(projectId, workItemId);
    }

    /**
     * List Work Item entities in a project with the standard optional type/status/workflow filters.
     * Used by the v2 controller.
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
     * If the bound Workflow declares no such edge from the current status (e.g. the Work Item is already terminal,
     * or skipped the review status), the PR is recorded as an Asset and the status is left unchanged. This is
     * the statechart-driven generalization of the former "force DONE from any state".
     *
     * @param projectId      the project the connection belongs to (cross-project guard already applied by caller)
     * @param projectKey     the Work Item's project key (from the PR body "closes conductor/KEY-N")
     * @param sequenceNumber the Work Item sequence number
     * @param pullRequestUrl the merged PR's html_url (may be null/blank → not stored)
     */
    @Transactional
    public void completeFromPullRequest(String projectId, String projectKey, int sequenceNumber,
                                        String pullRequestUrl) {
        WorkItem workItem = workItemRepository.findByProjectKeyAndSequenceNumber(projectKey, sequenceNumber)
                .orElseThrow(() -> new EntityNotFoundException(
                        "No Work Item found for " + projectKey + "-" + sequenceNumber));
        if (!workItem.getProject().getId().equals(projectId)) {
            throw new EntityNotFoundException(
                    projectKey + "-" + sequenceNumber + " does not belong to project " + projectId);
        }

        String previousStatus = workItem.getCurrentStatus();
        Optional<StatechartTransition> applied = workItemWorkflowService.applySystemTransition(
                projectId, workItem, WorkItemWorkflowService.TRIGGER_PR_MERGED);
        workItemRepository.save(workItem);

        // COND-18 E5: record the merged PR as a github_pr Asset. Idempotent on (workItem, type, ref).
        // Must run BEFORE publishStatusChanged below: KnowledgeSignalSink's terminal-status ingestion
        // snapshots the Work Item synchronously off this same transaction, and it's the pending Hibernate
        // session's auto-flush of this insert that puts the merged-PR Asset into that snapshot. Reorder
        // these two calls and a PR-merge-to-terminal transition would file without its own PR asset.
        assetService.recordAsset(workItem, "github_pr", pullRequestUrl, "Pull Request", "link");

        if (applied.isPresent()) {
            publishStatusChanged(projectId, workItem, previousStatus, workItem.getCurrentStatus(), pullRequestUrl);
        } else {
            log.info("PR merge for {}-{}: workflow {} declares no '{}' transition from status {}; "
                            + "recorded PR asset only, status unchanged",
                    projectKey, sequenceNumber, workItem.getWorkflow(),
                    WorkItemWorkflowService.TRIGGER_PR_MERGED, previousStatus);
        }
    }

    /**
     * Fire the single, Workflow-agnostic {@link EventType#WORK_ITEM_STATUS_CHANGED} event, enriched with the
     * Workflow's {@code noun} and the target status's display label/category so the notification provider can
     * format it for any Workflow without hardcoded status names. Public so the {@code LifecycleTriggerDispatcher}
     * publishes an identically-enriched event per cascade hop rather than duplicating the enrichment.
     */
    public void publishStatusChanged(String projectId, WorkItem workItem, String fromStatus, String toStatus,
                                     String prUrl) {
        Statechart statechart = workItemWorkflowService.resolveFor(projectId, workItem);
        Map<String, Object> meta = new HashMap<>();
        meta.put("workItemId", workItem.getId());
        meta.put("workItemTitle", workItem.getTitle());
        meta.put("projectId", projectId);
        if (workItem.getWorkflow() != null) {
            meta.put("workflow", workItem.getWorkflow());
        }
        meta.put("noun", statechart.noun());
        meta.put("fromStatus", fromStatus);
        meta.put("toStatus", toStatus);
        statechart.status(toStatus).ifPresent(s -> {
            meta.put("toStatusLabel", s.displayLabel());
            if (s.category() != null) {
                meta.put("toCategory", s.category());
            }
            // Stamped alongside (not instead of) toCategory: WorkflowSeeder writes workflow_definitions
            // directly, bypassing WorkflowDefinitionValidator, so a seeded-but-category-less Workflow can
            // reach a terminal status with toCategory absent. KnowledgeSignalSink's terminal gate falls
            // back to this explicit boolean so that gap doesn't silently file nothing forever.
            meta.put("toTerminal", s.terminal());
        });
        if (workItem.getAssignee() != null) {
            User a = workItem.getAssignee();
            meta.put("assigneeName", a.getName() != null ? a.getName() : a.getEmail());
        }
        if (prUrl != null && !prUrl.isBlank()) {
            meta.put("prUrl", prUrl);
        }
        signalBus.publish(Signal.of(SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED, projectId, workItem.getId(),
                Instant.now(), meta, new SignalOrigin("work_item", workItem.getId())));
    }

    @Transactional
    public void saveWorkItemTasks(String workItemId, JsonNode tasks) {
        WorkItem workItem = workItemRepository.findById(workItemId)
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
        workItem.setWorkItemTasks(tasks);
        workItemRepository.save(workItem);
    }

    @Transactional(readOnly = true)
    public JsonNode getWorkItemTasks(String workItemId) {
        WorkItem workItem = workItemRepository.findById(workItemId)
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
        JsonNode tasks = workItem.getWorkItemTasks();
        if (tasks == null) {
            throw new EntityNotFoundException("No tasks found for Work Item " + workItemId);
        }
        return tasks;
    }

    /**
     * Resolve a caller-supplied schedule timezone to the value to store: {@code null} for absent or blank
     * (blank clears), otherwise the zone id itself once {@link ZoneId#of} confirms it resolves. Unknown
     * zones surface as a {@link BusinessException} so {@code GlobalExceptionHandler} renders an RFC 7807
     * 400 rather than letting a bad zone reach the column.
     */
    private static String validateTimezone(String scheduleTimezone) {
        if (scheduleTimezone == null || scheduleTimezone.isBlank()) {
            return null;
        }
        try {
            ZoneId.of(scheduleTimezone);
        } catch (DateTimeException e) {
            throw new BusinessException("Unknown time zone: '" + scheduleTimezone
                    + "'. Expected an IANA zone id such as America/New_York.");
        }
        return scheduleTimezone;
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
                        throw new ForbiddenException("REVIEWER role cannot change Work Item status");
                    }
                });
    }

    @Transactional
    public void deleteWorkItem(String projectId, String workItemId) {
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);
        workItemRepository.delete(workItem);
    }

    private WorkItem findWorkItemInProject(String projectId, String workItemId) {
        WorkItem workItem = workItemRepository.findById(workItemId)
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
        if (!workItem.getProject().getId().equals(projectId)) {
            throw new EntityNotFoundException("Work Item not found");
        }
        return workItem;
    }
}
