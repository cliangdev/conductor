package com.conductor.service.publish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What a target that published <em>without a link</em> needs to find it later: the platform's own handle
 * for the publish (TikTok's {@code publish_id}) and what kind of post it was, which decides the URL shape.
 * Kept as JSON on the target's {@code resume_checkpoint} (a PUBLISHED row has no other use for it), so the
 * follow-up needs no migration. See {@code PublishTaskKind#PERMALINK}.
 *
 * <p>A publish action asks for a follow-up by reporting {@link #OUTPUT_PENDING_KEY} in its output.
 */
public record PermalinkFollowUp(String publishId, String kind) {

    /** Output flag: the post is live but the platform has not given it a link yet. */
    public static final String OUTPUT_PENDING_KEY = "permalink_pending";
    /** Output key naming the platform's publish handle. */
    public static final String OUTPUT_PUBLISH_ID_KEY = "publish_id";
    /** Output key naming the kind of post, e.g. {@code photo} or {@code video}. */
    public static final String OUTPUT_KIND_KEY = "post_kind";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FIELD = "permalinkFollowUp";

    public String toJson() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("publishId", publishId);
        inner.put("kind", kind);
        try {
            return MAPPER.writeValueAsString(Map.of(FIELD, inner));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Optional<PermalinkFollowUp> parse(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = MAPPER.readTree(json).path(FIELD);
            String publishId = node.path("publishId").asText("");
            if (publishId.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new PermalinkFollowUp(publishId, node.path("kind").asText("")));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
