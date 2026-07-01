package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.entity.MemberRole;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.service.view.AvailableTransitionsView;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ReviewRepository;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartTransition;
import com.conductor.workflow.lifecycle.SystemTriggerRegistry;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Applies a Work Item's bound Workflow {@link Statechart} to the {@code WorkItem} aggregate (COND-18 E2): it
 * validates status transitions and types, resolves the initial status on creation, projects the doer's
 * available moves behind {@code GET .../available-transitions}, and applies system-initiated transitions
 * (e.g. GitHub PR-merge). It is the seam {@code WorkItemService} delegates lifecycle decisions to.
 *
 * <p>Status and type are Workflow-defined strings — "which statuses/types are legal" lives in the published
 * {@link Statechart}, not a DB/Java enum. For an ENGINEERING-bound Work Item the resolved statechart reproduces
 * the legacy machine exactly, so an illegal move throws the identical {@code BusinessException} message.
 * Work Items with no explicit binding default to the built-in ENGINEERING workflow.
 */
@Service
public class WorkItemWorkflowService {

    static final String DEFAULT_WORKFLOW = "ENGINEERING";
    static final String APPROVED_VERDICT = "APPROVED";

    /** System trigger fired when a linked GitHub pull request merges. */
    public static final String TRIGGER_PR_MERGED = "pr_merged";

    /** System trigger fired on every Work Item status change (the WORK_ITEM_STATUS_CHANGED event). */
    public static final String TRIGGER_STATUS_CHANGED = "status_changed";

    private final WorkItemRepository workItemRepository;
    private final ProjectSecurityService projectSecurityService;
    private final ProjectMemberRepository projectMemberRepository;
    private final ReviewRepository reviewRepository;
    private final WorkflowDefinitionResolver resolver;
    private final SystemTriggerRegistry systemTriggerRegistry;

    public WorkItemWorkflowService(WorkItemRepository workItemRepository,
                                   ProjectSecurityService projectSecurityService,
                                   ProjectMemberRepository projectMemberRepository,
                                   ReviewRepository reviewRepository,
                                   WorkflowDefinitionResolver resolver,
                                   SystemTriggerRegistry systemTriggerRegistry) {
        this.workItemRepository = workItemRepository;
        this.projectSecurityService = projectSecurityService;
        this.projectMemberRepository = projectMemberRepository;
        this.reviewRepository = reviewRepository;
        this.resolver = resolver;
        this.systemTriggerRegistry = systemTriggerRegistry;
    }

    /** The Workflow's initial status id (e.g. {@code DRAFT}), used to stamp a freshly created Work Item. */
    public String initialStatus(String projectId, String slug) {
        Statechart statechart = resolver.resolveRequired(projectId, slug);
        return statechart.initialStatus()
                .orElseThrow(() -> new BusinessException("Workflow " + slug + " has no initial status"))
                .id();
    }

    /**
     * The Workflow version a freshly created Work Item pins to — the currently-resolved (latest PUBLISHED, or
     * built-in) version. Pinning protects in-flight Work Items from later re-publishes (Wave 5).
     */
    public Integer boundVersion(String projectId, String slug) {
        // Pin to the published snapshot's column version (what resolveFor looks up). Built-in workflows have
        // no snapshot, so fall back to the built-in statechart's declared version.
        return resolver.latestPublishedVersion(projectId, slug).orElseGet(() -> {
            Statechart statechart = resolver.resolveRequired(projectId, slug);
            return statechart.version() != null ? statechart.version() : 1;
        });
    }

    /**
     * Validate that {@code type} is allowed by the Workflow's declared {@code types}. An empty list means the
     * Workflow does not constrain types. Throws {@link BusinessException} on a disallowed type.
     */
    public void validateType(String projectId, String slug, String type) {
        Statechart statechart = resolver.resolveRequired(projectId, slug);
        List<String> allowed = statechart.types();
        if (!allowed.isEmpty() && !allowed.contains(type)) {
            throw new BusinessException("Type '" + type + "' is not allowed by workflow " + slug);
        }
    }

    /**
     * Validate a status change against the Work Item's bound Workflow. Throws the same
     * {@code "Invalid status transition from X to Y"} {@link BusinessException} the legacy hardcoded map threw,
     * so existing behavior and tests are preserved.
     */
    public void validateTransition(String projectId, WorkItem workItem, String newStatus) {
        Statechart statechart = resolveFor(projectId, workItem);
        if (!statechart.hasStatus(newStatus)) {
            throw new BusinessException(
                    "Unknown status '" + newStatus + "' for workflow " + statechart.slug());
        }
        Optional<StatechartTransition> transition =
                statechart.transition(workItem.getCurrentStatus(), newStatus);
        if (transition.isEmpty()) {
            throw new BusinessException(
                    "Invalid status transition from " + workItem.getCurrentStatus() + " to " + newStatus);
        }
        if (transition.get().requiresReview() && !isReviewSatisfied(projectId, workItem, transition.get())) {
            throw new UnprocessableEntityException(
                    "Transition to " + newStatus + " requires an approved review");
        }
    }

