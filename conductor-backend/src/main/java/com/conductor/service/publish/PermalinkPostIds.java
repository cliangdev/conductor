package com.conductor.service.publish;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The platform's own id for a post, read out of the link a person pasted after finishing a destination by
 * hand. A by-hand destination has nothing else to go on: the platform never told Conductor the id, so
 * without this the metrics feed had nothing to query and a post someone published themselves never showed
 * a single view.
 *
 * <p>Only shapes the platform's public URLs actually take. Instagram's {@code /p/<shortcode>} is a
 * shortcode, not the media id the Graph API reads by, so it yields nothing rather than something wrong.
 */
public final class PermalinkPostIds {

    private static final Pattern TIKTOK = Pattern.compile("tiktok\\.com/@[^/]+/(?:video|photo)/(\\d+)");
    private static final Pattern YOUTUBE_WATCH = Pattern.compile("[?&]v=([A-Za-z0-9_-]{6,})");
    private static final Pattern YOUTUBE_SHORT = Pattern.compile("(?:youtu\\.be/|youtube\\.com/shorts/)([A-Za-z0-9_-]{6,})");
    private static final Pattern FACEBOOK_POSTS = Pattern.compile("facebook\\.com/(\\d+)/posts/(\\d+)");
    private static final Pattern FACEBOOK_FBID = Pattern.compile("[?&](?:story_fbid|fbid|v)=(\\d+)");

    private PermalinkPostIds() {
    }

    public static Optional<String> parse(String platform, String permalink) {
        if (platform == null || permalink == null || permalink.isBlank()) {
            return Optional.empty();
        }
        String url = permalink.trim();
        return switch (platform.trim().toLowerCase(Locale.ROOT)) {
            case "tiktok" -> group(TIKTOK, url, 1);
            case "youtube" -> group(YOUTUBE_WATCH, url, 1).or(() -> group(YOUTUBE_SHORT, url, 1));
            // Graph reads a Page post as {page-id}_{post-id}; a fbid-style link carries only the post half.
            case "facebook" -> pagePost(url).or(() -> group(FACEBOOK_FBID, url, 1));
            default -> Optional.empty();
        };
    }

    private static Optional<String> pagePost(String url) {
        Matcher m = FACEBOOK_POSTS.matcher(url);
        return m.find() ? Optional.of(m.group(1) + "_" + m.group(2)) : Optional.empty();
    }

    private static Optional<String> group(Pattern pattern, String url, int group) {
        Matcher m = pattern.matcher(url);
        return m.find() ? Optional.of(m.group(group)) : Optional.empty();
    }
}
