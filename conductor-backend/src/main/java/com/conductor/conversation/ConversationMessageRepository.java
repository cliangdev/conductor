package com.conductor.conversation;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, String> {

    /**
     * Keyset page of a conversation's message log, oldest first: {@code (createdAt, id) ASC}. Same
     * cursor/tiebreak shape as {@link ConversationRepository#findPageByProjectId} -- see that method's
     * javadoc, including why this is a native query with an explicit {@code CAST(... AS timestamptz)} on
     * the standalone {@code IS NULL} check only. The tiebreak matters here even more directly: {@code
     * appendUserMessage} inserts the USER row and its reserved ASSISTANT placeholder back-to-back in one
     * transaction, so they routinely share a {@code createdAt}.
     */
    @Query(value = """
            SELECT * FROM conversation_messages
            WHERE conversation_id = :conversationId
              AND (CAST(:cursorTimestamp AS timestamptz) IS NULL
                   OR created_at > :cursorTimestamp
                   OR (created_at = :cursorTimestamp AND id > :cursorId))
            ORDER BY created_at ASC, id ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<ConversationMessage> findPageByConversationId(@Param("conversationId") String conversationId,
                                                         @Param("cursorTimestamp") OffsetDateTime cursorTimestamp,
                                                         @Param("cursorId") String cursorId,
                                                         @Param("limit") int limit);

    /** Newest-first, bounded by {@code pageable}'s page size -- {@link AgentConversationRunner#runNow}
     *  fetches only as many COMPLETED turns as its window-building could possibly need (a small bounded
     *  multiple of {@code MAX_WINDOW_MESSAGES}, see that constant's javadoc) rather than the conversation's
     *  entire history, then reverses the result back into chronological order before windowing. The
     *  existing {@code idx_conversation_messages_conversation (conversation_id, created_at)} index (V110)
     *  still serves this: Postgres reads a btree backwards for {@code DESC} order on the same
     *  {@code (conversation_id, created_at)} prefix, and the added {@code status} filter is just applied
     *  per-row during that same backward index scan -- it doesn't need its own index entry.
     *
     *  <p>The {@code id} tiebreak matches the keyset queries above, for the same reason: {@code
     *  appendUserMessage} inserts a USER row and its ASSISTANT placeholder back-to-back in one
     *  transaction, so once that placeholder completes the pair can share a {@code createdAt}. Ordering
     *  on the timestamp alone could then surface the reply ahead of the message it answers, and {@code
     *  dropOrphanUserTurns} would silently drop the inverted pair out of the window. */
    List<ConversationMessage> findByConversationIdAndStatusOrderByCreatedAtDescIdDesc(
            String conversationId, ConversationMessage.Status status, Pageable pageable);

    /** The single most recent turn regardless of role/status -- {@code ConversationService
     *  #appendUserMessage}'s one-in-flight-turn guard reads this to check whether the conversation is
     *  mid-reply before accepting a new user message. */
    Optional<ConversationMessage> findTopByConversationIdOrderByCreatedAtDesc(String conversationId);
}
