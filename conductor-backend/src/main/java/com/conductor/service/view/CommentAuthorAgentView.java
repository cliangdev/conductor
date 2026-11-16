package com.conductor.service.view;

/** The agent behind a comment or reply, for rendering its avatar. */
public record CommentAuthorAgentView(String id, String name, String avatarEmoji, String avatarColor) {
}
