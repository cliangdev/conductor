package com.conductor.service;

import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.publish.PublishingWorkflow;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartStatus;
import com.conductor.workflow.lifecycle.StatechartTransition;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * The approval invariant (COND-23, AC-P0-1.5): <b>nothing publishes under an approval that no longer
 * describes what would go out</b>. Editing the publish bundle of an Approved-or-later Post — its caption,
 * its fire time, the accounts it goes to, or a per-target caption override — sends the Post back to the
 * review status, voids the approval standing on it, and gives back any native-lane hand-off first.
 *
 * <h2>Why this exists on top of the bundle hash</h2>
 * {@link PublishBundleHasher} plus the gate in {@code WorkItemWorkflowService} already make a stale approval
 * <em>stop satisfying</em> the review gate the moment the bundle changes (T4.1). That is enough to block the
 * next transition, but it is not enough on its own for two reasons, and both are what this class adds:
 *
 * <ul>
 *   <li><b>The Post's status is left lying.</b> A Post edited while {@code APPROVED}/{@code SCHEDULED} still
 *       <em>says</em> Approved or Scheduled to every human and every scheduler looking at it, while the
 *       approval behind it is void. The revert makes the status tell the truth.</li>
 *   <li><b>A Scheduled Post is already live on a platform.</b> Once a native-lane target is
 *       {@code HANDED_OFF}, Facebook or YouTube owns the timer; no amount of Conductor-side invalidation
 *       stops it firing. The hand-off has to be revoked, and it has to be revoked <em>before</em> the revert
 *       commits.</li>
 * </ul>
 *
 * <h2>The ordering, and why it is not negotiable</h2>
 * One transaction, in this order: <b>revoke, revert, then apply the edit</b>. {@link NativeHandoffService}
 * runs the connector call inside the caller's transaction and throws on failure, so a revocation that fails
 * takes the whole thing down — the edit is refused, and the Post stays Scheduled, visibly consistent with
 * the post that is still scheduled on the platform. The alternative ordering (edit, then revoke) can leave a
 * live platform post behind a Post that no longer looks scheduled, which is the exact failure this guard
 * exists to prevent.
 *
 * <h2>What counts as "Approved or later", and where the review status comes from</h2>
 * Both are read off the Work Item's own version-pinned {@link Statechart}, never a hardcoded status list:
 * {@link AssetUploadPolicy#isApprovedOrLater} derives the locked set from the workflow's {@code requiresReview}
 * edge, and the status a revert lands on is that edge's {@code from}. A workflow that names its review status
 * something else entirely gets identical behavior; a workflow with no review gate is never touched.
 *
 * <h2>Scope: items that actually carry a publish bundle</h2>
 * The guard applies only where {@link PublishBundleHasher#appliesTo} is true — the item has at least one
 * publish target, which is the same "does this carry a publish bundle?" test the review gate uses. Every
 * ENGINEERING item has none and is completely unaffected, even in a post-gate status such as {@code DONE}.
 *
 * <h2>Published is immutable; Failed is not (COND-23 P0-6)</h2>
 * The locked set {@link AssetUploadPolicy#isApprovedOrLater} derives includes the workflow's <b>terminal</b>
 * status — {@code PUBLISHED} for MARKETING — and reverting <em>that</em> would be a lie. A published Post has
 * an approver, a fire time and a per-target outcome, and none of it can be un-happened: the posts are on the
 * platforms. Sending such a Post back to In Review would rewrite the audit trail of something that already
 * went out and make the pipeline look as if it could take it back. So a bundle edit on a Post in a terminal
 * status is <b>refused</b> outright, not reverted; the way to change a published post's copy is a new Post.
 *
 * <p>{@code FAILED} is the opposite case and is deliberately left revertible. It is not terminal, it is the
 * "needs attention" landing the roll-up puts a Post in, and fixing the caption or dropping the account that
 * refused it is exactly what a human does before hitting retry. Every other Approved-or-later status behaves
 * as before.
 *
 * <h2>Media is deliberately not here</h2>
 * A media change on an Approved-or-later Post is <em>refused</em> by {@link AssetService}, not silently
 * reverted (AC-P0-2.3): swapping the creative is a big enough change that it asks for an explicit revert
 * first. This guard covers every other bundle field.
 *
 * <p>Callers own the {@code WORK_ITEM_STATUS_CHANGED} event for a revert — the returned {@link Revert} carries
 * the two statuses to announce. Publishing it here would make this component depend on {@code WorkItemService},
 * which depends on it.
 */
@Component
public class PublishBundleGuard {

    private static final Logger log = LoggerFactory.getLogger(PublishBundleGuard.class);

    /** A revert that happened, for the caller to announce as a status change. */
    public record Revert(String fromStatus, String toStatus) {
    }

    private final WorkflowDefinitionResolver resolver;
    private final NativeHandoffService nativeHandoffService;
    private final PublishBundleHasher publishBundleHasher;
    private final WorkItemRepository workItemRepository;

    public PublishBundleGuard(WorkflowDefinitionResolver resolver,
                              NativeHandoffService nativeHandoffService,
                              PublishBundleHasher publishBundleHasher,
                              WorkItemRepository workItemRepository) {
        this.resolver = resolver;
        this.nativeHandoffService = nativeHandoffService;
        this.publishBundleHasher = publishBundleHasher;
        this.workItemRepository = workItemRepository;
    }

    /**
     * The caption/fire-time shaped entry point, for the Work Item patch path. Applies PATCH semantics
     * ({@code null} means "field absent — unchanged"; a blank timezone clears the stored zone) and reverts
     * only when one of those fields would actually take a different value, so a client that re-sends the
     * whole object unchanged never knocks a Post out of Approved.
     *
     * <p>Call it <b>before</b> applying the edit, and pass the incoming values, not the applied ones.
     *
     * @param scheduleTimezone the incoming zone; may be the raw or the already-validated value — blank and
     *                         {@code null} are normalized the same way the patch path normalizes them
     * @return the revert that happened, or empty when nothing changed status
     */
    @Transactional
    public Optional<Revert> revertForCaptionOrScheduleEdit(String projectId, WorkItem post, String caption,
                                                           OffsetDateTime fireTime, String scheduleTimezone) {
        if (!changesCaptionOrSchedule(post, caption, fireTime, scheduleTimezone)) {
            return Optional.empty();
        }
        return revertForBundleEdit(projectId, post);
    }

    /**
     * The general entry point, for every other bundle mutation — adding or removing a publish target, and
     * editing a per-target caption override. Call it <b>before</b> the mutation, inside the same transaction,
     * so a failed revocation rolls the mutation back with it.
     *
     * <p>A no-op for items with no publish bundle, for items short of the review gate, and for workflows with
     * no review gate at all, so callers can invoke it unconditionally.
     *
     * @return the revert that happened, or empty when the Post was left where it was
     * @throws com.conductor.exception.BusinessException when the Post is in a terminal status — its publish
     *                                                   record is immutable and the edit is refused, not
     *                                                   reverted — or when a native-lane hand-off could not be
     *                                                   revoked, in which case the caller's transaction must
     *                                                   roll back rather than commit an edit behind a live
     *                                                   scheduled platform post
     */
    @Transactional
    public Optional<Revert> revertForBundleEdit(String projectId, WorkItem post) {
        if (post == null || post.getId() == null) {
            return Optional.empty();
        }
        if (!publishBundleHasher.appliesTo(post)) {
            return Optional.empty();
        }

        Statechart statechart = resolveStatechart(projectId, post);
        String fromStatus = post.getCurrentStatus();
        if (!AssetUploadPolicy.isApprovedOrLater(statechart, fromStatus)) {
            return Optional.empty();
        }
        // A published Post cannot be un-published, so reverting it would misdescribe what happened. Refuse
        // the edit instead and leave the audit trail — approver, fire time, per-target outcome — intact.
        if (statechart.isTerminal(fromStatus)) {
            String noun = statechart.noun();
            throw new BusinessException("This " + noun + " is " + statusLabel(statechart, fromStatus)
                    + " and its publish record is immutable: the approval it went out under, the time it"
                    + " fired and each target's outcome are the record of posts that are already live."
                    + " Create a new " + noun + " instead of editing this one.");
        }
        String reviewStatus = AssetUploadPolicy.reviewGate(statechart)
                .map(StatechartTransition::from)
                .orElse(null);
        if (reviewStatus == null) {
            // Unreachable: isApprovedOrLater is false for a workflow with no review gate. Belt and braces —
            // there is no status to revert to, so leave the item alone rather than invent one.
            return Optional.empty();
        }

        // Revoke FIRST, inside the caller's transaction. A failure throws here and nothing below runs.
        if (PublishingWorkflow.isScheduledStatus(statechart, fromStatus)) {
            nativeHandoffService.unschedule(post);
        }

        post.setCurrentStatus(reviewStatus);
        // The edit closes the review round as well as out-hashing the approval. The hash alone would leave a
        // hole: an edit that removes the last publish target also removes the item's bundle, and a hash-bound
        // approval whose bundle no longer exists would gate as if it had never been bound. Closing the round
        // voids the standing approval unconditionally.
        post.setCurrentReviewRound(post.getCurrentReviewRound() + 1);
        workItemRepository.save(post);

        log.info("Publish bundle edit on {} {}: reverted {} -> {} and opened review round {}",
                statechart.noun(), post.getId(), fromStatus, reviewStatus, post.getCurrentReviewRound());

        return Optional.of(new Revert(fromStatus, reviewStatus));
    }

    /**
     * Whether a patch carrying these values would actually change one of the bundle's caption/schedule
     * fields. The fire time is compared as an instant, not as an offset-bearing value, so re-sending the same
     * moment in a different offset is not a change — the same reduction {@link PublishBundleHasher} makes.
     */
    static boolean changesCaptionOrSchedule(WorkItem post, String caption, OffsetDateTime fireTime,
                                            String scheduleTimezone) {
        if (post == null) {
            return false;
        }
        if (caption != null && !Objects.equals(caption, post.getDescription())) {
            return true;
        }
        if (fireTime != null && !sameInstant(fireTime, post.getScheduledFor())) {
            return true;
        }
        return scheduleTimezone != null
                && !Objects.equals(blankToNull(scheduleTimezone), blankToNull(post.getScheduleTimezone()));
    }

    private static boolean sameInstant(OffsetDateTime left, OffsetDateTime right) {
        return left != null && right != null && left.toInstant().equals(right.toInstant());
    }

    private static String statusLabel(Statechart statechart, String statusId) {
        return statechart.status(statusId).map(StatechartStatus::displayLabel).orElse(statusId);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Statechart resolveStatechart(String projectId, WorkItem post) {
        String slug = post.getWorkflow() != null ? post.getWorkflow() : WorkItemWorkflowService.DEFAULT_WORKFLOW;
        return resolver.resolveRequired(projectId, slug, post.getWorkflowVersion());
    }

    /**
     * The item's statechart, or null when it cannot be resolved.
     *
     * <p>The freeze guards run on every patch, including for projects whose Workflow was never seeded, so
     * they must not be the thing that turns a missing Workflow into a failed edit. A workflow that cannot
     * be resolved declares no review gate, and a workflow with no gate freezes nothing.
     */
    private Statechart statechartOrNull(String projectId, WorkItem post) {
        try {
            return resolveStatechart(projectId, post);
        } catch (RuntimeException e) {
            log.debug("No statechart for work item {}; nothing to freeze: {}", post.getId(), e.getMessage());
            return null;
        }
    }
    /**
     * Refuses an edit to a Work Item that is under review.
     *
     * <p>The content freeze the review gate implies but did not enforce. Until now the review status was
     * editable: an author could rewrite the caption, move the schedule or change the tags while a reviewer
     * was reading, and the approval that reviewer gave — for what they had read — attached to whatever the
     * item had become. The bundle hash caught that <em>after</em> approval, but nothing stopped the edit
     * landing mid-read, and nothing at all covered an item whose review predated its publish targets.
     *
     * <p>Only fires for a genuine change. Re-sending the same caption is not an edit, and refusing it
     * would break every client that PATCHes a whole object back.
     *
     * <p>Applies to every Workflow with a review gate, not just publishing ones: an Issue under review is
     * being read by somebody too.
     *
     * @throws BusinessException naming the move that reopens it, since the author cannot revert it
     */
    public void refuseEditWhileFrozen(String projectId, WorkItem workItem, String description,
                                           OffsetDateTime scheduledFor, String scheduleTimezone,
                                           java.util.Collection<String> tags) {
        Statechart statechart = statechartOrNull(projectId, workItem);
        if (statechart == null
                || !AssetUploadPolicy.isFrozen(statechart, workItem.getCurrentStatus())) {
            return;
        }
        // Past the gate the existing revert path owns this: an edit there takes the approval back rather
        // than being refused, which is the behaviour COND-23 specified and clients rely on.
        if (!changesAnything(workItem, description, scheduledFor, scheduleTimezone, tags)) {
            return;
        }
        String noun = statechart.noun();
        String statusLabel = statechart.status(workItem.getCurrentStatus())
                .map(StatechartStatus::displayLabel)
                .orElse(workItem.getCurrentStatus());
        throw new BusinessException("This " + noun + " is " + statusLabel + ", so its content is locked."
                + " It has to be " + AssetUploadPolicy.reopenHint(statechart, workItem.getCurrentStatus()) + " before it can change.");
    }

    /**
     * Refuses a change to where a Post publishes while it is under review.
     *
     * <p>Separate from {@link #refuseEditWhileUnderReview} only because the caller has already decided the
     * selection differs — {@code PublishTargetService} diffs before it calls, and an unchanged re-save
     * must stay a no-op rather than a refusal.
     */
    public void refuseTargetEditWhileFrozen(String projectId, WorkItem workItem) {
        Statechart statechart = statechartOrNull(projectId, workItem);
        if (statechart == null
                || !AssetUploadPolicy.isFrozen(statechart, workItem.getCurrentStatus())) {
            return;
        }
        String statusLabel = statechart.status(workItem.getCurrentStatus())
                .map(StatechartStatus::displayLabel)
                .orElse(workItem.getCurrentStatus());
        throw new BusinessException("This " + statechart.noun() + " is " + statusLabel + ", so where it"
                + " publishes is locked. It has to be " + AssetUploadPolicy.reopenHint(statechart, workItem.getCurrentStatus())
                + " before its accounts can change.");
    }

    /** Whether the patch actually differs from what is stored — re-sending the same values is not an edit. */
    private static boolean changesAnything(WorkItem workItem, String description,
                                           OffsetDateTime scheduledFor, String scheduleTimezone,
                                           java.util.Collection<String> tags) {
        if (description != null && !description.equals(workItem.getDescription())) {
            return true;
        }
        if (scheduledFor != null && !scheduledFor.isEqual(
                workItem.getScheduledFor() == null ? scheduledFor.minusYears(1) : workItem.getScheduledFor())) {
            return true;
        }
        if (scheduleTimezone != null && !scheduleTimezone.equals(workItem.getScheduleTimezone())) {
            return true;
        }
        return tags != null && !new java.util.LinkedHashSet<>(tags).equals(workItem.getTags());
    }

}
