package com.conductor.repository;

import com.conductor.entity.WorkItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkItemRepository extends JpaRepository<WorkItem, String> {

    /**
     * List a project's Work Items with optional, independent filters on type, current status, and bound
     * Workflow slug. A null filter is ignored, so all 8 combinations are served by one query (avoids a
     * combinatorial set of derived finders). Workflow filtering backs per-Workflow view pages.
     */
    @Query("""
            SELECT i FROM WorkItem i
            WHERE i.project.id = :projectId
              AND (:type IS NULL OR i.type = :type)
              AND (:status IS NULL OR i.currentStatus = :status)
              AND (:workflow IS NULL OR i.workflow = :workflow)
            """)
    List<WorkItem> findByProjectFiltered(@Param("projectId") String projectId,
                                      @Param("type") String type,
                                      @Param("status") String status,
                                      @Param("workflow") String workflow);

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
}
