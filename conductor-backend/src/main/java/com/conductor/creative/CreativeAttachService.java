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
        this.workItemRepository = workItemRepository;
        this.projectSecurityService = projectSecurityService;
        this.targetRepository = targetRepository;
        this.targetAssetRepository = targetAssetRepository;
        this.publishTargetService = publishTargetService;
        this.platformRegistry = platformRegistry;
        this.workflowDefinitionResolver = workflowDefinitionResolver;
        this.objectMapper = objectMapper;
    }


    public record AttachedAsset(String assetId, String frameId, String placementKey, Integer sequenceIndex) {
    }

    public record TargetUpdate(String targetId, String platform, List<String> assetIds, String altText) {
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
        // Frames grouped by placement in render order; the matching rules live in CreativePlacementMedia,
        // shared with PublishTargetService for destinations added after this attach.
        boolean carousel = Creative.KIND_CAROUSEL.equals(creative.getKind());
        CreativePlacementMedia.Placements placements = new CreativePlacementMedia.Placements(carousel)
                .withStillAltText(CreativePlacementMedia.stillAltTextOf(creative));

        // Attaching the same render again (say, to fill a destination added afterwards) reuses the frames
        // already on this Post instead of copying them a second time.
        Map<String, Asset> alreadyAttached = new LinkedHashMap<>();
        for (Asset existing : assetRepository.findAllByWorkItemId(workItemId)) {
            if (existing.getCreativeFrameId() != null) alreadyAttached.putIfAbsent(existing.getCreativeFrameId(), existing);
        }

        // Frames not yet on the Post become Assets in ONE call (one set of workflow checks, one batched
        // insert); the response and the placement map are then built in render order, as before.
        List<CreativeRenderFrame> toCreate = new ArrayList<>();
        List<AssetService.StoredObjectInput> inputs = new ArrayList<>();
        for (CreativeRenderFrame frame : frames) {
            if (alreadyAttached.containsKey(frame.getId())) {
                continue;
            }
            // The Asset links the frame's own stored object: nothing is copied. A succeeded render's frames are
            // deleted only with the Creative, which refuses while a frame is on a Post.
            toCreate.add(frame);
            inputs.add(new AssetService.StoredObjectInput(
                    assetType, "Creative " + creative.displayId() + " " + frame.getPlacementKey(), frame.getGcsPath(),
                    frame.getContentType(), frame.getSizeBytes(), frame.getWidth(), frame.getHeight(), frame.getId(),
                    frame.getDurationSeconds()));
        }
        Map<String, Asset> created = new LinkedHashMap<>();
        List<Asset> createdAssets = assetService.createAllFromStoredObjects(projectId, workItemId, inputs);
        for (int i = 0; i < toCreate.size(); i++) {
            created.put(toCreate.get(i).getId(), createdAssets.get(i));
        }
        for (CreativeRenderFrame frame : frames) {
            Asset asset = alreadyAttached.containsKey(frame.getId())
                    ? alreadyAttached.get(frame.getId()) : created.get(frame.getId());
            attachedAssets.add(new AttachedAsset(asset.getId(), frame.getId(), frame.getPlacementKey(), frame.getSequenceIndex()));
            placements.add(frame, asset.getId());
        }

        List<TargetUpdate> updates = new ArrayList<>();
        List<TargetSkip> skipped = new ArrayList<>();
        List<PublishTargetService.TargetSelection> selections = new ArrayList<>();

        List<PostPublishTarget> targets = targetRepository.findAllByWorkItemId(workItemId);
        Map<String, List<String>> customAssetIds = currentCustomAssetIds(targets);
        for (PostPublishTarget target : targets) {
            List<String> ownAssetIds = target.isCustomMedia()
                    ? customAssetIds.getOrDefault(target.getId(), List.of()) : List.of();
            // Explicitly media-less (custom, no rows — left by an earlier carousel attach or a deselect)
            // has made no choice of its own to protect, so it is eligible like an inheriting one.
            boolean explicitlyEmpty = target.isCustomMedia() && ownAssetIds.isEmpty();
            if (target.isCustomMedia() && !explicitlyEmpty) {
                skipped.add(new TargetSkip(target.getId(), target.getPlatform(),
                        "This destination already has its own chosen media"));
                selections.add(selectionFor(target, ownAssetIds));
                continue;
            }
            List<String> matched = placements.mediaFor(target);
            if (carousel) {
                if (matched.isEmpty()) {
                    // Never inherit: an inheriting target publishes the Post's WHOLE asset set, i.e. every
                    // slide of every placement. An explicit empty selection keeps the carousel off it, and
                    // the preflight (MULTI_IMAGE_UNSUPPORTED) says why.
                    skipped.add(new TargetSkip(target.getId(), target.getPlatform(),
                            "A multi-image carousel can't be published to this destination"));
                    selections.add(selectionFor(target, List.of(), true));
                    continue;
                }
            }
            if (matched.isEmpty()) {
                selections.add(selectionFor(target, List.of(), explicitlyEmpty));
                continue;
            }
            // Instagram alt text: only a lone still, and never over one the author already wrote.
            Map<String, Object> options = parsePublishOptions(target.getPublishOptions());
            String altText = CreativePlacementMedia.hasAltText(options) ? null : placements.altTextFor(target, matched);
            PublishTargetService.TargetSelection selection = selectionFor(target, matched);
            if (altText != null) {
                Map<String, Object> withAlt = options == null ? new LinkedHashMap<>() : new LinkedHashMap<>(options);
                withAlt.put("altText", altText);
                selection = new PublishTargetService.TargetSelection(selection.platform(), selection.connectionId(),
                        withAlt, selection.captionOverride(), selection.assetIds(), selection.format(), selection.noMedia());
            }
            updates.add(new TargetUpdate(target.getId(), target.getPlatform(), matched, altText));
            selections.add(selection);
        }

        publishTargetService.replaceSelection(projectId, workItemId, selections, caller);

        return new AttachResult(attachedAssets, updates, skipped);
    }

    /** Every custom-media target's stored selection, in order, read in one query. */
    private Map<String, List<String>> currentCustomAssetIds(List<PostPublishTarget> targets) {
        List<String> ids = targets.stream().filter(PostPublishTarget::isCustomMedia).map(PostPublishTarget::getId).toList();
        Map<String, List<String>> byTarget = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return byTarget;
        }
        for (PostPublishTargetAsset row : targetAssetRepository.findAllByTargetIdIn(ids)) {
            byTarget.computeIfAbsent(row.getTargetId(), key -> new ArrayList<>()).add(row.getAssetId());
        }
        return byTarget;
    }

    /**
     * Rebuilds one target's selection exactly as it stands today, with only {@code assetIds} possibly
     * different — every other field (platform, connection, format, caption override, publish options)
     * carries through untouched, so {@code replaceSelection} sees a no-op for every target this call does
     * not actually change.
     */
    private PublishTargetService.TargetSelection selectionFor(PostPublishTarget target, List<String> assetIds) {
        return selectionFor(target, assetIds, target.isCustomMedia() && assetIds.isEmpty());
    }

    private PublishTargetService.TargetSelection selectionFor(PostPublishTarget target, List<String> assetIds,
                                                              boolean noMedia) {
        return new PublishTargetService.TargetSelection(target.getPlatform(), target.getConnectionId(),
                parsePublishOptions(target.getPublishOptions()), target.getCaptionOverride(), assetIds,
                PostFormat.parse(target.getFormat()).wire(), noMedia);
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
