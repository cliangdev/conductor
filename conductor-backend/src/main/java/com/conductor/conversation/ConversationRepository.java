package com.conductor.conversation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, String> {

    Optional<Conversation> findByProjectIdAndChannelAndChannelKey(String projectId, String channel, String channelKey);

    Page<Conversation> findByProjectIdOrderByLastMessageAtDesc(String projectId, Pageable pageable);

    Optional<Conversation> findByIdAndProjectId(String id, String projectId);
}
