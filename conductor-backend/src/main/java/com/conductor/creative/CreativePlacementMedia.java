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

    private static final com.fasterxml.jackson.databind.ObjectMapper OPTIONS_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final CreativeRenderFrameRepository frameRepository;
    private final CreativeRepository creativeRepository;

    /** The alt text a single still from {@code creative} carries: its altText, else slideAltText[0]. */
    public static String stillAltTextOf(Creative creative) {
        if (creative == null) return null;
        if (creative.getAltText() != null && !creative.getAltText().isBlank()) return creative.getAltText();
        var slides = creative.getSlideAltText();
        if (slides != null && slides.isArray() && slides.size() > 0 && slides.get(0).isTextual()
                && !slides.get(0).asText().isBlank()) {
            return slides.get(0).asText();
        }
        return null;
    }

    /**
     * {@code optionsJson}/{@code options} helper: whether the options bag already holds a non-blank altText.
     */
    public static boolean hasAltText(Map<String, Object> options) {
        if (options == null) return false;
        Object v = options.get("altText");
        return v != null && !v.toString().isBlank();
    }

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

        // A CAROUSEL's companion slideshow video (its video/mp4 frame), kept out of the per-placement slide
        // lists so a photo selection can never pick it up.
        private String videoAssetId;
        // Whether the carousel's audio is a track, i.e. whether a per-target photos/video choice exists.
        private boolean hasTrack;

        // Asset ids whose frame is a still image (content type image/*).
        private final Set<String> imageAssetIds = new LinkedHashSet<>();
        // The Creative's alt text for a single still: its own altText, else slideAltText[0]. Null when blank.
        private String stillAltText;

        public Placements(boolean carousel) {
            this.carousel = carousel;
        }

        public Placements withTrack(boolean hasTrack) {
            this.hasTrack = hasTrack;
            return this;
        }

        public boolean hasTrack() {
            return hasTrack;
        }

        /**
         * How a CAROUSEL with a track goes out on {@code target}, or null when there is no choice to make
         * (no track, or not a carousel). The target's own {@code carouselAs} option wins; otherwise the
         * default for its platform and format. {@code video} needs a slideshow frame and a target that can
         * publish it, and falls back to {@code photos} without them.
         */
        public String carouselAsFor(PostPublishTarget target) {
            if (!carousel || !hasTrack) {
                return null;
            }
            return wantsVideo(target) && videoAssetId != null && CreativePlacementTargetMapper.carouselVideoFor(target) != null
                    ? "video" : "photos";
        }

        /** Video was asked for (explicitly or by default) on a target that can take it, but none was rendered. */
        public boolean videoMissingFor(PostPublishTarget target) {
            return carousel && hasTrack && videoAssetId == null && wantsVideo(target)
                    && CreativePlacementTargetMapper.carouselVideoFor(target) != null;
        }

        private static boolean wantsVideo(PostPublishTarget target) {
            String explicit = explicitCarouselAs(target);
            return explicit != null ? "video".equals(explicit)
                    : "video".equals(CreativePlacementTargetMapper.defaultCarouselAs(target));
        }

        /** The target's own {@code carouselAs} option, lower-cased, when it is a valid value. */
        static String explicitCarouselAs(PostPublishTarget target) {
            if (target == null || target.getPublishOptions() == null || target.getPublishOptions().isBlank()) {
                return null;
            }
            try {
                var node = OPTIONS_MAPPER.readTree(target.getPublishOptions()).get("carouselAs");
                if (node != null && node.isTextual()) {
                    String value = node.asText().trim().toLowerCase(java.util.Locale.ROOT);
                    return "photos".equals(value) || "video".equals(value) ? value : null;
                }
            } catch (Exception e) {
                // An unreadable bag is reported elsewhere; here it simply states no preference.
            }
            return null;
        }

        public Placements withStillAltText(String altText) {
            this.stillAltText = altText == null || altText.isBlank() ? null : altText.strip();
            return this;
        }

        /**
         * The alt text to set on {@code target}'s {@code altText} option for the {@code matched} media, or
         * null. Only an Instagram target that ends up with exactly one still image from a non-carousel
         * Creative gets one: carousels, video and other platforms take none.
         */
        public String altTextFor(PostPublishTarget target, List<String> matched) {
            if (stillAltText == null || carousel || target == null || target.getPlatform() == null
                    || !"instagram".equals(target.getPlatform().trim().toLowerCase(java.util.Locale.ROOT))
                    || matched == null || matched.size() != 1 || !imageAssetIds.contains(matched.get(0))) {
                return null;
            }
            return stillAltText;
        }

        public void add(CreativeRenderFrame frame, String assetId) {
            if (carousel && frame.getContentType() != null && frame.getContentType().startsWith("video/")) {
                if (videoAssetId == null) {
                    videoAssetId = assetId;
                }
                return;
            }
            if (frame.getContentType() != null && frame.getContentType().startsWith("image/")) {
                imageAssetIds.add(assetId);
            }
            if (frame.getContentType() != null && frame.getContentType().startsWith("video/")) {
                videoPlacementKeys.add(frame.getPlacementKey());
            }
            assetIdsByPlacement.computeIfAbsent(frame.getPlacementKey(), k -> new ArrayList<>()).add(assetId);
        }

        public boolean isCarousel() {
            return carousel;
        }

        public boolean isEmpty() {
            return assetIdsByPlacement.isEmpty() && videoAssetId == null;
        }

        /**
         * The Asset ids {@code target} publishes, or an empty list when no placement matches. A carousel
         * goes to the one placement the target prefers; otherwise the first placement, in rendered order,
         * the target matches wins — so a target that could take two video placements ends up with exactly
         * one video, never two.
         */
        public List<String> mediaFor(PostPublishTarget target) {
            if (carousel) {
                if ("video".equals(carouselAsFor(target))) {
                    return List.of(videoAssetId);
                }
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
            Creative creative = creativeRepository.findById(creativeId).orElse(null);
            boolean carousel = creative != null && Creative.KIND_CAROUSEL.equals(creative.getKind());
            Placements placements = new Placements(carousel).withStillAltText(stillAltTextOf(creative))
                    .withTrack(carousel && hasTrack(creative));
            frames.forEach(f -> placements.add(f, assetIdByFrameId.get(f.getId())));
            result.add(placements);
        });
        return result;
    }

    /** Whether a Creative's audio is a music track (a CAROUSEL then also has a slideshow video). */
    public static boolean hasTrack(Creative creative) {
        return creative != null && creative.getAudio() != null && creative.getAudio().hasNonNull("source")
                && "track".equals(creative.getAudio().get("source").asText());
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

    /**
     * Whether a target that matched nothing must get an explicit empty selection rather than inherit: true
     * when the Post carries a carousel, whose whole set (every slide of every size) must never be inherited.
     * Mirrors {@link CreativeAttachService}'s carousel branch.
     */
    public static boolean carouselCannotPublishTo(List<Placements> placements) {
        return placements.stream().anyMatch(p -> p.isCarousel() && !p.isEmpty());
    }
}
