package com.conductor.repository;

import com.conductor.entity.WorkItemAgentSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkItemAgentSessionRepository extends JpaRepository<WorkItemAgentSession, String> {

    Optional<WorkItemAgentSession> findByWorkItemIdAndSessionKey(String workItemId, String sessionKey);

    List<WorkItemAgentSession> findByWorkItemIdOrderByLastHeartbeatAtDesc(String workItemId);

    /**
     * Each item's most recently heard-from open session (no {@code ended_at}), at most one per item, in a
     * single query. Items with no open session are absent.
     */
    @Query(value = """
            SELECT DISTINCT ON (work_item_id) *
            FROM work_item_agent_sessions
            WHERE work_item_id IN (:ids) AND ended_at IS NULL
            ORDER BY work_item_id, last_heartbeat_at DESC
            """, nativeQuery = true)
    List<WorkItemAgentSession> findLatestOpenByWorkItem(@Param("ids") Collection<String> ids);

    /** Distinct sessions that reported WORKING on any of these items at or after {@code since}. */
    @Query("SELECT COUNT(DISTINCT s.sessionKey) FROM WorkItemAgentSession s WHERE s.workItemId IN :ids "
            + "AND s.activity = 'WORKING' AND s.lastHeartbeatAt >= :since")
    long countDistinctWorkingSince(@Param("ids") Collection<String> ids,
                                   @Param("since") java.time.OffsetDateTime since);
}
