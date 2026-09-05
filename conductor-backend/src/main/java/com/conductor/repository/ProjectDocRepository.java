package com.conductor.repository;

import com.conductor.entity.ProjectDoc;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectDocRepository extends JpaRepository<ProjectDoc, String> {

    // LEFT JOIN FETCH, not JOIN FETCH: an agent-authored doc has no createdBy/updatedBy user, and an
    // inner join would silently drop it from every listing.
    @Query("SELECT DISTINCT d FROM ProjectDoc d LEFT JOIN FETCH d.createdBy LEFT JOIN FETCH d.updatedBy WHERE d.project.id = :projectId AND d.folder IS NULL")
    List<ProjectDoc> findByProjectIdAndFolderIsNull(@Param("projectId") String projectId);

    @Query("SELECT DISTINCT d FROM ProjectDoc d LEFT JOIN FETCH d.createdBy LEFT JOIN FETCH d.updatedBy WHERE d.project.id = :projectId AND d.folder.id = :folderId")
    List<ProjectDoc> findByProjectIdAndFolderId(@Param("projectId") String projectId, @Param("folderId") String folderId);

    @Query("SELECT DISTINCT d FROM ProjectDoc d LEFT JOIN FETCH d.createdBy LEFT JOIN FETCH d.updatedBy WHERE d.project.id = :projectId")
    List<ProjectDoc> findAllByProjectId(@Param("projectId") String projectId);

    boolean existsByProjectIdAndFolderIsNullAndTitle(String projectId, String title);

    boolean existsByProjectIdAndFolderIdAndTitle(String projectId, String folderId, String title);

    // ...AndIdNot variants: a relocate re-checks uniqueness for a doc that may not be moving, so the
    // doc must not collide with itself.
    boolean existsByProjectIdAndFolderIsNullAndTitleAndIdNot(String projectId, String title, String id);

    boolean existsByProjectIdAndFolderIdAndTitleAndIdNot(String projectId, String folderId, String title, String id);

    @Query("SELECT d FROM ProjectDoc d LEFT JOIN FETCH d.createdBy LEFT JOIN FETCH d.updatedBy WHERE d.id = :docId")
    Optional<ProjectDoc> findByIdWithUsers(@Param("docId") String docId);

    /**
     * Ranked full-text search over {@code search_vector} (native -- the generated tsvector column and
     * the {@code websearch_to_tsquery}/{@code ts_rank} functions have no JPQL equivalent; see
     * {@code KnowledgePageRepository#search} for the same pattern). Returns entities rather than a
     * projection: the controller's snippet extraction needs {@link ProjectDoc#getContent()} run through
     * {@code DocImageMarkers.summarize} first (collapsing embedded image markers to {@code [image]})
     * before a substring is cut -- a {@code ts_headline} snippet straight off the raw {@code content}
     * column would leak internal GCS storage paths into search results instead.
     *
     * <p>A blank/whitespace-only {@code query} parses to an empty {@code tsquery}, which matches no
     * rows -- safe (no SQL error), just an empty result rather than the old LIKE query's "matches
     * everything" behavior. Neither caller passes a blank query today: the REST endpoint's only consumer
     * (the doc search UI) requires 3+ typed characters, and {@code CoordinatorToolProvider} rejects a
     * blank {@code q} before this is ever called.
     */
    @Query(value = """
            SELECT * FROM project_docs
            WHERE project_id = :projectId
              AND search_vector @@ websearch_to_tsquery('english', :q)
            ORDER BY ts_rank(search_vector, websearch_to_tsquery('english', :q)) DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ProjectDoc> searchByProjectIdAndQuery(@Param("projectId") String projectId, @Param("q") String query,
                                               @Param("limit") int limit);
}
