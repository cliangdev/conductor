package com.conductor.conversation;

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

    /** Unpaginated, ascending -- {@link AgentConversationRunner}'s window-building trims from the full
     *  COMPLETED-only in-memory log rather than a page. */
    List<ConversationMessage> findByConversationIdAndStatusOrderByCreatedAtAsc(
            String conversationId, ConversationMessage.Status status);

    /** The single most recent turn regardless of role/status -- {@code ConversationService
     *  #appendUserMessage}'s one-in-flight-turn guard reads this to check whether the conversation is
     *  mid-reply before accepting a new user message. */
    Optional<ConversationMessage> findTopByConversationIdOrderByCreatedAtDesc(String conversationId);
}
