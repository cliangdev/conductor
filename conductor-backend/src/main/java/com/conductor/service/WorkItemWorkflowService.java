package com.conductor.service;

import com.conductor.entity.Issue;
import com.conductor.entity.MemberRole;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.generated.model.AvailableTransition;
import com.conductor.generated.model.AvailableTransitionsResponse;
import com.conductor.repository.IssueRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ReviewRepository;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartTransition;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Applies a Work Item's bound Workflow {@link Statechart} to the {@code Issue} aggregate (COND-18 E2): it
 * validates status transitions and types, resolves the initial status on creation, projects the doer's
 * available moves behind {@code GET .../available-transitions}, and applies system-initiated transitions
 * (e.g. GitHub PR-merge). It is the seam {@code IssueService} delegates lifecycle decisions to.
 *
 * <p>Status and type are Workflow-defined strings — "which statuses/types are legal" lives in the published
 * {@link Statechart}, not a DB/Java enum. For an ENGINEERING-bound issue the resolved statechart reproduces
 * the legacy machine exactly, so an illegal move throws the identical {@code BusinessException} message.
 * Issues with no explicit binding default to the built-in ENGINEERING workflow.
 */
@Service
public class WorkItemWorkflowService {

    static final String DEFAULT_WORKFLOW = "ENGINEERING";
    static final String APPROVED_VERDICT = "APPROVED";

    /** System trigger fired when a linked GitHub pull request merges. */
    public static final String TRIGGER_PR_MERGED = "pr_merged";

    private final IssueRepository issueRepository;
    private final ProjectSecurityService projectSecurityService;
    private final ProjectMemberRepository projectMemberRepository;
    private final ReviewRepository reviewRepository;
    private final WorkflowDefinitionResolver resolver;

    public WorkItemWorkflowService(IssueRepository issueRepository,
                                   ProjectSecurityService projectSecurityService,
                                   ProjectMemberRepository projectMemberRepository,
                                   ReviewRepository reviewRepository,
                                   WorkflowDefinitionResolver resolver) {
        this.issueRepository = issueRepository;
        this.projectSecurityService = projectSecurityService;
        this.projectMemberRepository = projectMemberRepository;
        this.reviewRepository = reviewRepository;
        this.resolver = resolver;
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
     * Validate a status change against the issue's bound Workflow. Throws the same
     * {@code "Invalid status transition from X to Y"} {@link BusinessException} the legacy hardcoded map threw,
     * so existing behavior and tests are preserved.
     */
    public void validateTransition(String projectId, Issue issue, String newStatus) {
        Statechart statechart = resolveFor(projectId, issue);
        if (!statechart.hasStatus(newStatus)) {
            throw new BusinessException(
                    "Unknown status '" + newStatus + "' for workflow " + statechart.slug());
        }
        Optional<StatechartTransition> transition =
                statechart.transition(issue.getCurrentStatus(), newStatus);
        if (transition.isEmpty()) {
            throw new BusinessException(
                    "Invalid status transition from " + issue.getCurrentStatus() + " to " + newStatus);
        }
        if (transition.get().requiresReview() && !isReviewSatisfied(projectId, issue, transition.get())) {
            throw new UnprocessableEntityException(
                    "Transition to " + newStatus + " requires an approved review");
        }
    }

    /**
     * The doer projection: the valid next transitions for this actor from the issue's current status,
     * computed from the bound Workflow definition. REVIEWERs cannot change status, so they see none.
     */
    @Transactional(readOnly = true)
    public AvailableTransitionsResponse availableTransitions(String projectId, String issueId, User caller) {
        if (!projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Issue not found");
        }
        Issue issue = issueRepository.findById(issueId)
                .filter(i -> i.getProject() != null && projectId.equals(i.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Issue not found"));

        Statechart statechart = resolveFor(projectId, issue);
        String currentStatus = issue.getCurrentStatus();

        List<AvailableTransition> transitions = new ArrayList<>();
        if (!isReviewer(projectId, caller.getId())) {
            for (StatechartTransition t : statechart.transitionsFrom(currentStatus)) {
                // Doer projection: a review-gated edge stays hidden until its Review is satisfied.
                if (t.requiresReview() && !isReviewSatisfied(projectId, issue, t)) {
                    continue;
                }
                AvailableTransition at = new AvailableTransition(t.to(), t.label());
                at.setRequiresReview(t.requiresReview());
                transitions.add(at);
            }
        }

        AvailableTransitionsResponse response =
                new AvailableTransitionsResponse(statechart.slug(), currentStatus, transitions);
        response.setNoun(statechart.noun());
        return response;
    }

    /**
     * Apply a system-initiated transition (e.g. a merged GitHub PR): find the Workflow edge out of the
     * issue's current status declared for {@code trigger}, advance {@code current_status} to its target, and
     * return the applied transition. The Review gate and caller-role checks are intentionally NOT applied —
     * the external event is the authority. Returns empty when the bound Workflow declares no such edge from
     * the current status (the caller then records side-effects only, with no status change). Does not persist;
     * the caller's transaction saves.
     */
    public Optional<StatechartTransition> applySystemTransition(String projectId, Issue issue, String trigger) {
        Statechart statechart = resolveFor(projectId, issue);
        Optional<StatechartTransition> transition =
                statechart.triggeredTransitionFrom(issue.getCurrentStatus(), trigger);
        transition.ifPresent(t -> issue.setCurrentStatus(t.to()));
        return transition;
    }

    /** Resolve the {@link Statechart} for the workflow a Work Item is bound to, honoring its pinned version. */
    Statechart resolveFor(String projectId, Issue issue) {
        String slug = issue.getWorkflow() != null ? issue.getWorkflow() : DEFAULT_WORKFLOW;
        return resolver.resolveRequired(projectId, slug, issue.getWorkflowVersion());
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
    private boolean isReviewSatisfied(String projectId, Issue issue, StatechartTransition transition) {
        String role = transition.reviewerRole();
        if (role == null || role.isBlank()) {
            return reviewRepository.existsByIssueIdAndVerdict(issue.getId(), APPROVED_VERDICT);
        }
        MemberRole reviewerRole;
        try {
            reviewerRole = MemberRole.valueOf(role);
        } catch (IllegalArgumentException e) {
            return reviewRepository.existsByIssueIdAndVerdict(issue.getId(), APPROVED_VERDICT);
        }
        return reviewRepository.existsApprovedByReviewerRole(
                issue.getId(), projectId, APPROVED_VERDICT, reviewerRole);
    }
}
