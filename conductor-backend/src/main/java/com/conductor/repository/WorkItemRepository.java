package com.conductor.repository;

import com.conductor.entity.WorkItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkItemRepository extends JpaRepository<WorkItem, String> {

    /**
     * List a project's Work Items with optional, independent filters on type, current status, bound
     * Workflow slug, and tag. A null filter is ignored, so every combination is served by one query
     * (avoids a combinatorial set of derived finders). Workflow filtering backs per-Workflow view pages;
     * tag filtering is what makes a freeform label worth applying rather than decoration.
     */
    @Query("""
            SELECT i FROM WorkItem i
            WHERE i.project.id = :projectId
              AND (:type IS NULL OR i.type = :type)
              AND (:status IS NULL OR i.currentStatus = :status)
              AND (:workflow IS NULL OR i.workflow = :workflow)
              AND (:tag IS NULL OR :tag MEMBER OF i.tags)
            """)
    List<WorkItem> findByProjectFiltered(@Param("projectId") String projectId,
                                      @Param("type") String type,
                                      @Param("status") String status,
                                      @Param("workflow") String workflow,
                                      @Param("tag") String tag);

    @Query("SELECT COALESCE(MAX(i.sequenceNumber), 0) FROM WorkItem i WHERE i.project.id = :projectId")
    Integer findMaxSequenceNumberByProjectId(@Param("projectId") String projectId);

    @Query("SELECT i FROM WorkItem i JOIN i.project p WHERE p.key = :projectKey AND i.sequenceNumber = :sequenceNumber")
    Optional<WorkItem> findByProjectKeyAndSequenceNumber(@Param("projectKey") String projectKey, @Param("sequenceNumber") Integer sequenceNumber);

    /**
     * Resolve a Work Item by its project-scoped sequence number, used by the v2 by-display lookup. The
     * derived property path is valid on {@link WorkItem}: {@code project} (ManyToOne) → {@code project.id}
     * plus {@code sequenceNumber}.
     */
    Optional<WorkItem> findByProjectIdAndSequenceNumber(String projectId, Integer sequenceNumber);

    @Query("SELECT COUNT(i) FROM WorkItem i WHERE i.workflow = :slug")
    long countByWorkflowSlug(@Param("slug") String slug);

    @Query("SELECT i.workflow, COUNT(i) FROM WorkItem i WHERE i.workflow IN :slugs GROUP BY i.workflow")
    List<Object[]> countGroupedByWorkflowSlug(@Param("slugs") Collection<String> slugs);

    @Query("SELECT COUNT(i) FROM WorkItem i WHERE i.workflow = :slug AND i.workflowVersion = :version")
    long countByWorkflowSlugAndVersion(@Param("slug") String slug, @Param("version") int version);

    /**
     * Single Work Item with its {@code project} and {@code assignee} eagerly resolved -- both are
     * {@code FetchType.LAZY}, so a naive {@code findById} would cost 2 extra selects (or throw
     * {@code LazyInitializationException} once the loading session closes) the moment a caller reads
     * either. Backs {@link com.conductor.service.WorkItemSnapshotService}, which needs both up front.
     */
    @Query("SELECT i FROM WorkItem i LEFT JOIN FETCH i.project LEFT JOIN FETCH i.assignee WHERE i.id = :workItemId")
    Optional<WorkItem> findByIdWithProjectAndAssignee(@Param("workItemId") String workItemId);
}
