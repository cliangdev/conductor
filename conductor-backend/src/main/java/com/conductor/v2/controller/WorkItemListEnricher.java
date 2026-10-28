package com.conductor.v2.controller;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.generated.v2.model.WorkItemCreativeRef;
import com.conductor.generated.v2.model.WorkItemPublishTargetSummary;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        static final Enrichment EMPTY = new Enrichment(null, List.of(), null);
    }

    private final AssetRepository assets;
    private final PostPublishTargetRepository targets;
    private final AssetCreativeOriginResolver originResolver;
    private final StorageService storage;

    public WorkItemListEnricher(AssetRepository assets, PostPublishTargetRepository targets,
                                AssetCreativeOriginResolver originResolver, StorageService storage) {
        this.assets = assets;
        this.targets = targets;
        this.originResolver = originResolver;
        this.storage = storage;
    }

    /** Work Item id → enrichment; ids with nothing to add are absent. */
    public Map<String, Enrichment> enrich(Collection<String> workItemIds) {
        if (workItemIds == null || workItemIds.isEmpty()) {
            return Map.of();
        }
        Map<String, List<Asset>> imagesByItem = new HashMap<>();
        List<Asset> images = assets.findUploadedImageAssetsByWorkItemIds(workItemIds);
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
