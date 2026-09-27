package com.conductor.creative;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetAsset;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetAssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.AssetService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.PublishTargetService;
import com.conductor.service.StorageService;
import com.conductor.service.publish.PostFormat;
import com.conductor.service.publish.PublishPlatformRegistry;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * "Use in Post" (COND-24 T3, AC-P0-3.3): copies a SUCCEEDED render's frames into a Post's own assets and,
 * for every target that has not chosen its own media, sets that destination's media by placement —
 * {@link CreativePlacementTargetMapper} owns the placement → target rule; this class owns the storage
 * copy, the {@link com.conductor.entity.Asset} rows, and reconstructing the Post's <em>whole</em> target
 * selection so {@link PublishTargetService#replaceSelection} — the same set-replace a person's own edit
 * goes through — treats this exactly like any other bundle customisation (the approval gate reverts, the
 * bundle hash changes).
 *
 * <p>A target with {@code custom_media = true} already made its own choice and is never overwritten: it is
 * reported in {@code targetsSkipped}, and its existing selection is carried through the rebuilt list
 * unchanged. A target the mapper has no opinion about (a Facebook or Instagram Reel — reels are video, and
 * a Creative's frames are always still images) is touched by neither list.
 */
@Service
public class CreativeAttachService {

    private final CreativeRepository creativeRepository;
    private final CreativeRenderRepository renderRepository;
    private final CreativeRenderFrameRepository frameRepository;
    private final AssetService assetService;
    private final AssetRepository assetRepository;
    private final StorageService storageService;
    private final WorkItemRepository workItemRepository;
    private final ProjectSecurityService projectSecurityService;
    private final PostPublishTargetRepository targetRepository;
    private final PostPublishTargetAssetRepository targetAssetRepository;
    private final PublishTargetService publishTargetService;
    private final PublishPlatformRegistry platformRegistry;
    private final WorkflowDefinitionResolver workflowDefinitionResolver;
    private final ObjectMapper objectMapper;

    public CreativeAttachService(CreativeRepository creativeRepository,
                                 CreativeRenderRepository renderRepository,
                                 CreativeRenderFrameRepository frameRepository,
                                 AssetService assetService,
                                 AssetRepository assetRepository,
                                 StorageService storageService,
                                 WorkItemRepository workItemRepository,
                                 ProjectSecurityService projectSecurityService,
                                 PostPublishTargetRepository targetRepository,
                                 PostPublishTargetAssetRepository targetAssetRepository,
                                 PublishTargetService publishTargetService,
                                 PublishPlatformRegistry platformRegistry,
                                 WorkflowDefinitionResolver workflowDefinitionResolver,
                                 ObjectMapper objectMapper) {
        this.creativeRepository = creativeRepository;
        this.renderRepository = renderRepository;
        this.frameRepository = frameRepository;
        this.assetService = assetService;
        this.assetRepository = assetRepository;
        this.storageService = storageService;
        this.workItemRepository = workItemRepository;
        this.projectSecurityService = projectSecurityService;
        this.targetRepository = targetRepository;
        this.targetAssetRepository = targetAssetRepository;
        this.publishTargetService = publishTargetService;
        this.platformRegistry = platformRegistry;
        this.workflowDefinitionResolver = workflowDefinitionResolver;
        this.objectMapper = objectMapper;
    }

    static final String GCS_PREFIX = "marketing-assets";

    public record AttachedAsset(String assetId, String frameId, String placementKey, Integer sequenceIndex) {
    }

    public record TargetUpdate(String targetId, String platform, List<String> assetIds) {
    }

    public record TargetSkip(String targetId, String platform, String reason) {
    }

    public record AttachResult(List<AttachedAsset> assets, List<TargetUpdate> targetsUpdated,
                               List<TargetSkip> targetsSkipped) {
    }

    @Transactional
    public AttachResult attach(String projectId, String creativeId, String renderId, String workItemId, User caller) {
        requireEditor(projectId, caller);
        Creative creative = creativeRepository.findByIdAndProjectId(creativeId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Creative not found"));
        CreativeRender render = renderRepository.findByIdAndCreativeId(renderId, creativeId)
                .filter(r -> projectId.equals(r.getProjectId()))
                .orElseThrow(() -> new EntityNotFoundException("Render not found"));
        if (!render.isSucceeded()) {
            throw new ConflictException("Render " + renderId + " is not SUCCEEDED (" + render.getState() + ")");
        }
        if (render.isPreviewOnly()) {
            throw new ConflictException("Render " + renderId + " is a preview (a contact sheet only) and has no frames"
                    + " to attach; render the Creative in full first");
        }
        WorkItem workItem = workItemRepository.findById(workItemId)
                .filter(w -> w.getProject() != null && projectId.equals(w.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
        requirePost(projectId, workItem);

        List<CreativeRenderFrame> frames = frameRepository.findAllByRenderIdOrdered(renderId).stream()
                .filter(f -> !f.isSheet())
                .toList();

        String assetType = assetService.defaultAssetType(projectId, workItem);
        List<AttachedAsset> attachedAssets = new ArrayList<>();
        // placementKey -> assetIds, in the same order the frames were rendered (never re-sorted).
        Map<String, List<String>> assetIdsByPlacement = new LinkedHashMap<>();

        // Attaching the same render again (say, to fill a destination added afterwards) reuses the frames
        // already on this Post instead of copying them a second time.
        Map<String, Asset> alreadyAttached = new LinkedHashMap<>();
        for (Asset existing : assetRepository.findAllByWorkItemId(workItemId)) {
            if (existing.getCreativeFrameId() != null) alreadyAttached.putIfAbsent(existing.getCreativeFrameId(), existing);
        }

        for (CreativeRenderFrame frame : frames) {
            Asset reused = alreadyAttached.get(frame.getId());
            if (reused != null) {
                attachedAssets.add(new AttachedAsset(reused.getId(), frame.getId(), frame.getPlacementKey(), frame.getSequenceIndex()));
                assetIdsByPlacement.computeIfAbsent(frame.getPlacementKey(), k -> new ArrayList<>()).add(reused.getId());
                continue;
            }
            String assetId = java.util.UUID.randomUUID().toString();
            String destPath = GCS_PREFIX + "/" + projectId + "/" + workItemId + "/" + assetId + "-"
                    + frame.getPlacementKey() + (frame.getSequenceIndex() != null ? "-" + frame.getSequenceIndex() : "")
                    + "." + extensionFor(frame.getContentType());
            storageService.copy(frame.getGcsPath(), destPath);

            Asset asset = assetService.createFromStoredObject(projectId, workItemId, new AssetService.StoredObjectInput(
                    assetType, "Creative " + creative.displayId() + " " + frame.getPlacementKey(), destPath,
                    frame.getContentType(), frame.getSizeBytes(), frame.getWidth(), frame.getHeight(), frame.getId()));

            attachedAssets.add(new AttachedAsset(asset.getId(), frame.getId(), frame.getPlacementKey(), frame.getSequenceIndex()));
            assetIdsByPlacement.computeIfAbsent(frame.getPlacementKey(), k -> new ArrayList<>()).add(asset.getId());
        }

        List<TargetUpdate> updates = new ArrayList<>();
        List<TargetSkip> skipped = new ArrayList<>();
        List<PublishTargetService.TargetSelection> selections = new ArrayList<>();

        for (PostPublishTarget target : targetRepository.findAllByWorkItemId(workItemId)) {
            if (target.isCustomMedia()) {
                skipped.add(new TargetSkip(target.getId(), target.getPlatform(),
                        "This destination already has its own chosen media"));
                selections.add(selectionFor(target, currentCustomAssetIds(target)));
                continue;
            }
            List<String> matched = matchedAssetIds(target, assetIdsByPlacement);
            if (matched.isEmpty()) {
                selections.add(selectionFor(target, List.of()));
                continue;
            }
            updates.add(new TargetUpdate(target.getId(), target.getPlatform(), matched));
            selections.add(selectionFor(target, matched));
        }

        publishTargetService.replaceSelection(projectId, workItemId, selections, caller);

        return new AttachResult(attachedAssets, updates, skipped);
    }

    /** Storage copy preserves bytes and content type but not a filename — a frame is JPEG or PNG (see
     * {@code docs/creatives.md}), and the copied asset's own path must carry a matching extension. */
    private String extensionFor(String contentType) {
        return "image/png".equals(contentType) ? "png" : "jpg";
    }

    /** The one placement (if any) this target matches, in the order the frames actually rendered. */
    private List<String> matchedAssetIds(PostPublishTarget target, Map<String, List<String>> assetIdsByPlacement) {
        for (Map.Entry<String, List<String>> entry : assetIdsByPlacement.entrySet()) {
            if (CreativePlacementTargetMapper.matches(entry.getKey(), target)) {
                return entry.getValue();
            }
        }
        return List.of();
    }

    private List<String> currentCustomAssetIds(PostPublishTarget target) {
        return targetAssetRepository.findAllByTargetId(target.getId()).stream()
                .map(PostPublishTargetAsset::getAssetId)
                .toList();
    }

    /**
     * Rebuilds one target's selection exactly as it stands today, with only {@code assetIds} possibly
     * different — every other field (platform, connection, format, caption override, publish options)
     * carries through untouched, so {@code replaceSelection} sees a no-op for every target this call does
     * not actually change.
     */
    private PublishTargetService.TargetSelection selectionFor(PostPublishTarget target, List<String> assetIds) {
        return new PublishTargetService.TargetSelection(target.getPlatform(), target.getConnectionId(),
                parsePublishOptions(target.getPublishOptions()), target.getCaptionOverride(), assetIds,
                PostFormat.parse(target.getFormat()).wire());
    }

    private Map<String, Object> parsePublishOptions(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return null;
        }
    }

    /** "Is a Post": the same definition-driven check the publishing pipeline uses everywhere else. */
    private void requirePost(String projectId, WorkItem workItem) {
        String slug = workItem.getWorkflow() != null ? workItem.getWorkflow() : "ENGINEERING";
        Statechart statechart = workflowDefinitionResolver.resolveRequired(projectId, slug, workItem.getWorkflowVersion());
        if (!platformRegistry.declaresPublishing(statechart)) {
            throw new BusinessException("Work Item " + workItem.getId()
                    + " is not a Post — its Workflow does not declare publishing");
        }
    }

    private void requireEditor(String projectId, User caller) {
        if (!projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Project not found");
        }
        if (!projectSecurityService.isAdminOrCreator(projectId, caller.getId())) {
            throw new ForbiddenException("Only ADMIN or CREATOR can attach a Creative to a Post");
        }
    }
}
