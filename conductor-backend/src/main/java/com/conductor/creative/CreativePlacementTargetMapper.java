package com.conductor.creative;

import com.conductor.entity.PostPublishTarget;
import com.conductor.service.publish.PostFormat;

import java.util.Locale;

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
 * <p>A target this mapper does not match (a Facebook or Instagram Reel, say — reels are video, and a
 * Creative's frames are always still images) is simply never touched by attach: it is neither updated nor
 * reported as skipped, because nothing here claims to have an opinion about it.
 */
public final class CreativePlacementTargetMapper {

    private static final String PLACEMENT_9X16 = "9x16";
    private static final String PLACEMENT_4X5 = "4x5";
    private static final String PLACEMENT_1X1 = "1x1";
    private static final String PLACEMENT_STORY = "story";

    private static final String TIKTOK = "tiktok";
    private static final String INSTAGRAM = "instagram";
    private static final String FACEBOOK = "facebook";

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
}
