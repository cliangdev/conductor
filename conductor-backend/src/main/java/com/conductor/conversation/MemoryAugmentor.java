package com.conductor.conversation;

import com.conductor.agent.provider.ChatMessage;

import java.util.List;

/**
 * The long-term-memory seam for {@link AgentConversationRunner}: given the latest user message and the
 * recent-turns window it's about to hand to {@code AgentExecutionService}, an implementation may draw on
 * a longer memory than the window covers and return an {@link Augmentation} carrying whatever it found.
 *
 * <p>Two distinct channels, deliberately kept apart:
 * <ul>
 *   <li>{@link Augmentation#window()} — reserved for a future layer that prepends <em>summarized older
 *   dialogue</em> once a conversation outgrows the window, as synthetic leading {@link ChatMessage}s. Not
 *   built yet ({@link com.conductor.memory.DatabaseMemoryAugmentor} passes {@code window} through
 *   unchanged) — this is the seam it will use.</li>
 *   <li>{@link Augmentation#systemPromptAddendum()} — durable facts/decisions/preferences/events pulled
 *   from {@code com.conductor.memory}, appended to the turn's system prompt suffix instead of injected as
 *   window messages. Two reasons: {@link AgentConversationRunner#MAX_WINDOW_CHARS}'s prior-message cap
 *   governs {@code window} only, so memory content would silently eat into a budget meant for actual
 *   conversation history; and a synthetic USER/ASSISTANT pair never actually said by either party would
 *   pollute the persisted transcript and fight {@code AgentConversationRunner}'s strict-alternation
 *   invariant (see {@code dropOrphanUserTurns}) the moment it needed to be reconstructed from {@code
 *   ConversationMessage} rows.</li>
 * </ul>
 *
 * <p>The {@code agent_memories} table this reads from is described in {@code docs/memory.md} (Phase 6).
 */
public interface MemoryAugmentor {

    Augmentation augment(String projectId, String agentId, String conversationId, String latestUserContent,
                          List<ChatMessage> window);

    /**
     * @param window the (possibly unchanged) recent-turns window
     * @param systemPromptAddendum text to append to the turn's system prompt suffix, or null/blank for
     *                             nothing to add
     */
    record Augmentation(List<ChatMessage> window, String systemPromptAddendum) {

        public static Augmentation unchanged(List<ChatMessage> window) {
            return new Augmentation(window, null);
        }
    }
}
