package com.conductor.knowledge;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Read view of a {@link KnowledgeSource}. {@code payload} is resolved (downloaded from storage) when
 * the row's content was offloaded and the caller asked for it by id via
 * {@link KnowledgeIngestionService#getSources}; {@link KnowledgeIngestionService#listSources} leaves it
 * null in that case so browsing the inbox never triggers a storage download per row.
 */
public record KnowledgeSourceView(
        String id,
        String projectId,
        String sourceType,
        String sourceRef,
        String title,
        String contentType,
        String payload,
        boolean payloadOffloaded,
        Map<String, Object> metadata,
        KnowledgeSubmission.Origin origin,
        OffsetDateTime occurredAt,
        OffsetDateTime receivedAt,
        KnowledgeSourceStatus status,
        int attempts,
        String errorMessage,
        String skipReason,
        OffsetDateTime purgedAt,
        String domain
) {
}
