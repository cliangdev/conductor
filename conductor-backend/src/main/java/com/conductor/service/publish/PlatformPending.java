package com.conductor.service.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What a target that the platform has <em>accepted but not yet published</em> needs for a later verdict:
 * the platform's handle for the publish (TikTok's {@code publish_id}), the kind of post, when the media
 * links it fetches from die, and when it was initiated. Kept as JSON on the target's
 * {@code resume_checkpoint} (a {@code HANDED_OFF} TikTok row has no other use for it) — the invocation's own
 * checkpoint is wiped when the invocation succeeds, so it cannot carry this. See
 * {@code PublishTaskKind#TIKTOK_VERDICT}.
 *
 * <p>A publish action asks for this by reporting {@link #OUTPUT_PENDING_KEY} in its output.
 */
public record PlatformPending(String publishId, String kind, Instant linksExpireAt, Instant initAt) {

    /** Output flag: the platform accepted the post but its outcome is not known yet. */
    public static final String OUTPUT_PENDING_KEY = "platform_pending";
    public static final String OUTPUT_PUBLISH_ID_KEY = "publish_id";
    public static final String OUTPUT_KIND_KEY = "post_kind";
    /** ISO-8601 instant after which the platform can no longer fetch the post's media. */
    public static final String OUTPUT_LINKS_EXPIRE_KEY = "links_expire_at";
    public static final String OUTPUT_INIT_KEY = "init_at";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FIELD = "platformPending";

    public String toJson() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("publishId", publishId);
        inner.put("kind", kind);
        inner.put("linksExpireAt", linksExpireAt == null ? null : linksExpireAt.toString());
        inner.put("initAt", initAt == null ? null : initAt.toString());
        try {
            return MAPPER.writeValueAsString(Map.of(FIELD, inner));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Optional<PlatformPending> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(json).path(FIELD);
            String publishId = node.path("publishId").asText("");
            Instant expires = instant(node.path("linksExpireAt").asText(""));
            if (publishId.isBlank() || expires == null) {
                return Optional.empty();
            }
            return Optional.of(new PlatformPending(publishId, node.path("kind").asText(""), expires,
                    instant(node.path("initAt").asText(""))));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Builds one from a publish action's output map; empty unless it carries the flag, a handle and an expiry. */
    public static Optional<PlatformPending> fromOutput(Map<String, Object> output) {
        if (output == null || !Boolean.TRUE.equals(output.get(OUTPUT_PENDING_KEY))) {
            return Optional.empty();
        }
        String publishId = text(output.get(OUTPUT_PUBLISH_ID_KEY));
        Instant expires = instant(text(output.get(OUTPUT_LINKS_EXPIRE_KEY)));
        if (publishId == null || expires == null) {
            return Optional.empty();
        }
        return Optional.of(new PlatformPending(publishId, text(output.get(OUTPUT_KIND_KEY)), expires,
                instant(text(output.get(OUTPUT_INIT_KEY)))));
    }

    private static String text(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
