package com.conductor.service.publish;

import java.util.Locale;

/**
 * The shape a destination publishes a Post in. {@link #FEED} is the platform's ordinary post — an image,
 * a video, a carousel, a photo set — and is what every target was before formats existed. The others are
 * platform surfaces with their own rules: a {@link #REEL} is short vertical video with its own endpoint
 * and options, a {@link #STORY} is a single image or clip that disappears after a day, takes no caption
 * and cannot be scheduled by the platform. Which formats a platform offers, and on which lane, is the
 * {@link PublishPlatform}'s to say; what media a format accepts is {@code MediaTargetValidator}'s.
 */
public enum PostFormat {
    FEED,
    REEL,
    STORY;

    /** The plural a human reads in a refusal: "feed posts", "reels", "stories". */
    public String plural() {
        return switch (this) {
            case FEED -> "feed posts";
            case REEL -> "reels";
            case STORY -> "stories";
        };
    }

    /** The value as it travels over the API and into connector inputs: lowercase. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parses a stored or supplied value; null and blank mean {@link #FEED}, anything unknown is an error. */
    public static PostFormat parse(String value) {
        if (value == null || value.isBlank()) {
            return FEED;
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
