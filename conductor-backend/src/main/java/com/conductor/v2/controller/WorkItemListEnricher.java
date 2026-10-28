package com.conductor.v2.controller;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.WorkItem;
import com.conductor.generated.v2.model.WorkItemCreativeRef;
import com.conductor.generated.v2.model.WorkItemPublishTargetSummary;
import com.conductor.creative.Creative;
import com.conductor.creative.CreativePhotoRepository;
import com.conductor.creative.CreativeRepository;
import com.conductor.creative.CreativeRenderFrame;
import com.conductor.creative.CreativeRenderFrameRepository;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.StorageService;
import com.conductor.service.WorkItemWorkflowService;
import com.conductor.service.publish.PublishPlatformRegistry;
import com.conductor.workflow.lifecycle.Statechart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * List-only enrichment of Work Items for the Posts list: first-image thumbnail URL, publish-target
 * platforms with per-target state, and the linked Creative. Everything is loaded per page of results, never
 * per row: one query for the image Assets, one for the targets, and the two {@code IN} queries the
 * {@link AssetCreativeOriginResolver} already does for the Creative. Items that have none of these simply
 * get an empty enrichment, so non-publishing Workflows pay for the queries but see no new data.
 */
@Component
public class WorkItemListEnricher {

    private static final Logger log = LoggerFactory.getLogger(WorkItemListEnricher.class);
    private static final int THUMBNAIL_URL_EXPIRY_MINUTES = 60;

    public record Enrichment(String thumbnailUrl, List<WorkItemPublishTargetSummary> publishTargets,
                             WorkItemCreativeRef creative) {
        public static final Enrichment EMPTY = new Enrichment(null, List.of(), null);
    }

    private final AssetRepository assets;
    private final PostPublishTargetRepository targets;
    private final AssetCreativeOriginResolver originResolver;
    private final StorageService storage;
    private final CreativeRenderFrameRepository frames;
    private final CreativePhotoRepository photos;
    private final CreativeRepository creatives;
    private final WorkItemWorkflowService workflows;
    private final PublishPlatformRegistry platforms;

    public WorkItemListEnricher(AssetRepository assets, PostPublishTargetRepository targets,
                                AssetCreativeOriginResolver originResolver, StorageService storage,
                                CreativeRenderFrameRepository frames, CreativePhotoRepository photos,
                                CreativeRepository creatives, WorkItemWorkflowService workflows,
                                PublishPlatformRegistry platforms) {
        this.creatives = creatives;
        this.workflows = workflows;
        this.platforms = platforms;
        this.frames = frames;
        this.photos = photos;
        this.assets = assets;
        this.targets = targets;
        this.originResolver = originResolver;
        this.storage = storage;
    }

    /**
     * Ids of the Work Items that use a Creative (through its rendered frames) and/or a Media item (directly,
     * or through a frame that reuses its stored object). A null argument is not applied; when both are
     * given, the Work Item must match both. Null when neither is given (no filtering).
     */
    public Set<String> idsUsing(String creativeId, String mediaId) {
        Set<String> result = null;
        if (creativeId != null && !creativeId.isBlank()) {
            // The whole family (the "a" root and its lettered variants): the Creative tile counts the family,
            // so the list it links to must be the same set of Posts.
            List<String> familyIds = creatives.findById(creativeId)
                    .map(c -> creatives.findAllByNumberAndProjectId(c.getNumber(), c.getProjectId()).stream()
                            .map(Creative::getId).toList())
                    .orElse(List.of(creativeId));
            List<String> frameIds = familyIds.stream()
                    .flatMap(id -> frames.findAllByCreativeId(id).stream())
                    .map(CreativeRenderFrame::getId).toList();
            Set<String> ids = new HashSet<>();
            if (!frameIds.isEmpty()) {
                assets.findAllByCreativeFrameIdIn(frameIds).forEach(a -> ids.add(a.getWorkItem().getId()));
            }
            result = ids;
        }
        if (mediaId != null && !mediaId.isBlank()) {
            Set<String> ids = new HashSet<>();
            assets.findAllByMediaIdFetchWorkItem(mediaId).forEach(a -> ids.add(a.getWorkItem().getId()));
            photos.findById(mediaId).ifPresent(p -> {
                if (p.getGcsPath() != null) {
                    assets.findAllByFrameObjectPathsFetchWorkItem(List.of(p.getGcsPath()))
                            .forEach(a -> ids.add(a.getWorkItem().getId()));
                }
            });
            if (result == null) {
                result = ids;
            } else {
                result.retainAll(ids);
            }
        }
        return result;
    }

