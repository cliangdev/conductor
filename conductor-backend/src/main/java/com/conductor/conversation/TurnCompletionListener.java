package com.conductor.conversation;

/**
 * Fired by {@link AgentConversationRunner#runNow} after a conversation turn completes successfully
 * (the persisted reply's status is COMPLETED — never for a FAILED turn). Implementations must return
 * fast (the runner does not wait on them beyond invoking the call) and must never throw meaningfully:
 * the runner invokes each listener inside its own try/catch and only logs a failure, so a listener
 * that means to signal a problem should log it itself rather than rely on the exception surfacing
 * anywhere.
 *
 * <p>Lives in {@code conversation} rather than {@code memory} so this package has no dependency on
 * {@code memory} — {@code memory} already depends on {@link MemoryAugmentor} (a {@code conversation}
 * type), so the reverse dependency would cycle. {@code com.conductor.memory.MemoryExtractionService} is
 * the one implementation today.
 */
public interface TurnCompletionListener {

    void onTurnCompleted(String projectId, String agentId, String conversationId,
                          String userText, String assistantText);
}
