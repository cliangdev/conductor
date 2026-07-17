package com.conductor.knowledge.page;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface KnowledgePageRepository extends JpaRepository<KnowledgePage, String> {

    /** Looks up a page regardless of {@code deleted} -- the row identity for a path is unique whether live or tombstoned. */
    Optional<KnowledgePage> findByProjectIdAndPath(String projectId, String path);

    Optional<KnowledgePage> findByProjectIdAndPathAndDeletedFalse(String projectId, String path);

    List<KnowledgePage> findByProjectIdAndPathInAndDeletedFalse(String projectId, Collection<String> paths);

    List<KnowledgePage> findByProjectIdAndDeletedFalseOrderByPath(String projectId);

    List<KnowledgePage> findByProjectIdAndPageTypeAndDeletedFalseOrderByPath(String projectId, String pageType);

    /**
     * Full-text search over {@code search_vector} (native -- the generated tsvector column and the
     * {@code websearch_to_tsquery}/{@code ts_rank}/{@code ts_headline} functions have no JPQL equivalent).
     * {@code typeFilter}/{@code pathPrefix} are optional; pass null to skip either filter.
     */
    @Query(value = """
            SELECT path AS path, page_type AS type, title AS title, description AS description,
                   ts_headline('english', body, websearch_to_tsquery('english', :q),
                               'MaxFragments=1, MaxWords=35, MinWords=15') AS snippet,
                   ts_rank(search_vector, websearch_to_tsquery('english', :q)) AS rank
            FROM knowledge_pages
            WHERE project_id = :projectId
              AND deleted = false
              AND search_vector @@ websearch_to_tsquery('english', :q)
              AND (:typeFilter IS NULL OR page_type = :typeFilter)
              AND (:pathPrefix IS NULL OR path LIKE :pathPrefix || '%')
            ORDER BY rank DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<SearchRow> search(@Param("projectId") String projectId, @Param("q") String query,
                            @Param("typeFilter") String typeFilter, @Param("pathPrefix") String pathPrefix,
                            @Param("limit") int limit);

    interface SearchRow {
        String getPath();
        String getType();
        String getTitle();
        String getDescription();
        String getSnippet();
        Double getRank();
    }
}
