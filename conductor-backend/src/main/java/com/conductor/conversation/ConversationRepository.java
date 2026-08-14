package com.conductor.conversation;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, String> {

    Optional<Conversation> findByProjectIdAndChannelAndChannelKey(String projectId, String channel, String channelKey);

    Page<Conversation> findByProjectIdOrderByLastMessageAtDesc(String projectId, Pageable pageable);

    Optional<Conversation> findByIdAndProjectId(String id, String projectId);

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
