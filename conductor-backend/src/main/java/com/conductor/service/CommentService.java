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
import com.conductor.repository.CommentReplyRepository;
import com.conductor.repository.CommentRepository;
import com.conductor.repository.DocumentRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.service.view.CommentAnchor;
import com.conductor.service.view.CommentReplyView;
import com.conductor.service.view.CommentWithRepliesView;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalBus;
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
    private final SignalBus signalBus;
    private final ProjectRepository projectRepository;

    public CommentService(
            CommentRepository commentRepository,
            CommentReplyRepository commentReplyRepository,
            WorkItemRepository workItemRepository,
            DocumentRepository documentRepository,
            ProjectMemberRepository projectMemberRepository,
            ProjectSecurityService projectSecurityService,
            StorageService storageService,
            SignalBus signalBus,
            ProjectRepository projectRepository) {
        this.commentRepository = commentRepository;
        this.commentReplyRepository = commentReplyRepository;
        this.workItemRepository = workItemRepository;
        this.documentRepository = documentRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.projectSecurityService = projectSecurityService;
        this.storageService = storageService;
        this.signalBus = signalBus;
        this.projectRepository = projectRepository;
    }

    @Transactional
    public Comment createComment(String projectId, String workItemId, String documentId, String content,
                                 Integer lineNumber, CommentAnchor anchor, User caller) {
        verifyMembership(projectId, caller.getId());

        WorkItem workItem = findWorkItemInProject(projectId, workItemId);

        Document document = documentRepository.findByIdAndWorkItemId(documentId, workItemId)
                .orElseThrow(() -> new EntityNotFoundException("Document not found in Work Item"));

        // HTML documents anchor to a text selection; everything else anchors to a markdown line. A request
        // for the other kind is rejected rather than guessed at, so an old client never silently loses its anchor.
        boolean html = DocumentContent.isHtml(document);
        if (html) {
            if (lineNumber != null) {
                throw new BusinessException("lineNumber is not supported on HTML documents; send an anchor");
            }
            if (anchor == null || anchor.exact() == null || anchor.exact().isEmpty()) {
                throw new BusinessException("anchor is required on HTML documents");
            }
        } else {
            if (anchor != null) {
                throw new BusinessException("anchor is only supported on HTML documents; send a lineNumber");
            }
            if (lineNumber == null) {
                throw new BusinessException("lineNumber is required");
            }
        }

        Comment comment = new Comment();
        comment.setWorkItem(workItem);
        comment.setDocument(document);
        comment.setAuthor(caller);
        comment.setContent(content);
        if (html) {
            // line_number keeps its default of 0; quotedText mirrors the selection so existing readers keep working.
            comment.setAnchorExact(anchor.exact());
            comment.setAnchorPrefix(anchor.prefix());
            comment.setAnchorSuffix(anchor.suffix());
            comment.setAnchorSectionId(anchor.sectionId());
            comment.setAnchorSectionTitle(anchor.sectionTitle());
            comment.setQuotedText(anchor.exact());
        } else {
            comment.setLineNumber(lineNumber);
            comment.setQuotedText(extractLineFromDocument(document, lineNumber));
        }
        comment.setDocumentHash(document.getContentHash());

        commentRepository.save(comment);

        String excerpt = buildExcerpt(content);
        String authorLabel = caller.getName() != null ? caller.getName() : caller.getEmail();
        signalBus.publish(Signal.of(
                SignalTypes.CONDUCTOR_WORK_ITEM_COMMENT_ADDED,
                workItem.getProject().getId(),
                workItem.getId(),
                Instant.now(),
                Map.of(
                        "workItemId", workItem.getId(),
                        "workItemTitle", workItem.getTitle(),
                        "commentAuthor", authorLabel,
                        "excerpt", excerpt
                ),
                new SignalOrigin("comment", workItem.getId())));

        return comment;
    }

    String extractLineFromDocument(Document document, int lineNumber) {
        // The text is already on the row (written alongside the stored object), so there is no
        // storage round trip to find one line.
        String content = document.getContent();
        if (content == null) {
            return "";
        }
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
        findWorkItemInProject(projectId, workItemId);

        List<Comment> comments;
        if (resolved == null) {
            comments = commentRepository.findListByWorkItemId(workItemId);
        } else if (resolved) {
            comments = commentRepository.findListByWorkItemIdResolved(workItemId);
        } else {
            comments = commentRepository.findListByWorkItemIdUnresolved(workItemId);
        }
        if (comments.isEmpty()) {
            return List.of();
        }

        // Constant query count: replies for every comment in one query (authors fetch-joined), and the
        // document filenames in one query that never touches documents.content.
        Map<String, List<CommentReplyView>> repliesByComment = new java.util.HashMap<>();
        for (CommentReply reply : commentReplyRepository.findAllByCommentIdIn(
                comments.stream().map(Comment::getId).toList())) {
            repliesByComment.computeIfAbsent(reply.getComment().getId(), id -> new java.util.ArrayList<>())
                    .add(toCommentReplyView(reply));
        }
        Map<String, String> filenames = new java.util.HashMap<>();
        Map<String, String> contentHashes = new java.util.HashMap<>();
        for (Object[] row : commentRepository.findDocumentFilenames(
                comments.stream().map(c -> c.getDocument().getId()).distinct().toList())) {
            filenames.put((String) row[0], (String) row[1]);
            contentHashes.put((String) row[0], (String) row[2]);
        }

        return comments.stream()
                .map(comment -> {
                    List<CommentReplyView> replies = repliesByComment.getOrDefault(comment.getId(), List.of());

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
                            toAnchor(comment),
                            comment.isOutdatedAgainst(contentHashes.get(comment.getDocument().getId())),
                            filenames.get(comment.getDocument().getId()),
                            comment.getResolvedAt(),
                            replies);
                })
                .toList();
    }

    @Transactional
    public CommentReplyView addReply(String projectId, String workItemId, String commentId, String content, User caller) {
        verifyMembership(projectId, caller.getId());
        findWorkItemInProject(projectId, workItemId);

        Comment comment = findCommentInWorkItem(workItemId, commentId);

        CommentReply reply = new CommentReply();
        reply.setComment(comment);
        reply.setAuthor(caller);
        reply.setContent(content);

        commentReplyRepository.save(reply);

        WorkItem workItem = comment.getWorkItem();
        String excerpt = buildExcerpt(content);
        String authorLabel = caller.getName() != null ? caller.getName() : caller.getEmail();
        signalBus.publish(Signal.of(
                SignalTypes.CONDUCTOR_WORK_ITEM_COMMENT_REPLIED,
                workItem.getProject().getId(),
                workItem.getId(),
                Instant.now(),
                Map.of(
                        "workItemId", workItem.getId(),
                        "workItemTitle", workItem.getTitle(),
                        "commentAuthor", authorLabel,
                        "excerpt", excerpt
                ),
                new SignalOrigin("comment", workItem.getId())));

        return toCommentReplyView(reply);
    }

    @Transactional
    public Comment resolveComment(String projectId, String workItemId, String commentId, User caller) {
        verifyMembership(projectId, caller.getId());
        findWorkItemInProject(projectId, workItemId);

        Comment comment = findCommentInWorkItem(workItemId, commentId);
        comment.setResolvedAt(OffsetDateTime.now());
        comment.setResolvedBy(caller);

        commentRepository.save(comment);
        return comment;
    }

    @Transactional
    public void deleteComment(String projectId, String workItemId, String commentId, User caller) {
        verifyMembership(projectId, caller.getId());
        findWorkItemInProject(projectId, workItemId);
        Comment comment = findCommentInWorkItem(workItemId, commentId);

        boolean isAuthor = comment.getAuthor().getId().equals(caller.getId());
        boolean isAdmin = projectSecurityService.isAdminOrCreator(projectId, caller.getId());

        if (!isAuthor && !isAdmin) {
            throw new ForbiddenException("Only the comment author or a project admin can delete this comment");
        }

        commentRepository.delete(comment);
    }

    /** The comment's text-selection anchor, or null for a line comment. */
    public static CommentAnchor toAnchor(Comment comment) {
        if (comment.getAnchorExact() == null) {
            return null;
        }
        return new CommentAnchor(
                comment.getAnchorExact(),
                comment.getAnchorPrefix(),
                comment.getAnchorSuffix(),
                comment.getAnchorSectionId(),
                comment.getAnchorSectionTitle());
    }

    /**
     * Whether the document changed after this comment was written. Reads the document's current hash on its own
     * (the hash column only, never {@code content}); either hash missing reads as not outdated.
     */
    @Transactional(readOnly = true)
    public boolean isOutdated(Comment comment) {
        if (comment.getDocumentHash() == null) {
            return false;
        }
        return comment.isOutdatedAgainst(documentRepository.findContentHashById(comment.getDocument().getId()));
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
        if (!projectRepository.existsById(projectId)) {
            throw new EntityNotFoundException("Project not found");
        }
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
