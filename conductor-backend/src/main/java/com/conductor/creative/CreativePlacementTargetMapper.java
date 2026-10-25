package com.conductor.creative;

import com.conductor.entity.PostPublishTarget;
import com.conductor.service.publish.PostFormat;

import java.util.Locale;
import java.util.Set;

/**
 * Where a rendered placement goes on a Post (COND-24 T3, AC-P0-3.3): the one small, tested mapping
 * {@code CreativeAttachService} asks before it sets a target's media. Kept separate from that service
 * (which owns storage copies, Asset creation and the {@code PublishTargetService} call) so the mapping
 * itself — the part most likely to grow a placement or a platform — is a pure function with no
 * collaborators to mock.
 *
 * <h2>The mapping</h2>
 * <ul>
 *   <li>{@code 9x16} → TikTok (its only format), and Instagram's {@code REEL} format.</li>
 *   <li>{@code 4x5} → Instagram's {@code FEED} format.</li>
 *   <li>{@code 1x1} → Facebook's {@code FEED} format.</li>
 *   <li>{@code story} → any platform's {@code STORY} format.</li>
 * </ul>
 *
 * <p>A target this mapper does not match for a still image (a Facebook or Instagram Reel, say — reels are
 * video) is simply never touched by attach: it is neither updated nor reported as skipped, because nothing
 * here claims to have an opinion about it. Video frames (a CLIP's, or a CAROUSEL's companion slideshow) go
 * through {@link #matchesVideo} and {@link #carouselVideoFor} instead.
 */
public final class CreativePlacementTargetMapper {

    private static final String PLACEMENT_9X16 = "9x16";
    private static final String PLACEMENT_4X5 = "4x5";
    private static final String PLACEMENT_1X1 = "1x1";
    private static final String PLACEMENT_STORY = "story";
    private static final String PLACEMENT_16X9 = "16x9";

    private static final String TIKTOK = "tiktok";
    private static final String INSTAGRAM = "instagram";
    private static final String FACEBOOK = "facebook";
    private static final String YOUTUBE = "youtube";

    private CreativePlacementTargetMapper() {
    }

    /** Whether {@code target} is where a frame rendered at {@code placementKey} belongs. */
    public static boolean matches(String placementKey, PostPublishTarget target) {
        if (placementKey == null || target == null || target.getPlatform() == null) {
            return false;
        }
        String platform = target.getPlatform().trim().toLowerCase(Locale.ROOT);
        PostFormat format = PostFormat.parse(target.getFormat());

        return switch (placementKey) {
            case PLACEMENT_9X16 -> TIKTOK.equals(platform) || (INSTAGRAM.equals(platform) && format == PostFormat.REEL);
            case PLACEMENT_4X5 -> INSTAGRAM.equals(platform) && format == PostFormat.FEED;
            case PLACEMENT_1X1 -> FACEBOOK.equals(platform) && format == PostFormat.FEED;
            case PLACEMENT_STORY -> format == PostFormat.STORY;
            default -> false;
        };
    }

    /**
     * The video-aware mapping (COND-24 PR1): a CLIP creative's frames are always video, so the rule set
     * differs from {@link #matches} in three ways beyond the extra platforms — a {@code 9x16} video also
     * fills TikTok (its one format), an Instagram or Facebook reel, Instagram's feed when the set has no {@code 4x5} frame (Instagram
     * publishes a single video as a Reel), and YouTube (its one format) when the set
     * has no {@code 16x9} frame — YouTube prefers the landscape cut, so the choice never depends on order; a
     * {@code 16x9} video fills YouTube and, only when the clip set has no {@code 1x1} frame of its own,
     * Facebook's feed too; and {@code 9x16} additionally fills any story-format target left unclaimed by
     * a dedicated {@code story} frame, on any platform.
     *
     * @param presentPlacementKeys every placement this attach's clip set actually produced a frame for —
     *                            used only for the two "fill in when nothing more specific exists" rules
     *                            above ({@code story}, and {@code 16x9}'s Facebook-feed fallback)
     */
    public static boolean matchesVideo(String placementKey, PostPublishTarget target, Set<String> presentPlacementKeys) {
        if (placementKey == null || target == null || target.getPlatform() == null) {
            return false;
        }
        String platform = target.getPlatform().trim().toLowerCase(Locale.ROOT);
        PostFormat format = PostFormat.parse(target.getFormat());
        Set<String> present = presentPlacementKeys == null ? Set.of() : presentPlacementKeys;

        return switch (placementKey) {
            case PLACEMENT_9X16 -> TIKTOK.equals(platform)
                    || (INSTAGRAM.equals(platform) && format == PostFormat.REEL)
                    || (FACEBOOK.equals(platform) && format == PostFormat.REEL)
                    // Facebook publishes a single Page video as a Reel (vertical recommended), so its feed takes
                    // the vertical cut when there is no square one.
                    || (FACEBOOK.equals(platform) && format == PostFormat.FEED && !present.contains(PLACEMENT_1X1))
                    // Instagram publishes a single video as a Reel whatever the format, so its feed takes the
                    // vertical cut too, unless the set has a 4:5 cut of its own.
                    || (INSTAGRAM.equals(platform) && format == PostFormat.FEED && !present.contains(PLACEMENT_4X5))
                    // YouTube takes the landscape cut when the set has one; the vertical one goes out as a Short.
                    || (YOUTUBE.equals(platform) && !present.contains(PLACEMENT_16X9))
                    || (format == PostFormat.STORY && !present.contains(PLACEMENT_STORY));
            case PLACEMENT_4X5 -> INSTAGRAM.equals(platform) && format == PostFormat.FEED;
            case PLACEMENT_1X1 -> FACEBOOK.equals(platform) && format == PostFormat.FEED;
            case PLACEMENT_16X9 -> YOUTUBE.equals(platform)
                    || (FACEBOOK.equals(platform) && format == PostFormat.FEED && !present.contains(PLACEMENT_1X1)
                        && !present.contains(PLACEMENT_9X16));
            case PLACEMENT_STORY -> format == PostFormat.STORY;
            default -> false;
        };
    }

