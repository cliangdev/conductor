package com.conductor.service.view;

import java.time.OffsetDateTime;

/** A comment reply enriched with the author's display name. Assembled inside the service transaction. */
public record CommentReplyView(
        String id,
        String commentId,
        String authorId,
        String content,
        OffsetDateTime createdAt,
        String authorName) {
}
