package com.conductor.creative;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.PublishTargetMediaResolver;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
        List<String> frameIds = frameRepository.findAllByCreativeId(creativeId).stream()
                .filter(f -> !f.isSheet())
                .map(CreativeRenderFrame::getId)
                .toList();
        if (frameIds.isEmpty()) {
            return List.of();
        }
        List<Asset> assets = assetRepository.findAllByCreativeFrameIdIn(frameIds);
        if (assets.isEmpty()) {
            return List.of();
        }

        Map<String, Set<String>> assetIdsByWorkItem = new LinkedHashMap<>();
        for (Asset asset : assets) {
            assetIdsByWorkItem.computeIfAbsent(asset.getWorkItem().getId(), k -> new HashSet<>()).add(asset.getId());
        }

        List<PostPublishTarget> matched = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : assetIdsByWorkItem.entrySet()) {
            List<PostPublishTarget> targets = targetRepository.findAllByWorkItemId(entry.getKey());
            if (targets.isEmpty()) {
                continue;
            }
            Map<String, PublishTargetMediaResolver.EffectiveMedia> mediaByTarget =
                    mediaResolver.effectiveMediaByTarget(entry.getKey(), targets);
            for (PostPublishTarget target : targets) {
                if (target.getState() != PostPublishTargetState.PUBLISHED) {
                    continue;
                }
                PublishTargetMediaResolver.EffectiveMedia media = mediaByTarget.get(target.getId());
                if (media == null || media.isEmpty()) {
                    continue;
                }
                boolean matches = media.assetIds().stream().anyMatch(entry.getValue()::contains);
                if (matches) {
                    matched.add(target);
                }
            }
        }
        return matched;
    }
}
