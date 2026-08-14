package com.conductor.conversation;

import com.conductor.agent.provider.ChatMessage;

import java.util.List;

/**
 * The long-term-memory seam for {@link AgentConversationRunner}: given the recent-turns window it's
 * about to hand to {@code AgentExecutionService}, an implementation may prepend additional context
 * drawn from a longer memory than the window covers.
 *
 * <p>Intended layering (not yet built): recent turns verbatim (this interface's input {@code window}),
 * plus summarized older dialogue once a conversation outgrows the window, plus durable facts extracted
 * from past conversations and prepended as synthetic leading context messages. The target shape for
 * that store, sketched here so the eventual migration is copy-paste:
 * <pre>
 * CREATE TABLE agent_memories (
 *     id                     VARCHAR(36)  PRIMARY KEY,
 *     agent_id               VARCHAR(36)  NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
 *     project_id             VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
 *     scope                  VARCHAR(20)  NOT NULL,   -- e.g. 'conversation' | 'project' | 'global'
 *     content                TEXT         NOT NULL,
 *     source_conversation_id VARCHAR(36),
 *     superseded_by          VARCHAR(36)  REFERENCES agent_memories(id),
 *     created_at             TIMESTAMPTZ  NOT NULL DEFAULT now()
 * );
 * </pre>
 */
public interface MemoryAugmentor {

    List<ChatMessage> augment(String projectId, String agentId, String conversationId, List<ChatMessage> window);
}
