package com.conductor.v2.controller;

import com.conductor.entity.Comment;
import com.conductor.entity.User;
import com.conductor.generated.v2.api.WorkItemCommentsApi;
import com.conductor.generated.v2.model.AddCommentReplyRequest;
import com.conductor.generated.v2.model.CommentReplyResponse;
import com.conductor.generated.v2.model.CommentResponse;
import com.conductor.generated.v2.model.CommentWithRepliesResponse;
import com.conductor.generated.v2.model.CreateCommentRequest;
import com.conductor.service.CommentService;
import com.conductor.service.view.CommentReplyView;
import com.conductor.service.view.CommentWithRepliesView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Canonical v2 Work Item comments sub-resource
 * ({@code /api/v2/projects/{projectId}/work-items/{workItemId}/comments}). Successor to the legacy v1
 * {@code CommentController}; additive and behavior-preserving. All business logic lives in the shared
 * {@link CommentService} — this controller only maps the service's entity/domain-view return values to the v2
 * response DTOs. The service assembles those inside its own transaction (and the entity mapping reads only ids
 * off already-resolved references), so no {@code @Transactional} is needed here.
 *
 * <p>The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig} for controllers under the
 * {@code com.conductor.v2} package, so this class maps at bare paths via the generated interface.
 */
@RestController
public class WorkItemCommentsController implements WorkItemCommentsApi {

    private final CommentService commentService;

    public WorkItemCommentsController(CommentService commentService) {
        this.commentService = commentService;
    }

    @Override
    public ResponseEntity<CommentResponse> createWorkItemComment(String projectId, String workItemId,
                                                                 CreateCommentRequest request) {
        User caller = currentUser();
        Comment comment = commentService.createComment(
                projectId, workItemId, request.getDocumentId(), request.getContent(), request.getLineNumber(), caller);
        return ResponseEntity.status(201).body(toV2(comment));
    }

    @Override
    public ResponseEntity<List<CommentWithRepliesResponse>> listWorkItemComments(String projectId,
                                                                                 String workItemId,
                                                                                 Boolean resolved) {
        User caller = currentUser();
        List<CommentWithRepliesResponse> v2 = commentService.listComments(projectId, workItemId, resolved, caller)
                .stream()
                .map(WorkItemCommentsController::toV2WithReplies)
                .toList();
        return ResponseEntity.ok(v2);
    }

    @Override
    public ResponseEntity<CommentReplyResponse> addWorkItemCommentReply(String projectId, String workItemId,
                                                                        String commentId,
                                                                        AddCommentReplyRequest request) {
        User caller = currentUser();
        CommentReplyView reply = commentService.addReply(projectId, workItemId, commentId, request.getContent(), caller);
        return ResponseEntity.status(201).body(toV2Reply(reply));
    }

    @Override
    public ResponseEntity<CommentResponse> resolveWorkItemComment(String projectId, String workItemId,
                                                                  String commentId) {
        User caller = currentUser();
        Comment comment = commentService.resolveComment(projectId, workItemId, commentId, caller);
        return ResponseEntity.ok(toV2(comment));
    }

    @Override
    public ResponseEntity<Void> deleteWorkItemComment(String projectId, String workItemId, String commentId) {
        User caller = currentUser();
        commentService.deleteComment(projectId, workItemId, commentId, caller);
        return ResponseEntity.noContent().build();
    }

    private static CommentResponse toV2(Comment comment) {
        return new CommentResponse(
                comment.getId(), comment.getDocument().getId(), comment.getAuthor().getId(),
                comment.getContent(), comment.getCreatedAt())
                .lineNumber(comment.getLineNumber())
                .quotedText(comment.getQuotedText())
                .lineStale(comment.isLineStale())
                .resolvedAt(comment.getResolvedAt());
    }

    private static CommentWithRepliesResponse toV2WithReplies(CommentWithRepliesView v) {
        List<CommentReplyResponse> replies = v.replies().stream()
                .map(WorkItemCommentsController::toV2Reply)
                .toList();
        return new CommentWithRepliesResponse(
                v.id(), v.documentId(), v.authorId(), v.content(), v.createdAt(), replies)
                .authorName(v.authorName())
                .lineNumber(v.lineNumber())
                .quotedText(v.quotedText())
                .lineStale(v.lineStale())
                .documentName(v.documentName())
                .resolvedAt(v.resolvedAt());
    }

    private static CommentReplyResponse toV2Reply(CommentReplyView v) {
        return new CommentReplyResponse(
                v.id(), v.commentId(), v.authorId(), v.content(), v.createdAt())
                .authorName(v.authorName());
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
