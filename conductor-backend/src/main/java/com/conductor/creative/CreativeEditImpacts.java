package com.conductor.creative;

import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkItemReviewerRepository;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one place a slide-text edit's effect on each Post is worked out, shared by what announces it
 * ({@code affected-posts}), what refuses it ({@link CreativeRevisionService#edit}) and what does it
 * ({@link CreativeEditPropagator}), so the three cannot drift apart.
 *
 * <p>Every method reads lazily loaded state: call within a transaction.
 */
@Component
class CreativeEditImpacts {

    /** One Post using the Creative and what an edit would do to it. */
    record PostImpact(WorkItem post, String displayId, Statechart chart, CreativeEditImpact impact) {
    }

    private final WorkItemRepository workItemRepository;
    private final PostPublishTargetRepository targetRepository;
    private final WorkItemReviewerRepository reviewerRepository;
    private final WorkflowDefinitionResolver resolver;

    CreativeEditImpacts(WorkItemRepository workItemRepository, PostPublishTargetRepository targetRepository,
                        WorkItemReviewerRepository reviewerRepository, WorkflowDefinitionResolver resolver) {
        this.workItemRepository = workItemRepository;
        this.targetRepository = targetRepository;
        this.reviewerRepository = reviewerRepository;
        this.resolver = resolver;
    }

    /** Every Post of the project holding a frame of this Creative, by sequence number: two queries in all. */
    List<PostImpact> forCreative(String projectId, String creativeId) {
        List<WorkItem> posts = workItemRepository.findAllUsingCreative(projectId, creativeId);
        if (posts.isEmpty()) {
            return List.of();
        }
        Set<String> published = new HashSet<>(targetRepository.findWorkItemIdsWithPublishedTarget(
                posts.stream().map(WorkItem::getId).toList()));
        Map<String, Statechart> charts = new HashMap<>();
        return posts.stream().map(post -> classify(projectId, post, published.contains(post.getId()), charts)).toList();
    }

    /** One Post, for the propagator's per-Post transaction. */
    PostImpact forPost(String projectId, WorkItem post) {
        boolean published = !targetRepository.findWorkItemIdsWithPublishedTarget(List.of(post.getId())).isEmpty();
        return classify(projectId, post, published, new HashMap<>());
    }

    private PostImpact classify(String projectId, WorkItem post, boolean published, Map<String, Statechart> charts) {
        String slug = post.getWorkflow() != null ? post.getWorkflow() : "ENGINEERING";
        Statechart chart = charts.computeIfAbsent(slug + "@" + post.getWorkflowVersion(),
                k -> resolver.resolveRequired(projectId, slug, post.getWorkflowVersion()));
        String key = post.getProject() != null ? post.getProject().getKey() : null;
        String displayId = key != null && post.getSequenceNumber() != null ? key + "-" + post.getSequenceNumber()
                : post.getId();
        return new PostImpact(post, displayId, chart, CreativeEditImpact.of(chart, post.getCurrentStatus(), published));
    }

    /**
     * Whether an edit by {@code caller} made from {@code postId} is a reviewer's fix: the caller is one of that
     * Post's reviewers. It is the revision's source ({@code REVIEWER}); with the Post in review it is what keeps
     * the Post in its round instead of opening a new one.
     */
    boolean isReviewerOf(String postId, User caller) {
        return postId != null && caller != null && reviewerRepository.findByWorkItemIdAndUserId(postId, caller.getId()).isPresent();
    }

    /** The outcome the API reports for one Post: {@code reviewer-fix} for the Post a reviewer fixes from. */
    String outcome(PostImpact impact, String postId, boolean reviewerFix) {
        return reviewerFix && impact.post().getId().equals(postId) && impact.impact() == CreativeEditImpact.NEW_ROUND
                ? CreativeEditImpact.REVIEWER_FIX : impact.impact().wire();
    }

    /**
     * Why this caller may not save, or null. A reviewer of {@code postId} who is not a project editor may only
     * fix text when no OTHER Post using the Creative would go back to review: sending an approved or scheduled
     * Post back is an editor's call, not a side effect of a review comment.
     */
    String blockedReason(List<PostImpact> impacts, String postId, boolean callerIsEditor, boolean callerIsReviewer) {
        if (postId == null || callerIsEditor || !callerIsReviewer) {
            return null;
        }
        List<PostImpact> others = impacts.stream()
                .filter(i -> i.impact() == CreativeEditImpact.BACK_TO_REVIEW && !i.post().getId().equals(postId))
                .toList();
        if (others.isEmpty()) {
            return null;
        }
        PostImpact first = others.get(0);
        String who = others.size() == 1
                ? "Post " + first.displayId() + " uses this Creative and is approved or scheduled."
                : "Post " + first.displayId() + " and " + (others.size() - 1) + " more using this Creative are approved or scheduled.";
        return who + " Ask an editor to make this change.";
    }
}
