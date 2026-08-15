package com.conductor.conversation;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, String> {

    Optional<Conversation> findByProjectIdAndChannelAndChannelKey(String projectId, String channel, String channelKey);

    /**
     * Keyset page of a project's conversations, most-recently-active first: {@code (lastMessageAt, id)
     * DESC}. {@code cursorTimestamp}/{@code cursorId} are both null for the first page; otherwise they're
     * the previous page's last row, and the predicate selects strictly-after that position in the same
     * ordering. The {@code id} tiebreak is load-bearing, not decoration -- {@code lastMessageAt} comes
     * from {@code OffsetDateTime.now()}, so two conversations bumped in the same transaction can share a
     * timestamp; without the tiebreak a cursor could skip or repeat a row.
     *
     * <p>Native, not JPQL: a bare {@code :cursorTimestamp IS NULL} check with no adjacent typed operator
     * leaves Postgres unable to infer that bind parameter's type ("could not determine data type of
     * parameter") -- the explicit {@code CAST(... AS timestamptz)} resolves it (Hibernate's native-query
     * parameter scanner reads a trailing {@code ::cast} as part of the parameter name itself, so the
     * shorthand this codebase otherwise uses for {@code @ColumnTransformer} columns doesn't apply here).
     * The other two occurrences, each compared directly against the {@code timestamptz} column via
     * {@code <}/{@code =}, don't need the same cast -- Postgres infers those from the comparison itself.
     */
    @Query(value = """
            SELECT * FROM conversations
            WHERE project_id = :projectId
              AND (CAST(:cursorTimestamp AS timestamptz) IS NULL
                   OR last_message_at < :cursorTimestamp
                   OR (last_message_at = :cursorTimestamp AND id < :cursorId))
            ORDER BY last_message_at DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Conversation> findPageByProjectId(@Param("projectId") String projectId,
                                            @Param("cursorTimestamp") OffsetDateTime cursorTimestamp,
                                            @Param("cursorId") String cursorId,
                                            @Param("limit") int limit);

    Optional<Conversation> findByIdAndProjectId(String id, String projectId);

    /** {@code com.conductor.agent.AgentService#delete}'s guard against orphaning conversation history --
     *  {@code conversations.agent_id} has no {@code ON DELETE} clause, so an agent deleted while still
     *  referenced would otherwise surface as a bare FK-violation 500 instead of an actionable refusal.
     *  A count (not just an existence check) so the refusal message can tell the operator how many
     *  conversations are in the way. */
    long countByAgentId(String agentId);

    /**
     * Locks the conversation row for the rest of the caller's transaction -- {@code
     * ConversationService#appendUserMessage} uses this (and only this) to serialize concurrent POSTs
     * against the same conversation, closing the race where two callers both read the same "latest turn
     * is COMPLETED" state before either has inserted its new USER message.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Conversation c WHERE c.id = :id AND c.projectId = :projectId")
    Optional<Conversation> findWithLockByIdAndProjectId(@Param("id") String id, @Param("projectId") String projectId);
}
