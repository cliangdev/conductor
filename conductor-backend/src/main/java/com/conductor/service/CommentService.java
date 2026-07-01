package com.conductor.service;

import com.conductor.entity.Comment;
import com.conductor.entity.CommentReply;
import com.conductor.entity.Document;
import com.conductor.entity.WorkItem;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ForbiddenException;
import com.conductor.notification.EventType;
import com.conductor.notification.NotificationDispatcher;
import com.conductor.notification.NotificationEvent;
import com.conductor.repository.CommentReplyRepository;
import com.conductor.repository.CommentRepository;
import com.conductor.repository.DocumentRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.service.view.CommentReplyView;
import com.conductor.service.view.CommentWithRepliesView;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
public class CommentService {

    private final CommentRepository commentRepository;
    private final CommentReplyRepository commentReplyRepository;
    private final WorkItemRepository workItemRepository;
    private final DocumentRepository documentRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final ProjectSecurityService projectSecurityService;
    private final StorageService storageService;
    private final NotificationDispatcher notificationDispatcher;
    private final ProjectRepository projectRepository;

    public CommentService(
            CommentRepository commentRepository,
            CommentReplyRepository commentReplyRepository,
            WorkItemRepository workItemRepository,
            DocumentRepository documentRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectSecurityService projectSecurityService,
            StorageService storageService,
            NotificationDispatcher notificationDispatcher,
            ProjectRepository projectRepository) {
        this.commentRepository = commentRepository;
        this.commentReplyRepository = commentReplyRepository;
        this.workItemRepository = workItemRepository;
        this.documentRepository = documentRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectSecurityService = projectSecurityService;
        this.storageService = storageService;
        this.notificationDispatcher = notificationDispatcher;
        this.projectRepository = projectRepository;
    }

    @Transactional
    public Comment createComment(String projectId, String workItemId, String documentId, String content,
                                 Integer lineNumber, User caller) {
        if (lineNumber == null) {
            throw new BusinessException("lineNumber is required");
        }

        verifyMembership(projectId, caller.getId());

        WorkItem workItem = findWorkItemInProject(projectId, workItemId);

        Document document = documentRepository.findByIdAndWorkItemId(documentId, workItemId)
                .orElseThrow(() -> new EntityNotFoundException("Document not found in Work Item"));

        String quotedText = extractLineFromDocument(document, lineNumber);

        Comment comment = new Comment();
        comment.setWorkItem(workItem);
        comment.setDocument(document);
        comment.setAuthor(caller);
        comment.setContent(content);
        comment.setLineNumber(lineNumber);
        comment.setQuotedText(quotedText);

        commentRepository.save(comment);

        String excerpt = buildExcerpt(content);
        String authorLabel = caller.getName() != null ? caller.getName() : caller.getEmail();
        notificationDispatcher.dispatch(NotificationEvent.of(
                EventType.COMMENT_ADDED,
                workItem.getProject().getId(),
                Map.of(
                        "issueId", workItem.getId(),
                        "issueTitle", workItem.getTitle(),
                        "commentAuthor", authorLabel,
                        "excerpt", excerpt
                )));

        return comment;
    }

    String extractLineFromDocument(Document document, int lineNumber) {
        if (document.getStoragePath() == null) {
            return "";
        }
        byte[] bytes = storageService.download(document.getStoragePath());
        String content = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = content.split("\n", -1);
        int index = lineNumber - 1;
        if (index < 0 || index >= lines.length) {
            return "";
        }
        return lines[index];
    }

