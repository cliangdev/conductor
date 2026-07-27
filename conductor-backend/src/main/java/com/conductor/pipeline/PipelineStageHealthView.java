package com.conductor.pipeline;

import java.util.Map;

/**
 * One row of {@code PipelineHealthService#getHealth} -- a fixed pipeline stage's status-keyed counts
 * for a project. {@code stage} matches a {@code PipelineStage} enum name verbatim (e.g. {@code
 * "WEBHOOKS"}); kept as a plain String here rather than depending on the generated model from this
 * internal service layer, mirroring how {@code KnowledgeSourceCountsView} stays independent of
 * {@code KnowledgeSourceCounts} until {@code KnowledgeController} maps it.
 */
public record PipelineStageHealthView(String stage, String label, Map<String, Long> counts) {
}
