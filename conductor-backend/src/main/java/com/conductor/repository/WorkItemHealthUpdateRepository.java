package com.conductor.repository;

import com.conductor.entity.WorkItemHealthUpdate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface WorkItemHealthUpdateRepository extends JpaRepository<WorkItemHealthUpdate, String> {

    /** Each item's most recently confirmed update, at most one per item, in one query. */
    @Query(value = """
            SELECT DISTINCT ON (work_item_id) *
            FROM work_item_health_updates
            WHERE work_item_id IN (:ids) AND confirmed_at IS NOT NULL
            ORDER BY work_item_id, confirmed_at DESC, created_at DESC
            """, nativeQuery = true)
    List<WorkItemHealthUpdate> findLatestConfirmedByWorkItem(@Param("ids") Collection<String> ids);

    /**
     * Each item's newest unconfirmed update created at or after {@code since}, at most one per item, in one
     * query. The caller decides whether it is newer than the item's confirmed update.
     */
    @Query(value = """
            SELECT DISTINCT ON (work_item_id) *
            FROM work_item_health_updates
            WHERE work_item_id IN (:ids) AND confirmed_at IS NULL AND created_at >= :since
            ORDER BY work_item_id, created_at DESC
            """, nativeQuery = true)
    List<WorkItemHealthUpdate> findLatestDraftsSince(@Param("ids") Collection<String> ids,
                                                     @Param("since") OffsetDateTime since);

    List<WorkItemHealthUpdate> findTop50ByWorkItemIdOrderByCreatedAtDesc(String workItemId);
}
