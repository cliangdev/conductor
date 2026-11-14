package com.conductor.marketing.insights;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Turns the raw per-platform opening and watch numbers a metrics snapshot keeps in {@code extra} into two
 * normalised rates, so a short-form video's hook can be judged the same way on every platform.
 *
 * <ul>
 *   <li><b>hookRate</b> (0 to 1): the share of viewers who stayed past the opening. Instagram:
 *       {@code 1 - reels_skip_rate} (Meta's estimated share of initial views skipped within 3 seconds; a value
 *       above 1 is read as a percentage). Facebook: the Reel's retention at the 3 second mark
 *       ({@code video_retention_3s}). YouTube: the first bucket of the audience retention curve
 *       ({@code opening_hold}, {@code audienceWatchRatio} at the start of the video, capped at 1). TikTok
 *       reports none of these.</li>
 *   <li><b>holdRate</b> (0 to 1): average watch time over video duration, capped at 1 (replays push an
 *       average past the video length). Instagram and Facebook: average watch time in milliseconds over
 *       {@code video_duration_s}; Instagram's Graph API does not expose duration, so its holdRate is null
 *       unless a duration is present. YouTube: {@code avg_view_pct / 100}, else {@code avg_view_duration_s}
 *       over duration.</li>
 * </ul>
 *
 * The three hook sources are not the same measure (a 3 second skip, a 3 second retention point and the
 * first percent of a video). That is acceptable only because a lift is always taken against the baseline of
 * the same platform and format ({@link PostBaselineService}), never across platforms. A rate that is not
 * reported, or cannot be derived, is null, never 0.
 */
public final class HookMetrics {

    /** The two derived rates of one destination; either may be null. */
    public record Rates(Double hookRate, Double holdRate) {
        public static final Rates NONE = new Rates(null, null);
    }

    private HookMetrics() {}

    public static Rates of(String platform, JsonNode extra) {
        if (platform == null || extra == null || !extra.isObject()) {
            return Rates.NONE;
        }
        return new Rates(hookRate(platform, extra), holdRate(platform, extra));
    }

    static Double hookRate(String platform, JsonNode extra) {
        return switch (platform) {
            case "instagram" -> {
                Double skip = number(extra, "reels_skip_rate");
                if (skip == null || skip < 0) {
                    yield null;
                }
                double fraction = skip > 1 ? skip / 100.0 : skip;
                yield clamp(1.0 - fraction);
            }
            case "facebook" -> fraction(number(extra, "video_retention_3s"));
            case "youtube" -> fraction(number(extra, "opening_hold"));
            default -> null;
        };
    }

    static Double holdRate(String platform, JsonNode extra) {
        Double duration = number(extra, "video_duration_s");
        boolean knownDuration = duration != null && duration > 0;
        return switch (platform) {
            case "instagram" -> watchOverDuration(number(extra, "ig_reels_avg_watch_time"), duration, knownDuration);
            case "facebook" -> watchOverDuration(number(extra, "post_video_avg_time_watched"), duration, knownDuration);
            case "youtube" -> {
                Double pct = number(extra, "avg_view_pct");
                if (pct != null && pct >= 0) {
                    yield clamp(pct / 100.0);
                }
                Double seconds = number(extra, "avg_view_duration_s");
                yield seconds == null || seconds < 0 || !knownDuration ? null : clamp(seconds / duration);
            }
            default -> null;
        };
    }

    /** Average watch time is in milliseconds on both Meta platforms. */
    private static Double watchOverDuration(Double watchMs, Double duration, boolean knownDuration) {
        if (watchMs == null || watchMs < 0 || !knownDuration) {
            return null;
        }
        return clamp(watchMs / 1000.0 / duration);
    }

    private static Double fraction(Double value) {
        return value == null || value < 0 ? null : clamp(value);
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static Double number(JsonNode extra, String key) {
        JsonNode node = extra.get(key);
        return node != null && node.isNumber() ? node.asDouble() : null;
    }
}
