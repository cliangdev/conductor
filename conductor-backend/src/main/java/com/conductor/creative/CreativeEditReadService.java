package com.conductor.creative;

import com.conductor.entity.Asset;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.ProjectSecurityService;
import com.conductor.workflow.RuntimeTargetResolver;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.StatechartStatus;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The read side of slide-text edits: what reached a Post ({@code content-changes}), which Posts a Creative edit
 * would move and where ({@code affected-posts}), and where the re-render would run ({@code render-availability}).
 * Any project member may read; each read is a fixed number of queries however many Posts or changes there are.
 */
@Service
public class CreativeEditReadService {

    /** The most changes one Post read returns. */
    static final int MAX_CHANGES = 50;
    private static final int IN_CHUNK = 500;

    /** A Post's change plus the names the API shows next to it. */
    public record ChangeView(PostContentChange change, int revisionNo, String creativeDisplayId, String actorName) {
    }

    /** One Post a Creative edit would move. */
    public record AffectedPost(String workItemId, String displayId, String title, String status, String statusLabel,
                               String outcome) {
    }

    /** Where a re-render would run. {@code daemonOnline} is null: Conductor does not track daemon liveness. */
    public record RenderAvailability(String runtime, String targetName, Boolean daemonOnline) {
    }

    private final ProjectSecurityService projectSecurityService;
    private final PostContentChangeRepository changeRepository;
    private final CreativeRevisionRepository revisionRepository;
    private final CreativeRepository creativeRepository;
    private final CreativeRenderFrameRepository frameRepository;
    private final AssetRepository assetRepository;
    private final WorkItemRepository workItemRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final WorkflowDefinitionResolver resolver;
    private final RuntimeTargetResolver runtimeTargetResolver;

    public CreativeEditReadService(ProjectSecurityService projectSecurityService,
                                   PostContentChangeRepository changeRepository,
                                   CreativeRevisionRepository revisionRepository,
                                   CreativeRepository creativeRepository,
                                   CreativeRenderFrameRepository frameRepository,
                                   AssetRepository assetRepository,
                                   WorkItemRepository workItemRepository,
                                   ProjectRepository projectRepository,
                                   UserRepository userRepository,
                                   WorkflowDefinitionResolver resolver,
                                   RuntimeTargetResolver runtimeTargetResolver) {
        this.projectSecurityService = projectSecurityService;
        this.changeRepository = changeRepository;
        this.revisionRepository = revisionRepository;
        this.creativeRepository = creativeRepository;
        this.frameRepository = frameRepository;
        this.assetRepository = assetRepository;
        this.workItemRepository = workItemRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.resolver = resolver;
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

    @Transactional(readOnly = true)
    public List<AffectedPost> affectedPosts(String projectId, String creativeId, String postId, User caller) {
        requireMember(projectId, caller);
        creativeRepository.findByIdAndProjectId(creativeId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Creative not found"));
        List<String> frameIds = frameRepository.findAllByCreativeId(creativeId).stream()
                .map(CreativeRenderFrame::getId).toList();
        Map<String, WorkItem> posts = new LinkedHashMap<>();
        for (int i = 0; i < frameIds.size(); i += IN_CHUNK) {
            for (Asset asset : assetRepository.findAllByCreativeFrameIdIn(
                    frameIds.subList(i, Math.min(frameIds.size(), i + IN_CHUNK)))) {
                WorkItem post = asset.getWorkItem();
                if (post != null && post.getProject() != null && projectId.equals(post.getProject().getId())) {
                    posts.putIfAbsent(post.getId(), post);
                }
            }
        }
        if (posts.isEmpty()) {
            return List.of();
        }
        String key = projectRepository.findById(projectId).map(Project::getKey).orElse(null);
        Map<String, Statechart> charts = new HashMap<>();
        List<AffectedPost> out = new ArrayList<>();
        Set<WorkItem> ordered = new LinkedHashSet<>(posts.values().stream()
                .sorted(Comparator.comparing(WorkItem::getSequenceNumber, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList());
        for (WorkItem post : ordered) {
            String slug = post.getWorkflow() != null ? post.getWorkflow() : "ENGINEERING";
            Statechart chart = charts.computeIfAbsent(slug + "@" + post.getWorkflowVersion(),
                    k -> resolver.resolveRequired(projectId, slug, post.getWorkflowVersion()));
            CreativeEditImpact impact = CreativeEditImpact.of(chart, post.getCurrentStatus());
            String outcome = impact == CreativeEditImpact.NEW_ROUND && post.getId().equals(postId)
                    ? CreativeEditImpact.REVIEWER_FIX : impact.wire();
            String label = chart.status(post.getCurrentStatus()).map(StatechartStatus::displayLabel)
                    .orElse(post.getCurrentStatus());
            String displayId = key != null && post.getSequenceNumber() != null
                    ? key + "-" + post.getSequenceNumber() : post.getId();
            out.add(new AffectedPost(post.getId(), displayId, post.getTitle(), post.getCurrentStatus(), label, outcome));
        }
        return out;
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