    /**
     * The doer projection: the valid next transitions for this actor from the Work Item's current status,
     * computed from the bound Workflow definition. REVIEWERs cannot change status, so they see none.
     */
    @Transactional(readOnly = true)
    public AvailableTransitionsView availableTransitions(String projectId, String workItemId, User caller) {
        if (!projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Work Item not found");
        }
        WorkItem workItem = workItemRepository.findById(workItemId)
                .filter(i -> i.getProject() != null && projectId.equals(i.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));

        Statechart statechart = resolveFor(projectId, workItem);
        String currentStatus = workItem.getCurrentStatus();

        List<AvailableTransitionsView.Transition> transitions = new ArrayList<>();
        if (!isReviewer(projectId, caller.getId())) {
            for (StatechartTransition t : statechart.transitionsFrom(currentStatus)) {
                // Doer projection: a review-gated edge stays hidden until its Review is satisfied.
                if (t.requiresReview() && !isReviewSatisfied(projectId, workItem, t)) {
                    continue;
                }
                transitions.add(new AvailableTransitionsView.Transition(t.to(), t.label(), t.requiresReview()));
            }
        }

        return new AvailableTransitionsView(statechart.slug(), currentStatus, statechart.noun(), transitions);
    }

    /**
     * Apply a system-initiated transition (e.g. a merged GitHub PR, or an internal status-change event): find
     * the Workflow edge out of the Work Item's current status declared for {@code trigger}, advance
     * {@code current_status} to its target, and return the applied transition. Caller-role checks are never
     * applied (there is no human actor). The Review gate is honored <em>selectively</em> per the
     * {@link SystemTriggerRegistry}: {@code pr_merged} bypasses it (the merge is the authority), while a trigger
     * that does not bypass (e.g. {@code status_changed}) leaves a review-gated edge un-fired until its Review is
     * satisfied. Returns empty when the bound Workflow declares no matching edge, or when a gated edge is not yet
     * satisfied (the caller then records side-effects only, with no status change). Does not persist; the
     * caller's transaction saves.
     */
    public Optional<StatechartTransition> applySystemTransition(String projectId, WorkItem workItem, String trigger) {
        Statechart statechart = resolveFor(projectId, workItem);
        Optional<StatechartTransition> transition =
                statechart.triggeredTransitionFrom(workItem.getCurrentStatus(), trigger);
        if (transition.isEmpty()) {
            return Optional.empty();
        }
        StatechartTransition t = transition.get();
        if (!systemTriggerRegistry.bypassesReviewGate(trigger)
                && t.requiresReview() && !isReviewSatisfied(projectId, workItem, t)) {
            return Optional.empty();
        }
        workItem.setCurrentStatus(t.to());
        return transition;
    }

    /** Resolve the {@link Statechart} for the workflow a Work Item is bound to, honoring its pinned version. */
    Statechart resolveFor(String projectId, WorkItem workItem) {
        String slug = workItem.getWorkflow() != null ? workItem.getWorkflow() : DEFAULT_WORKFLOW;
        return resolver.resolveRequired(projectId, slug, workItem.getWorkflowVersion());
    }

    private boolean isReviewer(String projectId, String callerId) {
        return projectMemberRepository.findByProjectIdAndUserId(projectId, callerId)
                .map(m -> m.getRole() == MemberRole.REVIEWER)
                .orElse(false);
    }

    /**
     * A review-gated transition is satisfied when an APPROVED Review is recorded on the Work Item by a member
     * holding the transition's {@code reviewerRole} (or an ADMIN, who outranks any review role). When the
     * transition declares no {@code reviewerRole} — or an unrecognized one — any APPROVED review satisfies the
     * gate.
     */
    private boolean isReviewSatisfied(String projectId, WorkItem workItem, StatechartTransition transition) {
        String role = transition.reviewerRole();
        if (role == null || role.isBlank()) {
            return reviewRepository.existsByWorkItemIdAndVerdict(workItem.getId(), APPROVED_VERDICT);
        }
        MemberRole reviewerRole;
        try {
            reviewerRole = MemberRole.valueOf(role);
        } catch (IllegalArgumentException e) {
            return reviewRepository.existsByWorkItemIdAndVerdict(workItem.getId(), APPROVED_VERDICT);
        }
        // Pass the validated enum NAME — the native query casts it to the member_role PG enum.
        return reviewRepository.existsApprovedByReviewerRole(
                workItem.getId(), projectId, APPROVED_VERDICT, reviewerRole.name());
    }
}
