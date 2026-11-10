package com.conductor.marketing.insights;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * How long a destination needs before its numbers count as settled, in one place. A post a few hours old has
 * not finished collecting views, so comparing it with older ones understates it; tag and baseline numbers
 * read settled posts only. Keyed by {@code PublishPlatformRegistry} platform ids.
 */
public final class PostSettleWindows {

    /** TikTok, Instagram and YouTube settle after 72 hours. */
    public static final Duration DEFAULT = Duration.ofHours(72);

    /** Facebook reach accrues for longer: 7 days. */
    public static final Duration FACEBOOK = Duration.ofDays(7);

    private static final Map<String, Duration> BY_PLATFORM = Map.of(
            "tiktok", DEFAULT,
            "instagram", DEFAULT,
            "youtube", DEFAULT,
            "facebook", FACEBOOK);

    private PostSettleWindows() {}

    /** The window for a platform id; any platform not listed uses {@link #DEFAULT}. */
    public static Duration of(String platform) {
        return platform == null ? DEFAULT : BY_PLATFORM.getOrDefault(platform, DEFAULT);
    }

    /**
     * Settled when the fire time is at least the platform's window before {@code now}. A destination with no
     * fire time has no age to judge, so it is not settled: it stays out of every baseline, lift and verdict.
     */
    public static boolean isSettled(String platform, OffsetDateTime fireTime, OffsetDateTime now) {
        return fireTime != null && !fireTime.isAfter(now.minus(of(platform)));
    }
}
