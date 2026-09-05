package com.conductor.service.publish;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkItemReviewerRepository;
import com.conductor.service.AssetUploadPolicy;
import com.conductor.service.PostScheduleValidator;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.PublishConsentService;
import com.conductor.service.WorkItemWorkflowService;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartTransition;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * "What is stopping this Post from going out?" — answered before anyone tries to move it.
 *
 * <p>The approval gate used to be discoverable only by attempting the transition and reading the 422. An
 * agent that had created a Post, uploaded media and chosen destinations found out four calls later that a
 * video was the wrong shape for one of them; a person found out from a toast. This reads the same
 * validators the gate enforces ({@link PublishGateEvaluator}) and says, at any status, what blocks, what
 * merely warns, which move is next, whether the review is satisfied and whether the creator's consent
 * stands — so the readiness a client shows and the verdict the transition gives are one list.
 *
 * <p>{@link Preflight#earliestFireTime()} is the "as soon as possible" answer: now plus the longest lead
 * any selected destination needs. Deliberately a time and not a sentinel — {@code scheduledFor} is on the
 * calendar and in the bundle hash, and "asap" written there would make what was approved ambiguous.
 */
@Service
public class PublishPreflightService {

    private static final String DEFAULT_WORKFLOW = "ENGINEERING";

    /** The gate's answer, at this moment. */
    public record Preflight(boolean publishing,
                            boolean ready,
                            List<PublishFinding> blockers,
                            List<PublishFinding> warnings,
                            NextTransition nextTransition,
                            ConsentSummary consent,
                            ReviewSummary review,
                            OffsetDateTime earliestFireTime) {

        /** A Work Item on a Workflow that does not publish: nothing to check, nothing in the way. */
        static Preflight notPublishing() {
            return new Preflight(false, true, List.of(), List.of(), null,
                    new ConsentSummary(false, PublishConsentService.Verdict.NOT_REQUIRED),
                    new ReviewSummary(false, 0, false, null), null);
        }
    }

    /** The first gate edge out of the current status — the move a "ready" Post takes next. */
    public record NextTransition(String to, String label, boolean requiresReview) {}

    /** Whether the creator's consent is needed here, and where it stands. */
    public record ConsentSummary(boolean required, PublishConsentService.Verdict verdict) {}

    /**
     * Whether the Workflow has a review gate, how many reviewers are assigned to this item, whether an
     * approval currently satisfies the gate (round- and bundle-bound), and the role the gate asks for.
     */
    public record ReviewSummary(boolean gated, int assignedReviewers, boolean satisfied, String reviewerRole) {}

    private final ProjectSecurityService projectSecurityService;
    private final WorkItemRepository workItemRepository;
    private final WorkflowDefinitionResolver resolver;
    private final PublishingWorkflow publishingWorkflow;
    private final PublishGateEvaluator publishGateEvaluator;
    private final PostScheduleValidator postScheduleValidator;
    private final PostPublishTargetRepository targetRepository;
    private final PublishConsentService publishConsentService;
    private final WorkItemReviewerRepository reviewerRepository;
    private final WorkItemWorkflowService workItemWorkflowService;

    public PublishPreflightService(ProjectSecurityService projectSecurityService,
                                   WorkItemRepository workItemRepository,
                                   WorkflowDefinitionResolver resolver,
                                   PublishingWorkflow publishingWorkflow,
                                   PublishGateEvaluator publishGateEvaluator,
                                   PostScheduleValidator postScheduleValidator,
                                   PostPublishTargetRepository targetRepository,
                                   PublishConsentService publishConsentService,
                                   WorkItemReviewerRepository reviewerRepository,
                                   WorkItemWorkflowService workItemWorkflowService) {
        this.projectSecurityService = projectSecurityService;
        this.workItemRepository = workItemRepository;
        this.resolver = resolver;
        this.publishingWorkflow = publishingWorkflow;
        this.publishGateEvaluator = publishGateEvaluator;
        this.postScheduleValidator = postScheduleValidator;
        this.targetRepository = targetRepository;
        this.publishConsentService = publishConsentService;
        this.reviewerRepository = reviewerRepository;
        this.workItemWorkflowService = workItemWorkflowService;
    }

    @Transactional(readOnly = true)
    public Preflight preflight(String projectId, String workItemId, User caller) {
        if (caller == null || !projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Work Item not found");
        }
        WorkItem workItem = workItemRepository.findById(workItemId)
                .filter(item -> item.getProject() != null && projectId.equals(item.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
        String slug = workItem.getWorkflow() != null ? workItem.getWorkflow() : DEFAULT_WORKFLOW;
        Statechart statechart = resolver.resolveRequired(projectId, slug, workItem.getWorkflowVersion());
        return preflight(projectId, workItem, statechart);
    }

    /** The gate's answer for an item whose statechart the caller already holds. */
    public Preflight preflight(String projectId, WorkItem workItem, Statechart statechart) {
        if (!publishingWorkflow.declaresPublishing(statechart)) {
            return Preflight.notPublishing();
        }
        PublishGateEvaluator.Evaluation evaluation = publishGateEvaluator.evaluate(workItem);
        List<PostPublishTarget> targets = targetRepository.findAllByWorkItemId(workItem.getId());

        PublishConsentService.Verdict verdict = publishConsentService.verdict(workItem);
        ConsentSummary consent = new ConsentSummary(
                verdict != PublishConsentService.Verdict.NOT_REQUIRED, verdict);

        Optional<StatechartTransition> gate = AssetUploadPolicy.reviewGate(statechart);
        ReviewSummary review = new ReviewSummary(
                gate.isPresent(),
                reviewerRepository.findAllByWorkItemId(workItem.getId()).size(),
                gate.isPresent() && workItemWorkflowService.isReviewSatisfied(projectId, workItem, gate.get()),
                gate.map(StatechartTransition::reviewerRole).orElse(null));

        NextTransition next = statechart.transitionsFrom(workItem.getCurrentStatus()).stream()
                .filter(t -> PublishingWorkflow.isGateEdge(statechart, t.from(), t.to()))
                .findFirst()
                .map(t -> new NextTransition(t.to(), t.label(), t.requiresReview()))
                .orElse(null);

        return new Preflight(true, evaluation.ready(), evaluation.blockers(), evaluation.warnings(), next,
                consent, review, postScheduleValidator.earliestFireTime(targets));
    }
}
