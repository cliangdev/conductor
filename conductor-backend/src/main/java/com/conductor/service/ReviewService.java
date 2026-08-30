package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.Review;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkItemReviewerRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ReviewRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.view.ReviewWithUser;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalBus;
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartTransition;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ReviewService {

    private static final Set<String> VALID_VERDICTS = Set.of("APPROVED", "CHANGES_REQUESTED", "COMMENTED");

    /** The verdict that routes a Work Item back to its Workflow's changes-requested lane. */
    private static final String CHANGES_REQUESTED_VERDICT = "CHANGES_REQUESTED";

    /** The verdict that satisfies a review gate, and the only one a publish-bundle hash is recorded for. */
    private static final String APPROVED_VERDICT = "APPROVED";

    /** The {@code reviewOutcomes} token a Workflow declares to opt its gated edge into that routing. */
    private static final String REQUEST_CHANGES_OUTCOME = "request_changes";

    private final ReviewRepository reviewRepository;
    private final WorkItemReviewerRepository workItemReviewerRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final WorkItemRepository workItemRepository;
    private final UserRepository userRepository;
    private final SignalBus signalBus;
    private final WorkItemWorkflowService workItemWorkflowService;
    private final WorkItemService workItemService;
    private final PublishBundleHasher publishBundleHasher;

    public ReviewService(
            ReviewRepository reviewRepository,
            WorkItemReviewerRepository workItemReviewerRepository,
            ProjectMemberRepository projectMemberRepository,
            WorkItemRepository workItemRepository,
            UserRepository userRepository,
            SignalBus signalBus,
            WorkItemWorkflowService workItemWorkflowService,
            WorkItemService workItemService,
            PublishBundleHasher publishBundleHasher) {
        this.reviewRepository = reviewRepository;
        this.workItemReviewerRepository = workItemReviewerRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.workItemRepository = workItemRepository;
        this.userRepository = userRepository;
        this.signalBus = signalBus;
        this.workItemWorkflowService = workItemWorkflowService;
        this.workItemService = workItemService;
        this.publishBundleHasher = publishBundleHasher;
    }

    @Transactional
    public Review submitReview(String projectId, String workItemId, String verdict, String body, User currentUser) {
        if (!VALID_VERDICTS.contains(verdict)) {
            throw new BusinessException("Invalid verdict. Must be one of: APPROVED, CHANGES_REQUESTED, COMMENTED");
        }

        ProjectMember callerMember = projectMemberRepository.findByProjectIdAndUserId(projectId, currentUser.getId())
                .orElseThrow(() -> new ForbiddenException("You must be a project member to perform this action"));

        if (callerMember.getRole() == MemberRole.CREATOR) {
            throw new ForbiddenException("CREATOR role cannot submit reviews");
        }

        boolean isAssignedReviewer = workItemReviewerRepository
                .findByWorkItemIdAndUserId(workItemId, currentUser.getId())
                .isPresent();

        if (!isAssignedReviewer) {
            throw new ForbiddenException("You are not an assigned reviewer");
        }

        WorkItem workItem = workItemRepository.findById(workItemId).orElse(null);

        Review review = reviewRepository.findByWorkItemIdAndReviewerId(workItemId, currentUser.getId())
                .orElseGet(() -> {
                    Review r = new Review();
                    r.setWorkItemId(workItemId);
                    r.setReviewerId(currentUser.getId());
                    return r;
                });

        review.setVerdict(verdict);
        review.setBody(body);
        review.setSubmittedAt(OffsetDateTime.now());
        bindToBundle(review, workItem, verdict);

        reviewRepository.save(review);

        String workItemTitle = workItem != null ? workItem.getTitle() : workItemId;
        signalBus.publish(Signal.of(
                SignalTypes.CONDUCTOR_WORK_ITEM_REVIEW_SUBMITTED, projectId, workItemId, Instant.now(),
                Map.of("workItemId", workItemId, "workItemTitle", workItemTitle, "verdict", verdict),
                new SignalOrigin("work_item", workItemId)));

        routeOnVerdict(projectId, workItem, verdict);

        return review;
    }

    /**
     * Pin the verdict to what it was cast against (COND-23): the review round now open on the item, and — when
     * the item carries a publish bundle — the hash of that bundle. Both are re-stamped on every submission,
     * because the row is upserted per (work item, reviewer) and a reviewer changing their mind is a new verdict
     * about the bundle as it stands now.
     *
     * <p>Only an APPROVED verdict records a hash; anything else clears it, so a reviewer flipping an earlier
     * approval to CHANGES_REQUESTED cannot leave a hash behind that outlives the approval it belonged to.
     * An item with no publish targets records no hash at all and keeps gating on the review round alone.
     */
    private void bindToBundle(Review review, WorkItem workItem, String verdict) {
        if (workItem == null) {
            return;
        }
        review.setReviewRound(workItem.getCurrentReviewRound());
        boolean approvesABundle = APPROVED_VERDICT.equals(verdict) && publishBundleHasher.appliesTo(workItem);
        review.setBundleHash(approvesABundle ? publishBundleHasher.hash(workItem) : null);
    }

    /**
     * Move the Work Item onto the lane its bound Workflow declares for a {@code CHANGES_REQUESTED} verdict,
     * so a reviewer's rejection lands the item back with its author instead of leaving it parked in the
     * review status waiting for a human to move it by hand.
     *
     * <p>Entirely definition-driven — no status name is hardcoded here. A Workflow opts in by declaring both
     * halves of the contract:
     * <ol>
     *   <li>the review-gated edge out of the current status lists {@code request_changes} among its
     *       {@code reviewOutcomes} (i.e. the Workflow says this gate can be rejected, not just approved), and</li>
     *   <li>the Workflow declares an edge from the current status to a status whose id <em>is</em> the verdict
     *       ({@code CHANGES_REQUESTED}) — that edge, and the status it targets, are the definition's own
     *       statement of where a rejected item goes.</li>
     * </ol>
     * A Workflow declaring neither (ENGINEERING, whose gated {@code CODE_REVIEW -> DONE} edge has no
     * changes-requested lane) is untouched: reviews there stay advisory exactly as before.
     *
     * <p>An {@code APPROVED} verdict deliberately moves nothing. Approval only <em>satisfies</em> the gate;
     * choosing to take the now-unblocked edge stays with the doer (or with a system trigger such as
     * {@code pr_merged}), which is what keeps the gate a gate rather than an auto-advance.
     */
    private void routeOnVerdict(String projectId, WorkItem workItem, String verdict) {
        if (workItem == null || !CHANGES_REQUESTED_VERDICT.equals(verdict)) {
            return;
        }
        Statechart statechart = workItemWorkflowService.resolveFor(projectId, workItem);
        String fromStatus = workItem.getCurrentStatus();

        boolean gateAcceptsRejection = statechart.transitionsFrom(fromStatus).stream()
                .filter(StatechartTransition::requiresReview)
                .anyMatch(t -> t.reviewOutcomes().contains(REQUEST_CHANGES_OUTCOME));
        if (!gateAcceptsRejection) {
            return;
        }

        Optional<StatechartTransition> route = statechart.transition(fromStatus, verdict);
        if (route.isEmpty()) {
            return;
        }

        // The rejection closes the review round. Any approval already sitting on the item belongs to the round
        // just closed, so it can no longer satisfy the gate when the item is resubmitted — which is what stops
        // reviewer A's approval from clearing the gate after reviewer B sent the item back.
        workItem.setCurrentReviewRound(workItem.getCurrentReviewRound() + 1);
        workItem.setCurrentStatus(route.get().to());
        workItemRepository.save(workItem);
        workItemService.publishStatusChanged(projectId, workItem, fromStatus, workItem.getCurrentStatus(), null);
    }

    @Transactional(readOnly = true)
    public List<ReviewWithUser> listReviews(String projectId, String workItemId, User currentUser) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, currentUser.getId())) {
            throw new EntityNotFoundException("Project not found");
        }

        return reviewRepository.findAllByWorkItemId(workItemId).stream()
                .map(this::toReviewWithUser)
                .toList();
    }

    private ReviewWithUser toReviewWithUser(Review review) {
        User user = userRepository.findById(review.getReviewerId()).orElse(null);
        return new ReviewWithUser(
                review.getReviewerId(),
                review.getVerdict(),
                review.getSubmittedAt(),
                review.getBody(),
                user != null ? user.getName() : null,
                user != null ? user.getAvatarUrl() : null);
    }
}
