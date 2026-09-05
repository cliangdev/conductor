package com.conductor.conversation;

/**
 * Thrown by {@code ConversationController} when {@code AgentConversationRunner}'s bounded executor
 * rejects a submission (pool + 50-deep queue both full) -- see {@code
 * ConversationExecutorConfig}'s {@code AbortPolicy}. Maps to 503: the server is temporarily unable to
 * start the turn, not a problem with the request itself. Plain runtime exception, same style as {@link
 * ConversationNotFoundException}/{@link AgentNotAddressableException}.
 */
public class ConversationBusyException extends RuntimeException {

    public ConversationBusyException(String message) {
        super(message);
    }
}
