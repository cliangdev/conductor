package com.conductor.creative;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one implementation of "which of a Post's Creative frames does this destination publish": the frames
 * grouped by placement, and {@link CreativePlacementTargetMapper}'s rules applied to a target. Shared by
 * {@link CreativeAttachService} (destinations present when a render is attached) and
 * {@code PublishTargetService} (destinations added to the Post afterwards), so the two can never disagree.
 */
@Component
public class CreativePlacementMedia {

    private final CreativeRenderFrameRepository frameRepository;
    private final CreativeRepository creativeRepository;

    public CreativePlacementMedia(CreativeRenderFrameRepository frameRepository,
                                  CreativeRepository creativeRepository) {
        this.frameRepository = frameRepository;
        this.creativeRepository = creativeRepository;
    }

    /**
     * A Creative's frames on one Post, grouped by placement in render order (never re-sorted), with the
     * Asset id that carries each frame.
     */
    public static final class Placements {
        private final boolean carousel;
        // placementKey -> assetIds, in the order the frames were rendered.
        private final Map<String, List<String>> assetIdsByPlacement = new LinkedHashMap<>();
        // Placements whose frame(s) are video (a CLIP creative's frames): decides whether the image or the
        // video mapping rule applies, and feeds the video rule's "only when nothing more specific" fallbacks.
        private final Set<String> videoPlacementKeys = new LinkedHashSet<>();

        public Placements(boolean carousel) {
            this.carousel = carousel;
        }

        public void add(CreativeRenderFrame frame, String assetId) {
            if (frame.getContentType() != null && frame.getContentType().startsWith("video/")) {
                videoPlacementKeys.add(frame.getPlacementKey());
            }
            assetIdsByPlacement.computeIfAbsent(frame.getPlacementKey(), k -> new ArrayList<>()).add(assetId);
        }

        public boolean isCarousel() {
            return carousel;
        }

        public boolean isEmpty() {
            return assetIdsByPlacement.isEmpty();
        }

        /**
         * The Asset ids {@code target} publishes, or an empty list when no placement matches. A carousel
         * goes to the one placement the target prefers; otherwise the first placement, in rendered order,
         * the target matches wins — so a target that could take two video placements ends up with exactly
         * one video, never two.
         */
        public List<String> mediaFor(PostPublishTarget target) {
            if (carousel) {
                String placement = CreativePlacementTargetMapper.carouselPlacementFor(target, assetIdsByPlacement.keySet());
                return placement != null ? assetIdsByPlacement.get(placement) : List.of();
            }
            for (Map.Entry<String, List<String>> entry : assetIdsByPlacement.entrySet()) {
                String placementKey = entry.getKey();
                boolean matches = videoPlacementKeys.contains(placementKey)
                        ? CreativePlacementTargetMapper.matchesVideo(placementKey, target, assetIdsByPlacement.keySet())
                        : CreativePlacementTargetMapper.matches(placementKey, target);
                if (matches) {
                    return entry.getValue();
                }
            }
            return List.of();
        }
    }

    /**
     * The Creative-render placements already on a Post, rebuilt from its Assets (those with a
     * {@code creative_frame_id}), one {@link Placements} per Creative in the order the Post received them.
     * Empty when none of the Post's assets came from a render.
     */
    public List<Placements> forPostAssets(List<Asset> postAssets) {
        Map<String, String> assetIdByFrameId = new LinkedHashMap<>();
        for (Asset asset : postAssets) {
            if (asset.getCreativeFrameId() != null) {
                assetIdByFrameId.putIfAbsent(asset.getCreativeFrameId(), asset.getId());
            }
        }
        if (assetIdByFrameId.isEmpty()) {
            return List.of();
        }
        // Same ordering as CreativeRenderFrameRepository#findAllByRenderIdOrdered: sequence index first
        // (nulls first), then placement key.
        Comparator<CreativeRenderFrame> order = Comparator
                .comparing(CreativeRenderFrame::getSequenceIndex, Comparator.nullsFirst(Comparator.<Integer>naturalOrder()))
                .thenComparing(CreativeRenderFrame::getPlacementKey);
        Map<String, List<CreativeRenderFrame>> byCreative = frameRepository.findAllById(assetIdByFrameId.keySet()).stream()
                .filter(f -> !f.isSheet())
                .sorted(order)
                .collect(Collectors.groupingBy(CreativeRenderFrame::getCreativeId, LinkedHashMap::new, Collectors.toList()));
        List<Placements> result = new ArrayList<>();
        byCreative.forEach((creativeId, frames) -> {
            boolean carousel = creativeRepository.findById(creativeId)
                    .map(c -> Creative.KIND_CAROUSEL.equals(c.getKind())).orElse(false);
            Placements placements = new Placements(carousel);
            frames.forEach(f -> placements.add(f, assetIdByFrameId.get(f.getId())));
            result.add(placements);
        });
        return result;
    }

    /** The first Creative's match for {@code target}, empty when none of the Post's Creatives has a size for it. */
    public static List<String> mediaFor(List<Placements> placements, PostPublishTarget target) {
        for (Placements p : placements) {
            List<String> media = p.mediaFor(target);
            if (!media.isEmpty()) {
                return media;
            }
        }
        return List.of();
    }
}
