package com.conductor.conversation;

/**
 * Thrown when a conversation id doesn't resolve within the given project -- absent, or belonging to a
 * different project, are treated identically so a caller can never distinguish "doesn't exist" from
 * "exists in someone else's project" by probing ids. Plain runtime exception; {@code
 * GlobalExceptionHandler} wiring for a proper HTTP mapping lands with the REST API (a later phase).
 */
public class ConversationNotFoundException extends RuntimeException {

    public ConversationNotFoundException(String conversationId) {
        super("Conversation not found: " + conversationId);
    }

    public ConversationNotFoundException(String projectId, String conversationId) {
        super("Conversation not found: " + conversationId + " in project " + projectId);
    }
}
