package com.conductor.service.publish;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * How each native platform's read action is read as "has the scheduled post gone live yet?".
 *
 * <p>Every reader returns {@code null} for "cannot tell", which is deliberately not "published": a target
 * is only ever moved to PUBLISHED on a positive signal. Lives beside the registry rather than inside the
 * confirmation poller so a {@link PublishPlatform} can name its reader without the registry depending on
 * the poller.
 */
public final class PlatformLiveness {

    /** YouTube privacy states that mean the video is out in the world. */
    private static final Set<String> YOUTUBE_LIVE_PRIVACY = Set.of("public", "unlisted");

    /** Free-text statuses that mean "it published", for a platform that answers in words rather than a flag. */
    private static final Set<String> LIVE_STATUSES = Set.of("published", "live");

    /** ...and the ones that mean "not yet". */
    private static final Set<String> PENDING_STATUSES = Set.of("scheduled", "draft", "pending", "processing");

    private PlatformLiveness() {
    }

    /**
     * Facebook's own vocabulary: a scheduled Page post reports {@code is_published=false} until it fires.
     * A platform that answers in words instead is read from {@code status}.
     */
    public static Boolean facebookIsLive(Map<String, Object> output) {
        Boolean published = booleanValue(output, "is_published");
        if (published == null) {
            published = booleanValue(output, "published");
        }
        return published != null ? published : statusIsLive(output);
    }

    /**
     * YouTube's: a handed-off upload sits {@code private} with a {@code publish_at}, and the video is out
     * once its privacy status is {@code public} or {@code unlisted}.
     */
    public static Boolean youtubeIsLive(Map<String, Object> output) {
        String privacy = stringValue(output, "privacy_status");
        if (privacy != null) {
            return YOUTUBE_LIVE_PRIVACY.contains(privacy.toLowerCase(Locale.ROOT));
        }
        return statusIsLive(output);
    }

    static Boolean statusIsLive(Map<String, Object> output) {
        String status = stringValue(output, "status");
        if (status == null) {
            return null;
        }
        String normalized = status.toLowerCase(Locale.ROOT);
        if (LIVE_STATUSES.contains(normalized)) {
            return Boolean.TRUE;
        }
        return PENDING_STATUSES.contains(normalized) ? Boolean.FALSE : null;
    }

    static Boolean booleanValue(Map<String, Object> output, String key) {
        Object value = output == null ? null : output.get(key);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof String text) {
            if ("true".equalsIgnoreCase(text)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(text)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    static String stringValue(Map<String, Object> output, String key) {
        Object value = output == null ? null : output.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
