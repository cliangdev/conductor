package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.generated.api.CommentsApi;
import com.conductor.generated.model.AddCommentReplyRequest;
import com.conductor.generated.model.CommentReplyResponse;
import com.conductor.generated.model.CommentResponse;
import com.conductor.generated.model.CommentWithRepliesResponse;
import com.conductor.generated.model.CreateCommentRequest;
import com.conductor.service.CommentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class CommentController implements CommentsApi {

    private final CommentService commentService;

    public CommentController(CommentService commentService) {
        this.commentService = commentService;
    }

    @Override
    public ResponseEntity<CommentResponse> createComment(String projectId, String issueId, CreateCommentRequest createCommentRequest) {
        User caller = currentUser();
        CommentResponse response = commentService.createComment(projectId, issueId, createCommentRequest, caller);
        return ResponseEntity.status(201).body(response);
    }

    @Override
    public ResponseEntity<List<CommentWithRepliesResponse>> listComments(String projectId, String issueId) {
        User caller = currentUser();
        List<CommentWithRepliesResponse> comments = commentService.listComments(projectId, issueId, caller);
        return ResponseEntity.ok(comments);
    }

    @Override
    public ResponseEntity<CommentReplyResponse> addCommentReply(String projectId, String issueId, String commentId, AddCommentReplyRequest addCommentReplyRequest) {
        User caller = currentUser();
        CommentReplyResponse response = commentService.addReply(projectId, issueId, commentId, addCommentReplyRequest, caller);
        return ResponseEntity.status(201).body(response);
    }

    @Override
    public ResponseEntity<CommentResponse> resolveComment(String projectId, String issueId, String commentId) {
        User caller = currentUser();
        CommentResponse response = commentService.resolveComment(projectId, issueId, commentId, caller);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Void> deleteComment(String projectId, String issueId, String commentId) {
        User caller = currentUser();
        commentService.deleteComment(projectId, issueId, commentId, caller);
        return ResponseEntity.noContent().build();
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
