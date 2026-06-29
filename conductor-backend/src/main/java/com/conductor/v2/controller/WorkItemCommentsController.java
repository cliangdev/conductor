package com.conductor.v2.controller;

import com.conductor.entity.User;
import com.conductor.generated.v2.api.WorkItemCommentsApi;
import com.conductor.generated.v2.model.AddCommentReplyRequest;
import com.conductor.generated.v2.model.CommentReplyResponse;
import com.conductor.generated.v2.model.CommentResponse;
import com.conductor.generated.v2.model.CommentWithRepliesResponse;
import com.conductor.generated.v2.model.CreateCommentRequest;
import com.conductor.service.CommentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Canonical v2 Work Item comments sub-resource
 * ({@code /api/v2/projects/{projectId}/work-items/{workItemId}/comments}). Successor to the legacy v1
 * {@code CommentController}; additive and behavior-preserving. All business logic lives in the shared
 * {@link CommentService} — this controller only translates v2 request/response DTOs to/from the v1 DTOs the
 * service speaks. The service returns DTOs (not entities), so no {@code @Transactional} is needed here.
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
        com.conductor.generated.model.CreateCommentRequest v1Request =
                new com.conductor.generated.model.CreateCommentRequest(
                        request.getDocumentId(), request.getContent(), request.getLineNumber());
        com.conductor.generated.model.CommentResponse response =
                commentService.createComment(projectId, workItemId, v1Request, caller);
        return ResponseEntity.status(201).body(toV2(response));
    }

    @Override
    public ResponseEntity<List<CommentWithRepliesResponse>> listWorkItemComments(String projectId,
                                                                                 String workItemId,
                                                                                 Boolean resolved) {
        User caller = currentUser();
        List<com.conductor.generated.model.CommentWithRepliesResponse> comments =
                commentService.listComments(projectId, workItemId, resolved, caller);
        List<CommentWithRepliesResponse> v2 = comments.stream()
                .map(WorkItemCommentsController::toV2WithReplies)
                .toList();
        return ResponseEntity.ok(v2);
    }

    @Override
    public ResponseEntity<CommentReplyResponse> addWorkItemCommentReply(String projectId, String workItemId,
                                                                        String commentId,
                                                                        AddCommentReplyRequest request) {
        User caller = currentUser();
        com.conductor.generated.model.AddCommentReplyRequest v1Request =
                new com.conductor.generated.model.AddCommentReplyRequest(request.getContent());
        com.conductor.generated.model.CommentReplyResponse response =
                commentService.addReply(projectId, workItemId, commentId, v1Request, caller);
        return ResponseEntity.status(201).body(toV2Reply(response));
    }

    @Override
    public ResponseEntity<CommentResponse> resolveWorkItemComment(String projectId, String workItemId,
                                                                  String commentId) {
        User caller = currentUser();
        com.conductor.generated.model.CommentResponse response =
                commentService.resolveComment(projectId, workItemId, commentId, caller);
        return ResponseEntity.ok(toV2(response));
    }

    @Override
    public ResponseEntity<Void> deleteWorkItemComment(String projectId, String workItemId, String commentId) {
        User caller = currentUser();
        commentService.deleteComment(projectId, workItemId, commentId, caller);
        return ResponseEntity.noContent().build();
    }

    private static CommentResponse toV2(com.conductor.generated.model.CommentResponse v1) {
        return new CommentResponse(
                v1.getId(), v1.getDocumentId(), v1.getAuthorId(), v1.getContent(), v1.getCreatedAt())
                .lineNumber(v1.getLineNumber())
                .quotedText(v1.getQuotedText())
                .lineStale(v1.getLineStale())
                .resolvedAt(v1.getResolvedAt());
    }

    private static CommentWithRepliesResponse toV2WithReplies(
            com.conductor.generated.model.CommentWithRepliesResponse v1) {
        List<CommentReplyResponse> replies = v1.getReplies().stream()
                .map(WorkItemCommentsController::toV2Reply)
                .toList();
        return new CommentWithRepliesResponse(
                v1.getId(), v1.getDocumentId(), v1.getAuthorId(), v1.getContent(), v1.getCreatedAt(), replies)
                .authorName(v1.getAuthorName())
                .lineNumber(v1.getLineNumber())
                .quotedText(v1.getQuotedText())
                .lineStale(v1.getLineStale())
                .documentName(v1.getDocumentName())
                .resolvedAt(v1.getResolvedAt());
    }

    private static CommentReplyResponse toV2Reply(com.conductor.generated.model.CommentReplyResponse v1) {
        return new CommentReplyResponse(
                v1.getId(), v1.getCommentId(), v1.getAuthorId(), v1.getContent(), v1.getCreatedAt())
                .authorName(v1.getAuthorName());
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
