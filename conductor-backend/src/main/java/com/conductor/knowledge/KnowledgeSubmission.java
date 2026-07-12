package com.conductor.knowledge;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * One unit of inbound material offered to the Knowledge Center inbox -- a Slack message, a GitHub PR,
 * a manual note, etc. Exactly one of {@code payload} (by-value) or {@code sourceRef} (by-reference,
 * resolved later by the ingestion adapter) must be present. {@code dedupKey} is optional -- when absent,
 * {@link KnowledgeIngestionService} derives one from the other fields so repeat deliveries of the same
 * underlying event collapse to a single row.
 */
public record KnowledgeSubmission(
        String projectId,
        String sourceType,
        String sourceRef,
        String title,
        String contentType,
        String payload,
        OffsetDateTime occurredAt,
        String dedupKey,
        Origin origin,
        Map<String, Object> metadata
) {

    /** Where a submission came from -- e.g. {@code kind="workflow_run", id=<runId>}. */
    public record Origin(String kind, String id) {
    }
}
