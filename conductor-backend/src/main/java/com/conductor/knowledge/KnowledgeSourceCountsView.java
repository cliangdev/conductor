package com.conductor.knowledge;

/**
 * Per-status row counts for a project's ingestion inbox -- {@link KnowledgeIngestionService#getSourceCounts}'s
 * result. Every status is present with a zero default, even when the project has no sources at all.
 */
public record KnowledgeSourceCountsView(
        long pending,
        long processing,
        long processed,
        long dead
) {
}
