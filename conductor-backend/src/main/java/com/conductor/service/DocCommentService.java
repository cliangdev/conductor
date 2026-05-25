package com.conductor.service;

import com.conductor.entity.DocComment;
import com.conductor.entity.DocCommentReply;
import com.conductor.entity.MemberRole;
import com.conductor.entity.ProjectDoc;
import com.conductor.entity.User;
import com.conductor.exception.ForbiddenException;
import com.conductor.repository.DocCommentRepository;
import com.conductor.repository.DocCommentReplyRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class DocCommentService {

    private final DocCommentRepository docCommentRepository;
    private final DocCommentReplyRepository docCommentReplyRepository;
    private final UserRepository userRepository;
    private final ProjectDocService projectDocService;
    private final ProjectMemberRepository projectMemberRepository;

    public DocCommentService(
            DocCommentRepository docCommentRepository,
            DocCommentReplyRepository docCommentReplyRepository,
            UserRepository userRepository,
            ProjectDocService projectDocService,
            ProjectMemberRepository projectMemberRepository) {
        this.docCommentRepository = docCommentRepository;
        this.docCommentReplyRepository = docCommentReplyRepository;
        this.userRepository = userRepository;
        this.projectDocService = projectDocService;
        this.projectMemberRepository = projectMemberRepository;
    }

    @Transactional
    public DocComment createComment(String docId, String userId, String content, Integer lineNumber, String quotedText) {
        ProjectDoc doc = projectDocService.getDoc(docId);

        User author = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));

        DocComment comment = new DocComment();
        comment.setDoc(doc);
        comment.setAuthor(author);
        comment.setContent(content);
        comment.setLineNumber(lineNumber);
        comment.setQuotedText(quotedText);

        return docCommentRepository.save(comment);
    }

    @Transactional(readOnly = true)
    public List<DocComment> listComments(String docId) {
        return docCommentRepository.findByDocIdOrderByCreatedAtAsc(docId);
    }

    @Transactional
    public void deleteComment(String commentId, String userId) {
        DocComment comment = docCommentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found: " + commentId));

        boolean isAuthor = comment.getAuthor().getId().equals(userId);

        String projectId = comment.getDoc().getProject().getId();
        boolean isAdmin = projectMemberRepository.findByProjectIdAndUserId(projectId, userId)
                .map(member -> member.getRole() == MemberRole.ADMIN || member.getRole() == MemberRole.CREATOR)
                .orElse(false);

        if (!isAuthor && !isAdmin) {
            throw new ForbiddenException("Only the comment author or a project admin can delete this comment");
        }

        docCommentRepository.delete(comment);
    }

    @Transactional
    public DocCommentReply addReply(String commentId, String userId, String content) {
        DocComment comment = docCommentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found: " + commentId));

        User author = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));

        DocCommentReply reply = new DocCommentReply();
        reply.setComment(comment);
        reply.setAuthor(author);
        reply.setContent(content);

        return docCommentReplyRepository.save(reply);
    }

    @Transactional
    public DocComment resolveThread(String commentId) {
        DocComment comment = docCommentRepository.findById(commentId)
                .orElseThrow(() -> new EntityNotFoundException("Comment not found: " + commentId));

        comment.setResolvedAt(OffsetDateTime.now());
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
}
