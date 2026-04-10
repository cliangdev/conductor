package com.conductor.service;

import com.conductor.entity.Comment;
import com.conductor.entity.CommentReply;
import com.conductor.entity.Document;
import com.conductor.entity.Issue;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.model.AddCommentReplyRequest;
import com.conductor.generated.model.CommentReplyResponse;
import com.conductor.generated.model.CommentResponse;
import com.conductor.generated.model.CommentWithRepliesResponse;
import com.conductor.generated.model.CreateCommentRequest;
import com.conductor.repository.CommentReplyRepository;
import com.conductor.repository.CommentRepository;
import com.conductor.repository.DocumentRepository;
import com.conductor.repository.IssueRepository;
import com.conductor.repository.ProjectMemberRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class CommentService {

    private final CommentRepository commentRepository;
    private final CommentReplyRepository commentReplyRepository;
    private final IssueRepository issueRepository;
    private final DocumentRepository documentRepository;
    private final ProjectMemberRepository projectMemberRepository;

    public CommentService(
            CommentRepository commentRepository,
            CommentReplyRepository commentReplyRepository,
            IssueRepository issueRepository,
            DocumentRepository documentRepository,
            ProjectMemberRepository projectMemberRepository) {
        this.commentRepository = commentRepository;
        this.commentReplyRepository = commentReplyRepository;
        this.issueRepository = issueRepository;
        this.documentRepository = documentRepository;
        this.projectMemberRepository = projectMemberRepository;
    }

    @Transactional
    public CommentResponse createComment(String projectId, String issueId, CreateCommentRequest request, User caller) {
        validateAnchor(request.getLineNumber(), request.getSelectionStart(), request.getSelectionLength());

        Issue issue = findIssueInProject(projectId, issueId);

        Document document = documentRepository.findByIdAndIssueId(request.getDocumentId(), issueId)
                .orElseThrow(() -> new EntityNotFoundException("Document not found in issue"));

        Comment comment = new Comment();
        comment.setIssue(issue);
        comment.setDocument(document);
        comment.setAuthor(caller);
        comment.setContent(request.getContent());
        comment.setLineNumber(request.getLineNumber());
        comment.setSelectionStart(request.getSelectionStart());
        comment.setSelectionLength(request.getSelectionLength());

        commentRepository.save(comment);
        return toCommentResponse(comment);
    }

    @Transactional(readOnly = true)
    public List<CommentWithRepliesResponse> listComments(String projectId, String issueId, User caller) {
        verifyMembership(projectId, caller.getId());

        List<Comment> comments = commentRepository.findAllByIssueId(issueId);

        return comments.stream()
                .map(comment -> {
                    List<CommentReplyResponse> replies = commentReplyRepository.findAllByCommentId(comment.getId())
                            .stream()
                            .map(this::toCommentReplyResponse)
                            .toList();

                    CommentWithRepliesResponse response = new CommentWithRepliesResponse(
                            comment.getId(),
                            comment.getDocument().getId(),
                            comment.getAuthor().getId(),
                            comment.getContent(),
                            comment.getCreatedAt(),
                            replies);

                    response.setAuthorName(comment.getAuthor().getName());
                    response.setLineNumber(comment.getLineNumber());
                    response.setSelectionStart(comment.getSelectionStart());
                    response.setSelectionLength(comment.getSelectionLength());
                    response.setResolvedAt(comment.getResolvedAt());

                    return response;
                })
                .toList();
    }

    @Transactional
    public CommentReplyResponse addReply(String projectId, String issueId, String commentId, AddCommentReplyRequest request, User caller) {
        verifyMembership(projectId, caller.getId());

        Comment comment = findCommentInIssue(issueId, commentId);

        CommentReply reply = new CommentReply();
        reply.setComment(comment);
        reply.setAuthor(caller);
        reply.setContent(request.getContent());

        commentReplyRepository.save(reply);
        return toCommentReplyResponse(reply);
    }

    @Transactional
    public CommentResponse resolveComment(String projectId, String issueId, String commentId, User caller) {
        verifyMembership(projectId, caller.getId());

        Comment comment = findCommentInIssue(issueId, commentId);
        comment.setResolvedAt(OffsetDateTime.now());
        comment.setResolvedBy(caller);

        commentRepository.save(comment);
        return toCommentResponse(comment);
    }

    @Transactional
    public void deleteComment(String projectId, String issueId, String commentId, User caller) {
        Comment comment = findCommentInIssue(issueId, commentId);

        boolean isAuthor = comment.getAuthor().getId().equals(caller.getId());
        boolean isAdmin = projectMemberRepository.findByProjectIdAndUserId(projectId, caller.getId())
                .map(member -> member.getRole() == MemberRole.ADMIN || member.getRole() == MemberRole.CREATOR)
                .orElse(false);

        if (!isAuthor && !isAdmin) {
            throw new ForbiddenException("Only the comment author or a project admin can delete this comment");
        }

        commentRepository.delete(comment);
    }

    private void validateAnchor(Integer lineNumber, Integer selectionStart, Integer selectionLength) {
        boolean hasLineNumber = lineNumber != null;
        boolean hasSelection = selectionStart != null && selectionLength != null;
        if (!hasLineNumber && !hasSelection) {
            throw new BusinessException("Comment must have either a lineNumber or selectionStart+selectionLength anchor");
        }
    }

    private void verifyMembership(String projectId, String userId) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new EntityNotFoundException("Project not found");
        }
    }

    private Issue findIssueInProject(String projectId, String issueId) {
        Issue issue = issueRepository.findById(issueId)
                .orElseThrow(() -> new EntityNotFoundException("Issue not found"));
        if (!issue.getProject().getId().equals(projectId)) {
            throw new EntityNotFoundException("Issue not found");
        }
        return issue;
    }

    private Comment findCommentInIssue(String issueId, String commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found"));
        if (!comment.getIssue().getId().equals(issueId)) {
            throw new EntityNotFoundException("Comment not found");
        }
        return comment;
    }

    private CommentResponse toCommentResponse(Comment comment) {
        CommentResponse response = new CommentResponse(
                comment.getId(),
                comment.getDocument().getId(),
                comment.getAuthor().getId(),
                comment.getContent(),
                comment.getCreatedAt());

        response.setLineNumber(comment.getLineNumber());
        response.setSelectionStart(comment.getSelectionStart());
        response.setSelectionLength(comment.getSelectionLength());
        response.setResolvedAt(comment.getResolvedAt());

        return response;
    }

    private CommentReplyResponse toCommentReplyResponse(CommentReply reply) {
        CommentReplyResponse response = new CommentReplyResponse(
                reply.getId(),
                reply.getComment().getId(),
                reply.getAuthor().getId(),
                reply.getContent(),
                reply.getCreatedAt());

        response.setAuthorName(reply.getAuthor().getName());

        return response;
    }
}
