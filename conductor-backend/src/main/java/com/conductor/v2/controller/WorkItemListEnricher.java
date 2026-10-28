package com.conductor.v2.controller;

import com.conductor.creative.Creative;
import com.conductor.creative.CreativePhoto;
import com.conductor.creative.CreativePhotoRepository;
import com.conductor.creative.CreativeRenderFrame;
import com.conductor.creative.CreativeRenderFrameRepository;
import com.conductor.creative.CreativeRepository;
import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.WorkItem;
import com.conductor.generated.v2.model.WorkItemCreativeRef;
import com.conductor.generated.v2.model.WorkItemPublishTargetSummary;
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
 * List-only enrichment of Work Items for the Posts list: thumbnail URL, publish-target platforms with
 * per-target state, and the linked Creative. Everything is loaded per page of results, never per row: a fixed
 * number of reads whatever the page size (large pages are split into fixed-size chunks, so the statement
 * count grows with the number of chunks, not rows).
 *
 * <ul>
 *   <li><b>Thumbnail</b>: the first (oldest) uploaded image of the Post; if it has none, the poster of its
 *       first video Media item. Assets carry no explicit media order, so upload order is the order.</li>
 *   <li><b>Creative</b>: taken from the Post's first Creative-frame Asset of any content type, independent of
 *       which image is the thumbnail, so a Post with any Creative-origin media shows its Creative.</li>
 * </ul>
 *
 * Only items of a Workflow that declares publishing are enriched.
 */
@Component
public class WorkItemListEnricher {

