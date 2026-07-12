package com.conductor.knowledge.page;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Full-text search over the wiki bundle, backed by {@code knowledge_pages.search_vector}. */
@Service
public class KnowledgeSearchService {

    private static final int DEFAULT_LIMIT = 20;

    private final KnowledgePageRepository pageRepository;

    public KnowledgeSearchService(KnowledgePageRepository pageRepository) {
        this.pageRepository = pageRepository;
    }

    @Transactional(readOnly = true)
    public List<SearchHit> search(String projectId, String query, String typeFilter, String pathPrefix, Integer limit) {
        int effectiveLimit = limit != null && limit > 0 ? limit : DEFAULT_LIMIT;
        return pageRepository.search(projectId, query, typeFilter, pathPrefix, effectiveLimit).stream()
                .map(row -> new SearchHit(row.getPath(), row.getType(), row.getTitle(), row.getDescription(),
                        row.getSnippet(), row.getRank() != null ? row.getRank() : 0d))
                .toList();
    }
}
