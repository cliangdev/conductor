package com.conductor.conversation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, String> {

    Page<ConversationMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId, Pageable pageable);

    /** Unpaginated, ascending -- {@link AgentConversationRunner}'s window-building trims from the full
     *  COMPLETED-only in-memory log rather than a page. */
    List<ConversationMessage> findByConversationIdAndStatusOrderByCreatedAtAsc(
            String conversationId, ConversationMessage.Status status);

    long countByConversationId(String conversationId);
}