    @Transactional(readOnly = true)
    public List<CommentWithRepliesView> listComments(String projectId, String workItemId, Boolean resolved, User caller) {
        verifyReadAccess(projectId, caller.getId());

        List<Comment> comments;
        if (resolved == null) {
            comments = commentRepository.findAllByWorkItemId(workItemId);
        } else if (resolved) {
            comments = commentRepository.findAllByWorkItemIdAndResolvedAtIsNotNull(workItemId);
        } else {
            comments = commentRepository.findAllByWorkItemIdAndResolvedAtIsNull(workItemId);
        }

        return comments.stream()
                .map(comment -> {
                    List<CommentReplyView> replies = commentReplyRepository.findAllByCommentId(comment.getId())
                            .stream()
                            .map(this::toCommentReplyView)
                            .toList();

                    return new CommentWithRepliesView(
                            comment.getId(),
                            comment.getDocument().getId(),
                            comment.getAuthor().getId(),
                            comment.getContent(),
                            comment.getCreatedAt(),
                            comment.getAuthor().getName(),
                            comment.getLineNumber(),
                            comment.getQuotedText(),
                            comment.isLineStale(),
                            comment.getDocument().getFilename(),
                            comment.getResolvedAt(),
                            replies);
                })
                .toList();
    }

    @Transactional
    public CommentReplyView addReply(String projectId, String workItemId, String commentId, String content, User caller) {
        verifyMembership(projectId, caller.getId());

        Comment comment = findCommentInWorkItem(workItemId, commentId);

        CommentReply reply = new CommentReply();
        reply.setComment(comment);
        reply.setAuthor(caller);
        reply.setContent(content);

        commentReplyRepository.save(reply);

        WorkItem workItem = comment.getWorkItem();
        String excerpt = buildExcerpt(content);
        String authorLabel = caller.getName() != null ? caller.getName() : caller.getEmail();
        notificationDispatcher.dispatch(NotificationEvent.of(
                EventType.COMMENT_REPLY,
                workItem.getProject().getId(),
                Map.of(
                        "issueId", workItem.getId(),
                        "issueTitle", workItem.getTitle(),
                        "commentAuthor", authorLabel,
                        "excerpt", excerpt
                )));

        return toCommentReplyView(reply);
    }

    @Transactional
    public Comment resolveComment(String projectId, String workItemId, String commentId, User caller) {
        verifyMembership(projectId, caller.getId());

        Comment comment = findCommentInWorkItem(workItemId, commentId);
        comment.setResolvedAt(OffsetDateTime.now());
        comment.setResolvedBy(caller);

        commentRepository.save(comment);
        return comment;
    }

    @Transactional
    public void deleteComment(String projectId, String workItemId, String commentId, User caller) {
        Comment comment = findCommentInWorkItem(workItemId, commentId);

        boolean isAuthor = comment.getAuthor().getId().equals(caller.getId());
        boolean isAdmin = projectSecurityService.isAdminOrCreator(projectId, caller.getId());

        if (!isAuthor && !isAdmin) {
            throw new ForbiddenException("Only the comment author or a project admin can delete this comment");
        }

        commentRepository.delete(comment);
    }

    static String buildExcerpt(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() > 100) {
            return content.substring(0, 100) + "...";
        }
        return content;
    }

    private void verifyMembership(String projectId, String userId) {
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new ForbiddenException("You must be a project member to perform this action");
        }
    }

    private void verifyReadAccess(String projectId, String userId) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));
        if (!projectMemberRepository.existsByProjectIdAndUserId(projectId, userId)) {
            throw new ForbiddenException("You do not have access to this project");
        }
    }

    private WorkItem findWorkItemInProject(String projectId, String workItemId) {
        WorkItem workItem = workItemRepository.findById(workItemId)
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
        if (!workItem.getProject().getId().equals(projectId)) {
            throw new EntityNotFoundException("Work Item not found");
        }
        return workItem;
    }

    private Comment findCommentInWorkItem(String workItemId, String commentId) {
        Comment comment = commentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found"));
        if (!comment.getWorkItem().getId().equals(workItemId)) {
            throw new EntityNotFoundException("Comment not found");
        }
        return comment;
    }

    private CommentReplyView toCommentReplyView(CommentReply reply) {
        return new CommentReplyView(
                reply.getId(),
                reply.getComment().getId(),
                reply.getAuthor().getId(),
                reply.getContent(),
                reply.getCreatedAt(),
                reply.getAuthor().getName());
    }
}
