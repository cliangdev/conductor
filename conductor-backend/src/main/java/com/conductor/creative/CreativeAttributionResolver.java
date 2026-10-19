package com.conductor.creative;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.PublishTargetMediaResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one place that answers "which published destinations actually sent this Creative" (COND-24 T5):
 * follows {@code creative_render_frame} → {@code assets.creative_frame_id} → the owning Post's publish
 * targets, resolved through {@link PublishTargetMediaResolver} exactly the way the approval gate and the
 * bundle hash do — an inheriting target (custom_media = false) counts only when the frame's asset is
 * still part of the Post's shared media, and a target that chose its own media counts only when that
 * frame's asset is among its own explicit selection. Both {@code CreativePerformanceService} and {@code
 * CreativeExperimentService} read through this rather than each re-deriving the same join.
 */
@Component
public class CreativeAttributionResolver {

    private final CreativeRenderFrameRepository frameRepository;
    private final AssetRepository assetRepository;
    private final PostPublishTargetRepository targetRepository;
    private final PublishTargetMediaResolver mediaResolver;

    public CreativeAttributionResolver(CreativeRenderFrameRepository frameRepository,
                                       AssetRepository assetRepository,
                                       PostPublishTargetRepository targetRepository,
                                       PublishTargetMediaResolver mediaResolver) {
        this.frameRepository = frameRepository;
        this.assetRepository = assetRepository;
        this.targetRepository = targetRepository;
        this.mediaResolver = mediaResolver;
    }

    /**
     * Every {@code PUBLISHED} destination whose actual media includes at least one frame this Creative
     * rendered — across every render it has ever had, not only its latest. Empty when the Creative has
     * never been rendered, never attached to a Post, or never actually published.
     */
    public List<PostPublishTarget> resolvePublishedTargets(String creativeId) {
        return resolvePublishedTargetsByCreative(List.of(creativeId)).getOrDefault(creativeId, List.of());
    }

    /**
     * {@link #resolvePublishedTargets} for many Creatives at once in a constant number of queries (frames,
     * frame assets, targets, media) however many Creatives, frames or Posts are involved. Creatives with
     * no attributed destination are absent from the result.
     */
    public Map<String, List<PostPublishTarget>> resolvePublishedTargetsByCreative(Collection<String> creativeIds) {
        if (creativeIds == null || creativeIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> creativeByFrame = new LinkedHashMap<>();
        for (CreativeRenderFrame f : frameRepository.findAllByCreativeIdIn(creativeIds)) {
            if (!f.isSheet()) {
                creativeByFrame.put(f.getId(), f.getCreativeId());
            }
        }
        if (creativeByFrame.isEmpty()) {
            return Map.of();
        }
        List<Asset> assets = assetRepository.findAllByCreativeFrameIdIn(creativeByFrame.keySet());
        if (assets.isEmpty()) {
            return Map.of();
        }

        // creative -> work item -> the asset ids that creative's frames became on that Post
        Map<String, Map<String, Set<String>>> assetIdsByCreative = new LinkedHashMap<>();
        Set<String> workItemIds = new LinkedHashSet<>();
        for (Asset asset : assets) {
            String creativeId = creativeByFrame.get(asset.getCreativeFrameId());
            if (creativeId == null) {
                continue;
            }
            String workItemId = asset.getWorkItem().getId();
            workItemIds.add(workItemId);
            assetIdsByCreative.computeIfAbsent(creativeId, k -> new LinkedHashMap<>())
                    .computeIfAbsent(workItemId, k -> new HashSet<>()).add(asset.getId());
        }

        Map<String, List<PostPublishTarget>> publishedByWorkItem = new LinkedHashMap<>();
        for (PostPublishTarget target : targetRepository.findAllByWorkItemIdIn(workItemIds)) {
            publishedByWorkItem.computeIfAbsent(target.getWorkItem().getId(), k -> new ArrayList<>()).add(target);
        }
        List<PostPublishTarget> allTargets = publishedByWorkItem.values().stream().flatMap(List::stream).toList();
        Map<String, PublishTargetMediaResolver.EffectiveMedia> mediaByTarget =
                mediaResolver.effectiveMediaForTargets(allTargets);

        Map<String, List<PostPublishTarget>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Set<String>>> byCreative : assetIdsByCreative.entrySet()) {
            List<PostPublishTarget> matched = new ArrayList<>();
            for (Map.Entry<String, Set<String>> entry : byCreative.getValue().entrySet()) {
                for (PostPublishTarget target : publishedByWorkItem.getOrDefault(entry.getKey(), List.of())) {
                    if (target.getState() != PostPublishTargetState.PUBLISHED) {
                        continue;
                    }
                    PublishTargetMediaResolver.EffectiveMedia media = mediaByTarget.get(target.getId());
                    if (media == null || media.isEmpty()) {
                        continue;
                    }
                    if (media.assetIds().stream().anyMatch(entry.getValue()::contains)) {
                        matched.add(target);
                    }
                }
            }
            if (!matched.isEmpty()) {
                result.put(byCreative.getKey(), matched);
            }
        }
        return result;
    }
}
