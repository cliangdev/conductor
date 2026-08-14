package com.conductor.conversation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ConversationMessageRepository extends JpaRepository<ConversationMessage, String> {

    Page<ConversationMessage> findByConversationIdOrderByCreatedAtAsc(String conversationId, Pageable pageable);

    /** Unpaginated, ascending -- {@link AgentConversationRunner}'s window-building trims from the full
     *  COMPLETED-only in-memory log rather than a page. */
    List<ConversationMessage> findByConversationIdAndStatusOrderByCreatedAtAsc(
            String conversationId, ConversationMessage.Status status);

    /** The single most recent turn regardless of role/status -- {@code ConversationService
     *  #appendUserMessage}'s one-in-flight-turn guard reads this to check whether the conversation is
     *  mid-reply before accepting a new user message. */
    Optional<ConversationMessage> findTopByConversationIdOrderByCreatedAtDesc(String conversationId);
}
