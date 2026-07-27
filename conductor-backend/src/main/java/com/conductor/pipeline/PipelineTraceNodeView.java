package com.conductor.pipeline;

import java.time.OffsetDateTime;

/**
 * One node of {@code PipelineTraceService}'s ordered trace chain. {@code stage} matches a {@code
 * PipelineStage} enum name verbatim, same convention as {@link PipelineStageHealthView}.
 *
 * @param degraded true when the underlying record no longer exists (retention hard-deleted it) --
 *                 the node is a terminal placeholder, not a resolved step; see issue #342.
 */
public record PipelineTraceNodeView(
        String stage,
        String id,
        String status,
        OffsetDateTime occurredAt,
        String label,
        String link,
        boolean degraded) {

    static PipelineTraceNodeView degradedPlaceholder(String stage, String id) {
        return new PipelineTraceNodeView(stage, id, null, null, "Purged by retention", null, true);
    }
}
