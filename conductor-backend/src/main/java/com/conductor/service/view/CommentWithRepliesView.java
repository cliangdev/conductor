package com.conductor.service.view;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A comment with its replies, enriched with author name and the anchored document's name. Assembled inside the
 * service transaction so controllers can map it without touching lazy {@code author}/{@code document} associations.
 */
public record CommentWithRepliesView(
        String id,
        String documentId,
        String authorId,
        String content,
        OffsetDateTime createdAt,
        String authorName,
        Integer lineNumber,
        String quotedText,
        boolean lineStale,
        String documentName,
        OffsetDateTime resolvedAt,
        List<CommentReplyView> replies) {
}