    private static final Logger log = LoggerFactory.getLogger(WorkItemListEnricher.class);
    private static final int THUMBNAIL_URL_EXPIRY_MINUTES = 60;
    /** Largest IN list sent in one statement. */
    static final int CHUNK = 500;

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
        this.assets = assets;
        this.targets = targets;
        this.originResolver = originResolver;
        this.storage = storage;
        this.frames = frames;
        this.photos = photos;
        this.creatives = creatives;
        this.workflows = workflows;
        this.platforms = platforms;
    }

    private static <T> List<List<T>> chunks(Collection<T> all) {
        List<T> list = new ArrayList<>(all);
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += CHUNK) {
            out.add(list.subList(i, Math.min(list.size(), i + CHUNK)));
        }
        return out;
    }

    /**
     * Ids of the Work Items that use a Creative's family (through its rendered frames) and/or a Media item
     * (directly, or through a frame that reuses its stored object), within {@code projectId}. A null argument
     * is not applied; when both are given the Work Item must match both. Null when neither is given (no
     * filtering). A Creative or Media item that is not in the project matches nothing.
     */
    public Set<String> idsUsing(String projectId, String creativeId, String mediaId) {
        Set<String> result = null;
        if (creativeId != null && !creativeId.isBlank()) {
            // The whole family (the "a" root and its lettered variants): the Creative tile counts the
            // family, so the list it links to must be the same set of Posts.
            List<String> familyIds = creatives.findById(creativeId)
                    .filter(c -> projectId.equals(c.getProjectId()))
                    .map(c -> creatives.findAllByNumberAndProjectId(c.getNumber(), c.getProjectId()).stream()
                            .map(Creative::getId).toList())
                    .orElse(List.of());
            Set<String> ids = new HashSet<>();
            List<String> frameIds = new ArrayList<>();
            for (List<String> chunk : chunks(familyIds)) {
                frames.findAllByCreativeIdIn(chunk).forEach(f -> frameIds.add(f.getId()));
            }
            for (List<String> chunk : chunks(frameIds)) {
                assets.findAllByCreativeFrameIdIn(chunk).forEach(a -> ids.add(a.getWorkItem().getId()));
            }
            result = ids;
        }
        if (mediaId != null && !mediaId.isBlank()) {
            Set<String> ids = new HashSet<>();
            CreativePhoto photo = photos.findById(mediaId).filter(p -> projectId.equals(p.getProjectId())).orElse(null);
            if (photo != null) {
                assets.findAllByMediaIdFetchWorkItem(mediaId).forEach(a -> ids.add(a.getWorkItem().getId()));
                if (photo.getGcsPath() != null) {
                    assets.findAllByFrameObjectPathsFetchWorkItem(List.of(photo.getGcsPath()))
                            .forEach(a -> ids.add(a.getWorkItem().getId()));
                }
            }
            if (result == null) {
                result = ids;
            } else {
                result.retainAll(ids);
            }
        }
        return result;
    }

    /** Work Item id → enrichment; ids with nothing to add are absent. */
    public Map<String, Enrichment> enrich(String projectId, Collection<WorkItem> items) {
        if (items == null || items.isEmpty()) {
            return Map.of();
        }
        // Publishing is a property of the pinned Workflow version, so resolve once per (slug, version).
        Map<String, Boolean> publishingByVersion = new HashMap<>();
        List<String> workItemIds = new ArrayList<>();
        for (WorkItem item : items) {
            String key = item.getWorkflow() + "@" + item.getWorkflowVersion();
            boolean publishing = publishingByVersion.computeIfAbsent(key, k -> declaresPublishing(projectId, item, k));
            if (publishing) {
                workItemIds.add(item.getId());
            }
        }
        if (workItemIds.isEmpty()) {
            return Map.of();
        }

        Map<String, Asset> imageByItem = new HashMap<>();
        Map<String, Asset> frameAssetByItem = new HashMap<>();
        Map<String, List<PostPublishTarget>> targetsByItem = new HashMap<>();
        for (List<String> chunk : chunks(workItemIds)) {
            assets.findFirstUploadedImagePerWorkItem(chunk)
                    .forEach(a -> imageByItem.putIfAbsent(a.getWorkItem().getId(), a));
            assets.findFirstCreativeFrameAssetPerWorkItem(chunk)
                    .forEach(a -> frameAssetByItem.putIfAbsent(a.getWorkItem().getId(), a));
            for (PostPublishTarget t : targets.findAllByWorkItemIdIn(chunk)) {
                targetsByItem.computeIfAbsent(t.getWorkItem().getId(), k -> new ArrayList<>()).add(t);
            }
        }
        Map<String, AssetCreativeOriginResolver.Origin> origins = originResolver.resolve(frameAssetByItem.values());
        Map<String, String> videoPosterPaths = videoPosterPaths(workItemIds, imageByItem.keySet());

        Map<String, Enrichment> out = new HashMap<>();
        for (String id : workItemIds) {
            Asset image = imageByItem.get(id);
            String path = image != null ? image.getGcsPath() : videoPosterPaths.get(id);
            List<PostPublishTarget> myTargets = targetsByItem.getOrDefault(id, List.of());
            WorkItemCreativeRef creative = creativeOf(frameAssetByItem.get(id), origins);
            if (path == null && myTargets.isEmpty() && creative == null) {
                continue;
            }
            out.put(id, new Enrichment(sign(path), summaries(myTargets), creative));
        }
        return out;
    }

    private boolean declaresPublishing(String projectId, WorkItem item, String key) {
        try {
            Statechart statechart = workflows.resolveFor(projectId, item);
            return platforms.declaresPublishing(statechart);
        } catch (RuntimeException e) {
            // One unresolvable Workflow version must not take the whole list down: its items are simply
            // not enriched, and the others are.
            log.warn("Could not resolve workflow {} to decide list enrichment; skipping its items: {}", key, e.toString());
            return false;
        }
    }

    /** Poster object path of each item's first uploaded video Media, for items that have no image. */
    private Map<String, String> videoPosterPaths(List<String> workItemIds, Set<String> withImage) {
        List<String> without = workItemIds.stream().filter(id -> !withImage.contains(id)).toList();
        if (without.isEmpty()) {
            return Map.of();
        }
        Map<String, String> mediaIdByItem = new HashMap<>();
        for (List<String> chunk : chunks(without)) {
            assets.findFirstUploadedVideoPerWorkItem(chunk)
                    .forEach(a -> mediaIdByItem.putIfAbsent(a.getWorkItem().getId(), a.getMediaId()));
        }
        if (mediaIdByItem.isEmpty()) {
            return Map.of();
        }
        Map<String, String> posterByMedia = new HashMap<>();
        for (List<String> chunk : chunks(new HashSet<>(mediaIdByItem.values()))) {
            for (CreativePhoto p : photos.findAllById(chunk)) {
                if (p.getPosterGcsPath() != null) {
                    posterByMedia.put(p.getId(), p.getPosterGcsPath());
                }
            }
        }
        Map<String, String> out = new HashMap<>();
        mediaIdByItem.forEach((item, media) -> {
            String poster = media == null ? null : posterByMedia.get(media);
            if (poster != null) {
                out.put(item, poster);
            }
        });
        return out;
    }

    private String sign(String gcsPath) {
        if (gcsPath == null) {
            return null;
        }
        try {
            return storage.generateSignedUrl(gcsPath, THUMBNAIL_URL_EXPIRY_MINUTES);
        } catch (RuntimeException e) {
            // A signing failure must not take the whole list down; the row just has no thumbnail.
            log.warn("Could not sign a thumbnail URL: {}", e.toString());
            return null;
        }
    }

    private static WorkItemCreativeRef creativeOf(Asset frameAsset,
                                                  Map<String, AssetCreativeOriginResolver.Origin> origins) {
        if (frameAsset == null) {
            return null;
        }
        AssetCreativeOriginResolver.Origin o = origins.get(frameAsset.getId());
        if (o != null && o.creativeId() != null && o.creativeDisplayId() != null) {
            return new WorkItemCreativeRef(o.creativeId(), o.creativeDisplayId()).name(o.creativeName());
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
