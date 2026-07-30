package com.conductor.knowledge.page;

/**
 * One source in a {@code KnowledgePageService#batchWrite} call's {@code skipped} list: reviewed and
 * deliberately not filed. {@code reason} is required and is stored on {@code knowledge_sources.skip_reason}
 * -- a skip with no reason teaches nobody anything.
 */
public record SkippedSource(String sourceId, String reason) {
}
