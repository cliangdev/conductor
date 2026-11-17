package com.conductor.service.view;

import com.conductor.comment.Mention;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A comment reply enriched with the author's display name. Assembled inside the service transaction.
 * {@code authorId} is null for a machine author (an agent run), whose byline is {@code authorName}.
 */
public record CommentReplyView(
        String id,
        String commentId,
        String authorId,
        String content,
        OffsetDateTime createdAt,
        String authorName,
        String authorKind,
        CommentAuthorAgentView authorAgent,
        OffsetDateTime editedAt,
        List<Mention> mentions) {

    /** A user-authored reply, as the original Work Item shape had it. */
    public CommentReplyView(String id, String commentId, String authorId, String content,
                            OffsetDateTime createdAt, String authorName) {
        this(id, commentId, authorId, content, createdAt, authorName, "USER", null, null, List.of());
    }
}
