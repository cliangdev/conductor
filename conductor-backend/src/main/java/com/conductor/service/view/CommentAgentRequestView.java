package com.conductor.service.view;

import java.time.OffsetDateTime;

/**
 * An {@code @agent} request on a thread: who was asked, and how it is going. {@code status} is PENDING, RUNNING,
 * SUCCEEDED or FAILED; {@code error} is set on FAILED, in plain words. The avatar fields are the agent's emoji
 * and color (defaults filled in).
 */
public record CommentAgentRequestView(
        String agentId,
        String agentName,
        String avatarEmoji,
        String avatarColor,
        String status,
        String error,
        OffsetDateTime createdAt) {
}
