package com.conductor.integration;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * One item an {@link IngestConnector} pull hands to the platform for the Knowledge Center inbox — a
 * deliberate strict subset of {@code KnowledgeSubmission}: no {@code projectId}, no {@code origin}, no
 * {@code domain}. Those are platform-owned; a connector must not choose which project it writes into,
 * forge its own provenance, or override the domain registry.
 *
 * <p>Unlike {@code KnowledgeSubmission}, {@code dedupKey} is REQUIRED here (not optional) — the
 * connector is the only party that knows what makes two of its items the same underlying event, so the
 * platform cannot derive a reasonable default the way it does for direct submissions.
 */
public record IngestItem(
        String sourceType,
        String sourceRef,
        String title,
        String contentType,
        String payload,
        OffsetDateTime occurredAt,
        String dedupKey,
        Map<String, Object> metadata) {

    public IngestItem {
        if (dedupKey == null || dedupKey.isBlank()) {
            throw new IllegalArgumentException("IngestItem.dedupKey is required");
        }
    }
}
