package com.conductor.comment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CommentAgentRequestRepository extends JpaRepository<CommentAgentRequest, String> {

    List<CommentAgentRequest> findByCommentIdInOrderByCreatedAtAsc(Collection<String> commentIds);

    Optional<CommentAgentRequest> findFirstByWorkflowRunId(String workflowRunId);

    /** Requests of one agent on one thread that have no reply linked yet, oldest first. */
    List<CommentAgentRequest> findByCommentIdAndAgentIdAndReplyIdIsNullOrderByCreatedAtAsc(String commentId,
                                                                                           String agentId);

    /**
     * Serialises dispatches for one thread: the comment row is the lock, so two mentions of the same agent on
     * the same thread cannot both pass {@link #countInFlight} before either has inserted its row.
     */
    @Query(value = "SELECT id FROM comments WHERE id = :commentId FOR UPDATE", nativeQuery = true)
    List<String> lockComment(@Param("commentId") String commentId);

    /**
     * Requests of this agent on this thread still in flight: dispatched and the run not terminal, or not yet
     * dispatched and younger than {@code staleBefore} (an older undispatched row is a dead dispatch).
     */
    @Query(value = """
            SELECT COUNT(*) FROM comment_agent_requests r
            LEFT JOIN workflow_runs w ON w.id = r.workflow_run_id
            WHERE r.comment_id = :commentId AND r.agent_id = :agentId AND r.error IS NULL
              AND ((r.workflow_run_id IS NULL AND r.created_at > :staleBefore)
                   OR w.status IN ('PENDING', 'PENDING_LOCAL_PICKUP', 'RUNNING', 'CANCELLING'))
            """, nativeQuery = true)
    long countInFlight(@Param("commentId") String commentId, @Param("agentId") String agentId,
                       @Param("staleBefore") OffsetDateTime staleBefore);

    /** Closes undispatched rows older than {@code staleBefore}, which frees the pending unique index. */
    @Modifying
    @Query(value = """
            UPDATE comment_agent_requests SET error = :error
            WHERE comment_id = :commentId AND agent_id = :agentId
              AND workflow_run_id IS NULL AND error IS NULL AND created_at <= :staleBefore
            """, nativeQuery = true)
    int closeStale(@Param("commentId") String commentId, @Param("agentId") String agentId,
                   @Param("staleBefore") OffsetDateTime staleBefore, @Param("error") String error);

    @Query(value = "SELECT id, status FROM workflow_runs WHERE id IN (:runIds)", nativeQuery = true)
    List<Object[]> findRunStatuses(@Param("runIds") Collection<String> runIds);
}