    /**
     * Work Item id → enrichment; ids with nothing to add are absent. Only items of a Workflow that declares
     * publishing (platform asset types) are enriched: an Engineering list pays for no extra reads.
     */
    public Map<String, Enrichment> enrich(String projectId, Collection<WorkItem> items) {
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        Map<String, Boolean> publishingBySlug = new HashMap<>();
        List<String> workItemIds = new ArrayList<>();
        for (WorkItem item : items) {
            boolean publishing = publishingBySlug.computeIfAbsent(String.valueOf(item.getWorkflow()), slug -> {
                try {
                    Statechart statechart = workflows.resolveFor(projectId, item);
                    return platforms.declaresPublishing(statechart);
                } catch (RuntimeException e) {
                    return false;
                }
            });
            if (publishing) {
                workItemIds.add(item.getId());
            }
        }
        if (workItemIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Asset>> imagesByItem = new HashMap<>();
        List<Asset> images = assets.findFirstUploadedImagePerWorkItem(workItemIds);
        for (Asset a : images) {
            imagesByItem.computeIfAbsent(a.getWorkItem().getId(), k -> new ArrayList<>()).add(a);
        }
        Map<String, AssetCreativeOriginResolver.Origin> origins = originResolver.resolve(images);

        Map<String, List<PostPublishTarget>> targetsByItem = new HashMap<>();
        for (PostPublishTarget t : targets.findAllByWorkItemIdIn(workItemIds)) {
            targetsByItem.computeIfAbsent(t.getWorkItem().getId(), k -> new ArrayList<>()).add(t);
        }

        Map<String, Enrichment> out = new HashMap<>();
        for (String id : workItemIds) {
            List<Asset> mine = imagesByItem.getOrDefault(id, List.of());
            List<PostPublishTarget> myTargets = targetsByItem.getOrDefault(id, List.of());
            if (mine.isEmpty() && myTargets.isEmpty()) {
                continue;
            }
            out.put(id, new Enrichment(thumbnailOf(mine), summaries(myTargets), creativeOf(mine, origins)));
        }
        return out;
    }

    private String thumbnailOf(List<Asset> images) {
        if (images.isEmpty()) {
            return null;
        }
        try {
            return storage.generateSignedUrl(images.get(0).getGcsPath(), THUMBNAIL_URL_EXPIRY_MINUTES);
        } catch (RuntimeException e) {
            // A signing failure must not take the whole list down; the row just has no thumbnail.
            log.warn("Could not sign a thumbnail URL: {}", e.toString());
            return null;
        }
    }

    private static WorkItemCreativeRef creativeOf(List<Asset> images,
                                                  Map<String, AssetCreativeOriginResolver.Origin> origins) {
        for (Asset a : images) {
            AssetCreativeOriginResolver.Origin o = origins.get(a.getId());
            if (o != null && o.creativeId() != null && o.creativeDisplayId() != null) {
                return new WorkItemCreativeRef(o.creativeId(), o.creativeDisplayId()).name(o.creativeName());
            }
        }
        return null;
    }

    private static List<WorkItemPublishTargetSummary> summaries(List<PostPublishTarget> list) {
        return list.stream()
                .map(t -> new WorkItemPublishTargetSummary(t.getPlatform(), t.getState().name())
                        .stateLabel(PublishTargetResponses.stateLabel(t.getState()))
                        .label(t.getPlatformAccountLabel()))
                .toList();
    }
}
