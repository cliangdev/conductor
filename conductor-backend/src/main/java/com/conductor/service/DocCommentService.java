package com.conductor.service;

import com.conductor.service.view.CommentReplyView;
import com.conductor.service.view.CommentWithRepliesView;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The v1 project-doc comment API, as a facade over {@link CommentService}: project-doc comments live in the
 * unified {@code comments} table (see {@code docs/comments.md}); the legacy {@code doc_comments} tables are no
 * longer written by this code. Results are views assembled inside the transaction, so the controller can map
 * them without touching lazy associations.
 *
 * <p>Kept so the v1 endpoints return exactly what they always did, until they are removed.
 */
@Service
public class DocCommentService {

    private final CommentService commentService;

    public DocCommentService(CommentService commentService) {
        this.commentService = commentService;
    }

    /** A whole-doc comment when {@code lineNumber} is null; the client supplies the quoted text. */
    public CommentWithRepliesView createComment(String projectId, String docId, ProjectActor actor, String content,
                                                Integer lineNumber, String quotedText) {
        return commentService.createProjectDocComment(projectId, docId, actor, content, lineNumber, quotedText);
    }

    /** Every comment on a doc with its replies, oldest first. */
    public List<CommentWithRepliesView> listThreads(String projectId, String docId, ProjectActor actor) {
        return commentService.listThreads(projectId, new CommentTarget.ProjectDoc(docId),
                CommentService.StatusFilter.ALL, actor);
    }

    /**
     * Author-or-admin for a human caller. A machine actor may delete only comments a machine actor
     * left: a run-scoped token is short-lived and non-attributable, and an agent's job is to answer a
     * reviewer's comment, not to remove it.
     */
    public void deleteComment(String projectId, String docId, String commentId, ProjectActor actor) {
        commentService.deleteProjectDocComment(projectId, docId, commentId, actor);
    }

    public CommentReplyView addReply(String projectId, String docId, String commentId, ProjectActor actor,
                                     String content) {
        return commentService.addProjectDocReply(projectId, docId, commentId, actor, content);
    }

    public CommentWithRepliesView resolveThread(String projectId, String docId, String commentId,
                                                ProjectActor actor) {
        return commentService.resolveProjectDocThread(projectId, docId, commentId, actor);
    }
}
