package com.conductor.knowledge.page;

/**
 * Full-content read view of a page (real or virtual -- see {@code KnowledgePageService#VIRTUAL index.md/log.md}).
 * {@code content} is the canonical render (frontmatter + body); virtual pages report {@code version} 0.
 */
public record PageView(String path, int version, String type, String title, String description, String content) {
}