    /**
     * Whether {@code target} can publish a CAROUSEL's companion 9x16 slideshow video: TikTok, YouTube, and an
     * Instagram or Facebook feed or Reel (a single video there already goes out as a Reel). A story takes
     * one item, so it never does. Returns the placement the video was rendered for.
     */
    public static String carouselVideoFor(PostPublishTarget target) {
        if (target == null || target.getPlatform() == null) {
            return null;
        }
        String platform = target.getPlatform().trim().toLowerCase(Locale.ROOT);
        PostFormat format = PostFormat.parse(target.getFormat());
        boolean takesVideo = TIKTOK.equals(platform) || YOUTUBE.equals(platform)
                || ((INSTAGRAM.equals(platform) || FACEBOOK.equals(platform))
                && (format == PostFormat.FEED || format == PostFormat.REEL));
        return takesVideo ? PLACEMENT_9X16 : null;
    }

    /**
     * What a CAROUSEL with a music track goes out as when the target does not say: the video for TikTok,
     * YouTube and any Reel-format target (they have no multi-image post, or are video-first), the slide
     * images for an Instagram or Facebook feed post.
     */
    public static String defaultCarouselAs(PostPublishTarget target) {
        if (target == null || target.getPlatform() == null) {
            return "photos";
        }
        String platform = target.getPlatform().trim().toLowerCase(Locale.ROOT);
        PostFormat format = PostFormat.parse(target.getFormat());
        return TIKTOK.equals(platform) || YOUTUBE.equals(platform) || format == PostFormat.REEL ? "video" : "photos";
    }

    /**
     * Where a CAROUSEL's image set goes: the one placement (of those that actually have slides) whose list
     * this {@code target} publishes, or null when the target cannot take a multi-image post at all.
     * Instagram's feed prefers {@code 4x5} then {@code 1x1}; Facebook's feed {@code 1x1} then {@code 4x5};
     * TikTok {@code 9x16} then {@code 4x5}. An Instagram or Facebook reel, any story and YouTube get null:
     * a reel is one video, a story one item, YouTube has no image posts.
     */
    public static String carouselPlacementFor(PostPublishTarget target, Set<String> placementsWithSlides) {
        if (target == null || target.getPlatform() == null || placementsWithSlides == null) {
            return null;
        }
        String platform = target.getPlatform().trim().toLowerCase(Locale.ROOT);
        PostFormat format = PostFormat.parse(target.getFormat());
        String[] preference;
        if (INSTAGRAM.equals(platform) && format == PostFormat.FEED) {
            preference = new String[] {PLACEMENT_4X5, PLACEMENT_1X1};
        } else if (FACEBOOK.equals(platform) && format == PostFormat.FEED) {
            preference = new String[] {PLACEMENT_1X1, PLACEMENT_4X5};
        } else if (TIKTOK.equals(platform)) {
            preference = new String[] {PLACEMENT_9X16, PLACEMENT_4X5};
        } else {
            return null;
        }
        for (String placement : preference) {
            if (placementsWithSlides.contains(placement)) {
                return placement;
            }
        }
        return null;
    }
}
