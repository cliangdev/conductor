package com.conductor.creative;

import com.conductor.entity.Asset;
import com.conductor.entity.WorkItem;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.PublishBundleGuard;
import com.conductor.service.WorkItemService;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Carries a slide-text edit to the Posts that use the Creative. Listens for {@link CreativeRevisionRendered}
 * (published once the re-render has committed) and, for every Post holding a frame of this Creative, in a
 * transaction of its own:
 *
 * <ol>
 *   <li>re-points the Post at the new render's slides ({@link CreativeAttachService#reattach}: destinations,
 *       order and per-target options stay exactly as they were) through the same {@code replaceSelection} any
 *       edit uses, so the bundle hash changes and every approval cast against the old slides stops counting;</li>
 *   <li>reopens the review as the Post's status calls for ({@link CreativeEditImpact}): Approved or Scheduled
 *       goes through {@link PublishBundleGuard#revertForBundleEdit} (unscheduled, back to the review status, a
 *       new round, announced as a status change like any other revert); In Review gets a new round; a draft is
 *       just updated; a published Post is not touched and gets no record;</li>
 *   <li>writes a {@link PostContentChange}: revision, diff, who, and the status and round before and after.</li>
 * </ol>
 *
 * <p><b>Reviewer fix.</b> When the revision was made by a reviewer from that very Post's review
 * ({@code source = REVIEWER, postId = the Post, reviewRound = the Post's current round}) the Post stays in the
 * same round: no bump, status unchanged, and the change row has equal rounds before and after. Approvals already
 * recorded in the round are bound to the old bundle hash and so go stale by the existing hash rule; the
 * reviewer's next approval binds the new hash. Every other Post using the Creative gets the normal treatment.
 *
 * <p><b>The freeze exception lives here only.</b> A Post under review or later refuses media and destination
 * edits; this path alone passes {@link com.conductor.service.FreezeBypass#CREATIVE_EDIT_PROPAGATION} (a method
 * argument, never request data), and the public attach endpoint still refuses a frozen Post.
 *
 * <p>Idempotent (a Post that already holds the new render's frames, or already has a change row for the revision,
 * is skipped), and one Post failing is logged and rolls back only itself.
 */
@Component
public class CreativeEditPropagator {

    private static final Logger log = LoggerFactory.getLogger(CreativeEditPropagator.class);

    /** How a propagation went, by Post id. */
    record Result(List<String> moved, List<String> skipped, List<String> failed) {
    }

    private final CreativeRevisionRepository revisionRepository;
    private final CreativeRepository creativeRepository;
    private final CreativeRenderRepository renderRepository;
    private final CreativeRenderFrameRepository frameRepository;
    private final AssetRepository assetRepository;
    private final WorkItemRepository workItemRepository;
    private final PostContentChangeRepository changeRepository;
    private final WorkflowDefinitionResolver resolver;
    private final PublishBundleGuard publishBundleGuard;
    private final CreativeAttachService attachService;
    private final WorkItemService workItemService;
    private final TransactionTemplate newTx;
    private final TransactionTemplate readTx;

    public CreativeEditPropagator(CreativeRevisionRepository revisionRepository,
                                  CreativeRepository creativeRepository,
                                  CreativeRenderRepository renderRepository,
                                  CreativeRenderFrameRepository frameRepository,
                                  AssetRepository assetRepository,
                                  WorkItemRepository workItemRepository,
                                  PostContentChangeRepository changeRepository,
                                  WorkflowDefinitionResolver resolver,
                                  PublishBundleGuard publishBundleGuard,
                                  CreativeAttachService attachService,
                                  WorkItemService workItemService,
                                  PlatformTransactionManager transactionManager) {
        this.revisionRepository = revisionRepository;
        this.creativeRepository = creativeRepository;
        this.renderRepository = renderRepository;
        this.frameRepository = frameRepository;
        this.assetRepository = assetRepository;
        this.workItemRepository = workItemRepository;
        this.changeRepository = changeRepository;
        this.resolver = resolver;
        this.publishBundleGuard = publishBundleGuard;
        this.attachService = attachService;
        this.workItemService = workItemService;
        this.newTx = new TransactionTemplate(transactionManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx.setReadOnly(true);
    }

    /** Runs on the thread that committed the re-render, with no transaction bound; see {@link CreativeRevisionRendered}. */
    @EventListener
    public void onRendered(CreativeRevisionRendered event) {
        try {
            propagate(event);
        } catch (RuntimeException e) {
            // The edit is committed: a propagation that cannot even start must not surface from the commit.
            log.warn("Could not carry revision {} of Creative {} to its Posts", event.revisionId(), event.creativeId(), e);
        }
    }

    Result propagate(CreativeRevisionRendered event) {
        List<String> moved = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        CreativeRevision revision = revisionRepository.findById(event.revisionId()).orElse(null);
        Creative creative = creativeRepository.findByIdAndProjectId(event.creativeId(), event.projectId()).orElse(null);
        CreativeRender render = renderRepository.findByIdAndCreativeId(event.renderId(), event.creativeId())
                .filter(CreativeRender::isSucceeded).orElse(null);
        if (revision == null || creative == null || render == null
                || !CreativeRevision.STATUS_APPLIED.equals(revision.getStatus())) {
            log.warn("Not carrying revision {}: the revision, Creative or succeeded render {} is missing",
                    event.revisionId(), event.renderId());
            return new Result(moved, skipped, failed);
        }

        // Every Post holding a frame of ANY render of this Creative: three batched reads however many Posts.
        List<CreativeRenderFrame> frames = frameRepository.findAllByCreativeId(creative.getId());
        List<String> frameIds = frames.stream().map(CreativeRenderFrame::getId).toList();
        List<String> postIds = readTx.execute(status -> {
            Map<String, WorkItem> posts = new LinkedHashMap<>();
            for (int i = 0; i < frameIds.size(); i += 500) {
                for (Asset asset : assetRepository.findAllByCreativeFrameIdIn(
                        frameIds.subList(i, Math.min(frameIds.size(), i + 500)))) {
                    WorkItem post = asset.getWorkItem();
                    if (post != null && post.getProject() != null && event.projectId().equals(post.getProject().getId())) {
                        posts.putIfAbsent(post.getId(), post);
                    }
                }
            }
            return posts.values().stream()
                    .sorted(Comparator.comparing(WorkItem::getSequenceNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(WorkItem::getId).toList();
        });
        Set<String> alreadyDone = new HashSet<>();
        if (!postIds.isEmpty()) {
            changeRepository.findAllByRevisionIdAndWorkItemIdIn(revision.getId(), postIds)
                    .forEach(c -> alreadyDone.add(c.getWorkItemId()));
        }

        for (String postId : postIds) {
            if (alreadyDone.contains(postId)) {
                skipped.add(postId);
                continue;
            }
            try {
                Boolean didMove = newTx.execute(status -> propagateOne(event.projectId(), revision, creative, render,
                        frames, postId));
                (Boolean.TRUE.equals(didMove) ? moved : skipped).add(postId);
            } catch (RuntimeException e) {
                log.warn("Could not carry revision {} of Creative {} to Post {}: {}", revision.getId(), creative.getId(),
                        postId, e.getMessage(), e);
                failed.add(postId);
            }
        }
        if (!moved.isEmpty() || !failed.isEmpty()) {
            log.info("Slide-text revision {} of Creative {}: {} Post(s) moved, {} left alone, {} failed",
                    revision.getId(), creative.getId(), moved.size(), skipped.size(), failed.size());
        }
        return new Result(moved, skipped, failed);
    }

    /** One Post, in its own transaction. Returns whether it was moved. */
    private boolean propagateOne(String projectId, CreativeRevision revision, Creative creative, CreativeRender render,
                                 List<CreativeRenderFrame> frames, String postId) {
        WorkItem post = workItemRepository.findById(postId)
                .filter(w -> w.getProject() != null && projectId.equals(w.getProject().getId())).orElse(null);
        if (post == null || changeRepository.existsByWorkItemIdAndRevisionId(postId, revision.getId())) {
            return false;
        }
        // Already on the new slides (attached by hand, or an earlier run got this far): nothing to do.
        Set<String> newFrameIds = new HashSet<>();
        frames.stream().filter(f -> render.getId().equals(f.getRenderId())).forEach(f -> newFrameIds.add(f.getId()));
        List<Asset> assets = assetRepository.findAllByWorkItemId(postId);
        if (assets.stream().anyMatch(a -> a.getCreativeFrameId() != null && newFrameIds.contains(a.getCreativeFrameId()))) {
            return false;
        }

        String slug = post.getWorkflow() != null ? post.getWorkflow() : "ENGINEERING";
        Statechart chart = resolver.resolveRequired(projectId, slug, post.getWorkflowVersion());
        String fromStatus = post.getCurrentStatus();
        CreativeEditImpact impact = CreativeEditImpact.of(chart, fromStatus);
        if (impact == CreativeEditImpact.UNCHANGED) {
            return false;
        }
        int roundBefore = post.getCurrentReviewRound();
        boolean reviewerFix = impact == CreativeEditImpact.NEW_ROUND
                && CreativeRevision.SOURCE_REVIEWER.equals(revision.getSource())
                && postId.equals(revision.getPostId())
                && revision.getReviewRound() != null && revision.getReviewRound() == roundBefore;

        // Reopen the review FIRST (it may have to revoke a platform hand-off, and a failure there must stop
        // everything), then move the media.
        Optional<PublishBundleGuard.Revert> revert = Optional.empty();
        if (impact == CreativeEditImpact.BACK_TO_REVIEW) {
            revert = publishBundleGuard.revertForBundleEdit(projectId, post);
        } else if (impact == CreativeEditImpact.NEW_ROUND && !reviewerFix) {
            post.setCurrentReviewRound(roundBefore + 1);
            workItemRepository.save(post);
        }

        CreativeAttachService.ReattachResult moved = attachService.reattach(projectId, postId, creative, render, frames);
        if (moved.assetsReplaced() == 0) {
            // Unreachable (the Post holds a frame of this Creative that is not on the new render): refuse rather
            // than commit a round bump for a move that did not happen.
            throw new IllegalStateException("Post " + postId + " has no Creative assets to move");
        }

        PostContentChange change = new PostContentChange();
        change.setProjectId(projectId);
        change.setWorkItemId(postId);
        change.setCreativeId(creative.getId());
        change.setRevisionId(revision.getId());
        change.setSource(revision.getSource());
        change.setActorUserId(revision.getAuthorUserId());
        change.setFromStatus(fromStatus);
        change.setToStatus(post.getCurrentStatus());
        change.setReviewRoundBefore(roundBefore);
        change.setReviewRoundAfter(post.getCurrentReviewRound());
        change.setTextDiff(revision.getTextDiff());
        changeRepository.save(change);

        // Announce a revert like every other one (WorkItemService does the same after a caption edit).
        revert.ifPresent(r -> workItemService.publishStatusChanged(projectId, post, r.fromStatus(), r.toStatus(), null));
        return true;
    }
}
