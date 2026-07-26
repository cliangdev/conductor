package com.conductor.service;

import com.conductor.entity.DocComment;
import com.conductor.entity.DocCommentReply;
import com.conductor.entity.ProjectDoc;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.DocCommentRepository;
import com.conductor.repository.DocCommentReplyRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class DocCommentService {

    private final DocCommentRepository docCommentRepository;
    private final DocCommentReplyRepository docCommentReplyRepository;
    private final ProjectDocService projectDocService;
    private final ProjectSecurityService projectSecurityService;

    public DocCommentService(
            DocCommentRepository docCommentRepository,
            DocCommentReplyRepository docCommentReplyRepository,
            ProjectDocService projectDocService,
            ProjectSecurityService projectSecurityService) {
        this.docCommentRepository = docCommentRepository;
        this.docCommentReplyRepository = docCommentReplyRepository;
        this.projectDocService = projectDocService;
        this.projectSecurityService = projectSecurityService;
    }

    @Transactional
    public DocComment createComment(String projectId, String docId, DocActor actor, String content,
                                    Integer lineNumber, String quotedText) {
        ProjectDoc doc = projectDocService.getDoc(projectId, docId);

        DocComment comment = new DocComment();
        comment.setDoc(doc);
        comment.setAuthor(actor.user());
        comment.setAuthorLabel(actor.label());
        comment.setContent(content);
        comment.setLineNumber(lineNumber);
        comment.setQuotedText(quotedText);

        return docCommentRepository.save(comment);
    }

    @Transactional(readOnly = true)
    public List<DocComment> listComments(String projectId, String docId) {
        projectDocService.getDoc(projectId, docId);
        return docCommentRepository.findByDocIdOrderByCreatedAtAsc(docId);
    }

    /**
     * Author-or-admin for a human caller. A machine actor may delete only comments a machine actor
     * left: a run-scoped token is short-lived and non-attributable, and an agent's job is to answer a
     * reviewer's comment, not to remove it.
     */
    @Transactional
    public void deleteComment(String projectId, String docId, String commentId, DocActor actor) {
        DocComment comment = getComment(projectId, docId, commentId);

        boolean allowed;
        if (actor.user() != null) {
            boolean isAuthor = comment.getAuthor() != null
                    && comment.getAuthor().getId().equals(actor.userId());
            allowed = isAuthor || projectSecurityService.isAdminOrCreator(projectId, actor.userId());
        } else {
            allowed = comment.getAuthor() == null;
        }

        if (!allowed) {
            throw new ForbiddenException("Only the comment author or a project admin can delete this comment");
        }

        docCommentRepository.delete(comment);
    }

    @Transactional
    public DocCommentReply addReply(String projectId, String docId, String commentId, DocActor actor, String content) {
        DocComment comment = getComment(projectId, docId, commentId);

        DocCommentReply reply = new DocCommentReply();
        reply.setComment(comment);
        reply.setAuthor(actor.user());
        reply.setAuthorLabel(actor.label());
        reply.setContent(content);

        return docCommentReplyRepository.save(reply);
    }

    @Transactional
    public DocComment resolveThread(String projectId, String docId, String commentId, DocActor actor) {
        // Authors fetch-joined so the caller can map this to a response after the transaction closes.
        DocComment comment = docCommentRepository.findByIdWithAuthors(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found: " + commentId));
        assertBelongsToDoc(comment, projectId, docId);

        comment.setResolvedAt(OffsetDateTime.now());
        comment.setResolvedBy(actor.user());
        return docCommentRepository.save(comment);
    }

    @Transactional
    public void markCommentsStale(String docId) {
        List<DocComment> unresolvedComments = docCommentRepository.findByDocIdAndResolvedAtIsNull(docId);
        for (DocComment comment : unresolvedComments) {
            comment.setLineStale(true);
        }
        docCommentRepository.saveAll(unresolvedComments);
    }

    private DocComment getComment(String projectId, String docId, String commentId) {
        DocComment comment = docCommentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found: " + commentId));
        assertBelongsToDoc(comment, projectId, docId);
        return comment;
    }

    /**
     * A comment id in the URL is not proof of access — see {@link ProjectDocService#getDoc}. Resolving
     * the doc through that gate is what ties the comment to the caller's project.
     */
    private void assertBelongsToDoc(DocComment comment, String projectId, String docId) {
        if (!comment.getDoc().getId().equals(docId)) {
            throw new EntityNotFoundException("Comment not found: " + comment.getId());
        }
        projectDocService.getDoc(projectId, docId);
    }
}
