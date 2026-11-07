package com.conductor.marketing.links;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TrackedLinkRepository extends JpaRepository<TrackedLink, String> {

    boolean existsByProjectIdAndToken(String projectId, String token);

    boolean existsByWorkItemIdAndKind(String workItemId, String kind);

    /** Any one link of a family: its kind is the family's kind, since a family holds one kind. */
    Optional<TrackedLink> findFirstByProjectIdAndFamily(String projectId, String family);

    @Query("SELECT l FROM TrackedLink l WHERE l.projectId = :projectId "
            + "AND (:kind IS NULL OR l.kind = :kind) AND (:workItemId IS NULL OR l.workItemId = :workItemId) "
            + "ORDER BY l.createdAt DESC, l.id")
    List<TrackedLink> search(@Param("projectId") String projectId, @Param("kind") String kind,
                             @Param("workItemId") String workItemId, Pageable page);

    @Query("SELECT COUNT(l) FROM TrackedLink l WHERE l.projectId = :projectId "
            + "AND (:kind IS NULL OR l.kind = :kind) AND (:workItemId IS NULL OR l.workItemId = :workItemId)")
    long countMatching(@Param("projectId") String projectId, @Param("kind") String kind,
                       @Param("workItemId") String workItemId);

    @Query("SELECT COUNT(w) > 0 FROM WorkItem w WHERE w.id = :workItemId AND w.project.id = :projectId")
    boolean workItemInProject(@Param("projectId") String projectId, @Param("workItemId") String workItemId);

    /**
     * Inserts a link unless any unique rule already holds it (the token is taken in the project, or the Post has
     * one): the number of rows inserted, 0 or 1. It cannot raise a unique violation, which matters for the Post
     * path: that insert shares the transaction creating the Post, and a failed statement would poison it.
     * {@code flushAutomatically} writes the just-created Work Item first, so the foreign key finds it; the
     * persistence context is deliberately not cleared, the caller still holds that Work Item.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO tracked_link (id, project_id, kind, family, token, url, work_item_id, label, created_by, created_at)
            VALUES (:id, :projectId, :kind, :family, :token, :url, CAST(:workItemId AS VARCHAR),
                    CAST(:label AS VARCHAR), CAST(:createdBy AS VARCHAR), now())
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") String id, @Param("projectId") String projectId, @Param("kind") String kind,
                       @Param("family") String family, @Param("token") String token, @Param("url") String url,
                       @Param("workItemId") String workItemId, @Param("label") String label,
                       @Param("createdBy") String createdBy);
}
