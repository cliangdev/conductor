package com.conductor.knowledge.page;

/**
 * One page write in a {@code KnowledgePageService#batchWrite} call. {@code content} is the full page
 * document (frontmatter + body) for a create/update; ignored (may be null) when {@code delete} is true.
 * {@code baseVersion} is the version the caller last observed -- null means "I believe this path doesn't
 * exist yet"; any mismatch against the current stored version is a conflict.
 */
public record PageWrite(String path, String content, Integer baseVersion, boolean delete) {
}
