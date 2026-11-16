package com.conductor.service.view;

import com.conductor.comment.Mention;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * A comment with its replies, enriched with author name and the anchored document's name. Assembled inside the
 * service transaction so controllers can map it without touching lazy {@code author}/{@code document} associations.
 *
 * <p>{@code authorId} is null for a machine author (an agent run); {@code documentId} is null on a project-doc
 * comment, which carries {@code projectDocId} instead. {@code lineNumber} is the stored value (0 when the
 * comment has no line); use {@code kind} to tell what the comment is anchored to. {@code mentions} are read
 * from the validated tokens in the body; {@code agentRequests} are the {@code @agent} requests on the thread.
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
        CommentAnchor anchor,
        boolean outdated,
        String documentName,
        OffsetDateTime resolvedAt,
        List<CommentReplyView> replies,
        CommentKind kind,
        String workItemId,
        String projectDocId,
        String documentHash,
        String authorKind,
        CommentAuthorAgentView authorAgent,
        OffsetDateTime editedAt,
        String resolvedByName,
        OffsetDateTime updatedAt,
        String anchorStatus,
        List<Mention> mentions,
        List<CommentAgentRequestView> agentRequests) {

    /** The original Work Item shape: a user-authored comment on a Work Item document. */
    public CommentWithRepliesView(
            String id, String documentId, String authorId, String content, OffsetDateTime createdAt,
            String authorName, Integer lineNumber, String quotedText, boolean lineStale, CommentAnchor anchor,
            boolean outdated, String documentName, OffsetDateTime resolvedAt, List<CommentReplyView> replies) {
        this(id, documentId, authorId, content, createdAt, authorName, lineNumber, quotedText, lineStale, anchor,
                outdated, documentName, resolvedAt, replies,
                anchor != null ? CommentKind.QUOTE
                        : (lineNumber != null && lineNumber > 0 ? CommentKind.LINE : CommentKind.DOCUMENT),
                null, null, null, "USER", null, null, null, null, null, List.of(), List.of());
    }
}
