package com.conductor.creative;

import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.ProjectSecurityService;
import com.conductor.workflow.RuntimeTargetResolver;
import com.conductor.workflow.lifecycle.StatechartStatus;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The read side of slide-text edits: what reached a Post ({@code content-changes}), which Posts a Creative edit
 * would move and where ({@code affected-posts}), and where the re-render would run ({@code render-availability}).
 * Any project member may read; each read is a fixed number of queries however many Posts or changes there are.
 */
@Service
public class CreativeEditReadService {

    /** The most changes one Post read returns. */
    static final int MAX_CHANGES = 50;

    /** A Post's change plus the names the API shows next to it. */
    public record ChangeView(PostContentChange change, int revisionNo, String creativeDisplayId, String actorName) {
    }

    /** One Post a Creative edit would move. */
    public record AffectedPost(String workItemId, String displayId, String title, String status, String statusLabel,
                               String outcome) {
    }

    /** The Posts an edit would move, and why this caller may not save it (null when they may). */
    public record AffectedPosts(List<AffectedPost> posts, String blockedReason) {
    }

    /** Where a re-render would run. {@code daemonOnline} is null: Conductor does not track daemon liveness. */
    public record RenderAvailability(String runtime, String targetName, Boolean daemonOnline) {
    }

    private final ProjectSecurityService projectSecurityService;
    private final PostContentChangeRepository changeRepository;
    private final CreativeRevisionRepository revisionRepository;
    private final CreativeRepository creativeRepository;
    private final WorkItemRepository workItemRepository;
    private final UserRepository userRepository;
    private final RuntimeTargetResolver runtimeTargetResolver;
    private final CreativeEditImpacts impacts;

    public CreativeEditReadService(ProjectSecurityService projectSecurityService,
                                   PostContentChangeRepository changeRepository,
                                   CreativeRevisionRepository revisionRepository,
                                   CreativeRepository creativeRepository,
                                   WorkItemRepository workItemRepository,
                                   UserRepository userRepository,
                                   RuntimeTargetResolver runtimeTargetResolver,
                                   CreativeEditImpacts impacts) {
        this.impacts = impacts;
        this.projectSecurityService = projectSecurityService;
        this.changeRepository = changeRepository;
        this.revisionRepository = revisionRepository;
        this.creativeRepository = creativeRepository;
        this.workItemRepository = workItemRepository;
        this.userRepository = userRepository;
        this.runtimeTargetResolver = runtimeTargetResolver;
    }

    @Transactional(readOnly = true)
    public List<ChangeView> contentChanges(String projectId, String workItemId, User caller) {
        requireMember(projectId, caller);
        workItemRepository.findById(workItemId)
                .filter(w -> w.getProject() != null && projectId.equals(w.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
        List<PostContentChange> changes = changeRepository.findAllByWorkItemIdOrderByCreatedAtDescIdDesc(
                workItemId, PageRequest.of(0, MAX_CHANGES));
        if (changes.isEmpty()) {
            return List.of();
        }
        Map<String, CreativeRevision> revisions = new HashMap<>();
        revisionRepository.findAllById(changes.stream().map(PostContentChange::getRevisionId).distinct().toList())
                .forEach(r -> revisions.put(r.getId(), r));
        Map<String, Creative> creatives = new HashMap<>();
        creativeRepository.findAllById(changes.stream().map(PostContentChange::getCreativeId).distinct().toList())
                .forEach(c -> creatives.put(c.getId(), c));
        Map<String, String> names = new HashMap<>();
        userRepository.findAllById(changes.stream().map(PostContentChange::getActorUserId)
                        .filter(java.util.Objects::nonNull).distinct().toList())
                .forEach(u -> names.put(u.getId(), u.getName()));
        List<ChangeView> out = new ArrayList<>();
        for (PostContentChange change : changes) {
            CreativeRevision revision = revisions.get(change.getRevisionId());
            Creative creative = creatives.get(change.getCreativeId());
            out.add(new ChangeView(change, revision != null ? revision.getRevisionNo() : 0,
                    creative != null ? creative.displayId() : null,
                    change.getActorUserId() != null ? names.get(change.getActorUserId()) : null));
        }
        return out;
    }

    /**
     * Which Posts an edit would move and how. {@code postId} is the Post the caller is editing from (a reviewer's
     * fix), if any. The outcomes and {@code blockedReason} come from {@link CreativeEditImpacts}, the same
     * computation {@code edit} and the propagator use.
     */
    @Transactional(readOnly = true)
    public AffectedPosts affectedPosts(String projectId, String creativeId, String postId, User caller) {
        requireMember(projectId, caller);
        creativeRepository.findByIdAndProjectId(creativeId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Creative not found"));
        List<CreativeEditImpacts.PostImpact> impacted = impacts.forCreative(projectId, creativeId);
        boolean reviewerOfPost = impacts.isReviewerOf(postId, caller);
        boolean editor = projectSecurityService.isAdminOrCreator(projectId, caller.getId());
        List<AffectedPost> out = new ArrayList<>();
        for (CreativeEditImpacts.PostImpact impact : impacted) {
            WorkItem post = impact.post();
            String label = impact.chart().status(post.getCurrentStatus()).map(StatechartStatus::displayLabel)
                    .orElse(post.getCurrentStatus());
            out.add(new AffectedPost(post.getId(), impact.displayId(), post.getTitle(), post.getCurrentStatus(), label,
                    impacts.outcome(impact, postId, reviewerOfPost)));
        }
        return new AffectedPosts(out, impacts.blockedReason(impacted, postId, editor, reviewerOfPost));
    }

    @Transactional(readOnly = true)
    public RenderAvailability renderAvailability(String projectId, String creativeId, User caller) {
        requireMember(projectId, caller);
        creativeRepository.findByIdAndProjectId(creativeId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Creative not found"));
        RuntimeTargetResolver.RenderTargetResolution resolution = runtimeTargetResolver.resolveRenderTarget(projectId);
        return resolution.available()
                ? new RenderAvailability(CreativeRenderDispatchService.RUNTIME_TARGET, resolution.targetName(), null)
                : new RenderAvailability(CreativeRenderDispatchService.RUNTIME_SELF_HOSTED, null, null);
    }

    private void requireMember(String projectId, User caller) {
        if (caller == null || !projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Project not found");
        }
    }
}
