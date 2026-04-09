package com.conductor.service;

import com.conductor.entity.Comment;
import com.conductor.entity.CommentReply;
import com.conductor.entity.Document;
import com.conductor.entity.Issue;
import com.conductor.entity.IssueStatus;
import com.conductor.entity.IssueType;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private CommentReplyRepository commentReplyRepository;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private ProjectMemberRepository projectMemberRepository;

    @InjectMocks
    private CommentService commentService;

    private User author;
    private User otherUser;
    private Project project;
    private Issue issue;
    private Document document;
    private Comment comment;

    @BeforeEach
    void setUp() {
        author = new User();
        author.setId("user-1");
        author.setEmail("author@example.com");
        author.setName("Author Name");

        otherUser = new User();
        otherUser.setId("user-2");
        otherUser.setEmail("other@example.com");
        otherUser.setName("Other User");

        project = new Project();
        project.setId("proj-1");
        project.setName("Test Project");
        project.setCreatedBy(author);
        project.setCreatedAt(OffsetDateTime.now());
        project.setUpdatedAt(OffsetDateTime.now());

        issue = new Issue();
        issue.setId("issue-1");
        issue.setProject(project);
        issue.setType(IssueType.PRD);
        issue.setTitle("Test Issue");
        issue.setStatus(IssueStatus.DRAFT);
        issue.setCreatedBy(author);
        issue.setCreatedAt(OffsetDateTime.now());
        issue.setUpdatedAt(OffsetDateTime.now());

        document = new Document();
        document.setId("doc-1");
        document.setIssue(issue);
        document.setFilename("spec.md");
        document.setContentType("text/markdown");
        document.setCreatedAt(OffsetDateTime.now());
        document.setUpdatedAt(OffsetDateTime.now());

        comment = new Comment();
        comment.setId("comment-1");
        comment.setIssue(issue);
        comment.setDocument(document);
        comment.setAuthor(author);
        comment.setContent("This needs work");
        comment.setLineNumber(42);
        comment.setCreatedAt(OffsetDateTime.now());
        comment.setUpdatedAt(OffsetDateTime.now());
    }

    @Test
    void createCommentWithLineNumberSucceeds() {
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(issue));
        when(documentRepository.findByIdAndIssueId("doc-1", "issue-1")).thenReturn(Optional.of(document));
        when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            if (c.getId() == null) c.setId("new-comment-id");
            if (c.getCreatedAt() == null) c.setCreatedAt(OffsetDateTime.now());
            if (c.getUpdatedAt() == null) c.setUpdatedAt(OffsetDateTime.now());
            return c;
        });

        CreateCommentRequest request = new CreateCommentRequest("doc-1", "Looks good");
        request.setLineNumber(10);

        CommentResponse response = commentService.createComment("proj-1", "issue-1", request, author);

        assertThat(response.getLineNumber()).isEqualTo(10);
        assertThat(response.getContent()).isEqualTo("Looks good");
        assertThat(response.getAuthorId()).isEqualTo("user-1");
    }

    @Test
    void createCommentWithSelectionAnchorSucceeds() {
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(issue));
        when(documentRepository.findByIdAndIssueId("doc-1", "issue-1")).thenReturn(Optional.of(document));
        when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            if (c.getId() == null) c.setId("new-comment-id");
            if (c.getCreatedAt() == null) c.setCreatedAt(OffsetDateTime.now());
            if (c.getUpdatedAt() == null) c.setUpdatedAt(OffsetDateTime.now());
            return c;
        });

        CreateCommentRequest request = new CreateCommentRequest("doc-1", "Selection comment");
        request.setSelectionStart(100);
        request.setSelectionLength(50);

        CommentResponse response = commentService.createComment("proj-1", "issue-1", request, author);

        assertThat(response.getSelectionStart()).isEqualTo(100);
        assertThat(response.getSelectionLength()).isEqualTo(50);
        assertThat(response.getLineNumber()).isNull();
    }

    @Test
    void createCommentWithNoAnchorThrowsBusinessException() {
        CreateCommentRequest request = new CreateCommentRequest("doc-1", "Missing anchor");

        assertThatThrownBy(() -> commentService.createComment("proj-1", "issue-1", request, author))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("anchor");
    }

    @Test
    void createCommentWithPartialSelectionAnchorThrowsBusinessException() {
        CreateCommentRequest request = new CreateCommentRequest("doc-1", "Partial anchor");
        request.setSelectionStart(100);

        assertThatThrownBy(() -> commentService.createComment("proj-1", "issue-1", request, author))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void listCommentsReturnsRepliesNested() {
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-1", "user-1")).thenReturn(true);
        when(commentRepository.findAllByIssueId("issue-1")).thenReturn(List.of(comment));

        CommentReply reply = new CommentReply();
        reply.setId("reply-1");
        reply.setComment(comment);
        reply.setAuthor(otherUser);
        reply.setContent("Agreed");
        reply.setCreatedAt(OffsetDateTime.now());

        when(commentReplyRepository.findAllByCommentId("comment-1")).thenReturn(List.of(reply));

        List<CommentWithRepliesResponse> results = commentService.listComments("proj-1", "issue-1", author);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo("comment-1");
        assertThat(results.get(0).getReplies()).hasSize(1);
        assertThat(results.get(0).getReplies().get(0).getId()).isEqualTo("reply-1");
        assertThat(results.get(0).getReplies().get(0).getContent()).isEqualTo("Agreed");
    }

    @Test
    void addReplyAppearsInSubsequentList() {
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-1", "user-1")).thenReturn(true);
        when(commentRepository.findById("comment-1")).thenReturn(Optional.of(comment));
        when(commentReplyRepository.save(any(CommentReply.class))).thenAnswer(inv -> {
            CommentReply r = inv.getArgument(0);
            if (r.getId() == null) r.setId("new-reply-id");
            if (r.getCreatedAt() == null) r.setCreatedAt(OffsetDateTime.now());
            return r;
        });

        AddCommentReplyRequest request = new AddCommentReplyRequest("Great point!");
        CommentReplyResponse response = commentService.addReply("proj-1", "issue-1", "comment-1", request, author);

        assertThat(response.getId()).isEqualTo("new-reply-id");
        assertThat(response.getContent()).isEqualTo("Great point!");
        assertThat(response.getCommentId()).isEqualTo("comment-1");
        assertThat(response.getAuthorId()).isEqualTo("user-1");

        ArgumentCaptor<CommentReply> captor = ArgumentCaptor.forClass(CommentReply.class);
        verify(commentReplyRepository).save(captor.capture());
        assertThat(captor.getValue().getComment().getId()).isEqualTo("comment-1");
    }

    @Test
    void resolveCommentSetsResolvedAt() {
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-1", "user-1")).thenReturn(true);
        when(commentRepository.findById("comment-1")).thenReturn(Optional.of(comment));
        when(commentRepository.save(any(Comment.class))).thenReturn(comment);

        CommentResponse response = commentService.resolveComment("proj-1", "issue-1", "comment-1", author);

        assertThat(comment.getResolvedAt()).isNotNull();
        assertThat(comment.getResolvedBy()).isEqualTo(author);
        assertThat(response.getResolvedAt()).isNotNull();
    }

    @Test
    void deleteCommentByAuthorSucceeds() {
        when(commentRepository.findById("comment-1")).thenReturn(Optional.of(comment));
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "user-1"))
                .thenReturn(Optional.empty());

        commentService.deleteComment("proj-1", "issue-1", "comment-1", author);

        verify(commentRepository).delete(comment);
    }

    @Test
    void deleteCommentByNonAuthorNonAdminThrows403() {
        when(commentRepository.findById("comment-1")).thenReturn(Optional.of(comment));

        ProjectMember reviewerMember = new ProjectMember();
        reviewerMember.setRole(MemberRole.REVIEWER);
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "user-2"))
                .thenReturn(Optional.of(reviewerMember));

        assertThatThrownBy(() -> commentService.deleteComment("proj-1", "issue-1", "comment-1", otherUser))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void deleteCommentByAdminSucceeds() {
        when(commentRepository.findById("comment-1")).thenReturn(Optional.of(comment));

        ProjectMember adminMember = new ProjectMember();
        adminMember.setRole(MemberRole.ADMIN);
        when(projectMemberRepository.findByProjectIdAndUserId("proj-1", "user-2"))
                .thenReturn(Optional.of(adminMember));

        commentService.deleteComment("proj-1", "issue-1", "comment-1", otherUser);

        verify(commentRepository).delete(comment);
    }
}
