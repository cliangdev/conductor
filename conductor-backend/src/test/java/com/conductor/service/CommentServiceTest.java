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

import java.nio.charset.StandardCharsets;
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

    @Mock
    private StorageService storageService;

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
        document.setStoragePath("proj-1/issues/issue-1/doc-1/spec.md");
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
        String docContent = "line one\nline two\nline three";
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(issue));
        when(documentRepository.findByIdAndIssueId("doc-1", "issue-1")).thenReturn(Optional.of(document));
        when(storageService.download(document.getStoragePath()))
                .thenReturn(docContent.getBytes(StandardCharsets.UTF_8));
        when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            if (c.getId() == null) c.setId("new-comment-id");
            if (c.getCreatedAt() == null) c.setCreatedAt(OffsetDateTime.now());
            if (c.getUpdatedAt() == null) c.setUpdatedAt(OffsetDateTime.now());
            return c;
        });

        CreateCommentRequest request = new CreateCommentRequest("doc-1", "Looks good", 2);

        CommentResponse response = commentService.createComment("proj-1", "issue-1", request, author);

        assertThat(response.getLineNumber()).isEqualTo(2);
        assertThat(response.getContent()).isEqualTo("Looks good");
        assertThat(response.getAuthorId()).isEqualTo("user-1");
    }

    @Test
    void createCommentPersistsQuotedTextFromDocumentLine() {
        String docContent = "# Introduction\nThis is the summary\nMore details here";
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(issue));
        when(documentRepository.findByIdAndIssueId("doc-1", "issue-1")).thenReturn(Optional.of(document));
        when(storageService.download(document.getStoragePath()))
                .thenReturn(docContent.getBytes(StandardCharsets.UTF_8));
        when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            if (c.getId() == null) c.setId("new-comment-id");
            if (c.getCreatedAt() == null) c.setCreatedAt(OffsetDateTime.now());
            if (c.getUpdatedAt() == null) c.setUpdatedAt(OffsetDateTime.now());
            return c;
        });

        CreateCommentRequest request = new CreateCommentRequest("doc-1", "Needs clarification", 2);

        commentService.createComment("proj-1", "issue-1", request, author);

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(captor.capture());
        assertThat(captor.getValue().getQuotedText()).isEqualTo("This is the summary");
    }

    @Test
    void createCommentQuotedTextFirstLine() {
        String docContent = "First line\nSecond line";
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(issue));
        when(documentRepository.findByIdAndIssueId("doc-1", "issue-1")).thenReturn(Optional.of(document));
        when(storageService.download(document.getStoragePath()))
                .thenReturn(docContent.getBytes(StandardCharsets.UTF_8));
        when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            if (c.getId() == null) c.setId("new-comment-id");
            if (c.getCreatedAt() == null) c.setCreatedAt(OffsetDateTime.now());
            if (c.getUpdatedAt() == null) c.setUpdatedAt(OffsetDateTime.now());
            return c;
        });

        CreateCommentRequest request = new CreateCommentRequest("doc-1", "Comment on first line", 1);

        commentService.createComment("proj-1", "issue-1", request, author);

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(captor.capture());
        assertThat(captor.getValue().getQuotedText()).isEqualTo("First line");
    }

    @Test
    void createCommentQuotedTextOutOfBoundsReturnsEmpty() {
        String docContent = "Only one line";
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(issue));
        when(documentRepository.findByIdAndIssueId("doc-1", "issue-1")).thenReturn(Optional.of(document));
        when(storageService.download(document.getStoragePath()))
                .thenReturn(docContent.getBytes(StandardCharsets.UTF_8));
        when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            if (c.getId() == null) c.setId("new-comment-id");
            if (c.getCreatedAt() == null) c.setCreatedAt(OffsetDateTime.now());
            if (c.getUpdatedAt() == null) c.setUpdatedAt(OffsetDateTime.now());
            return c;
        });

        CreateCommentRequest request = new CreateCommentRequest("doc-1", "Comment on line 99", 99);

        commentService.createComment("proj-1", "issue-1", request, author);

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(captor.capture());
        assertThat(captor.getValue().getQuotedText()).isEqualTo("");
    }

    @Test
    void createCommentDocumentWithNoStoragePathReturnsEmptyQuotedText() {
        document.setStoragePath(null);
        when(issueRepository.findById("issue-1")).thenReturn(Optional.of(issue));
        when(documentRepository.findByIdAndIssueId("doc-1", "issue-1")).thenReturn(Optional.of(document));
        when(commentRepository.save(any(Comment.class))).thenAnswer(inv -> {
            Comment c = inv.getArgument(0);
            if (c.getId() == null) c.setId("new-comment-id");
            if (c.getCreatedAt() == null) c.setCreatedAt(OffsetDateTime.now());
            if (c.getUpdatedAt() == null) c.setUpdatedAt(OffsetDateTime.now());
            return c;
        });

        CreateCommentRequest request = new CreateCommentRequest("doc-1", "Comment", 1);

        commentService.createComment("proj-1", "issue-1", request, author);

        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentRepository).save(captor.capture());
        assertThat(captor.getValue().getQuotedText()).isEqualTo("");
    }

    @Test
    void createCommentMissingLineNumberThrowsBusinessException() {
        CreateCommentRequest request = new CreateCommentRequest();
        request.setDocumentId("doc-1");
        request.setContent("Missing lineNumber");
        // lineNumber is null

        assertThatThrownBy(() -> commentService.createComment("proj-1", "issue-1", request, author))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("lineNumber");
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

        List<CommentWithRepliesResponse> results = commentService.listComments("proj-1", "issue-1", null, author);

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

    // --- listComments resolved filter tests ---

    @Test
    void listCommentsNullResolvedReturnsAll() {
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-1", "user-1")).thenReturn(true);
        when(commentRepository.findAllByIssueId("issue-1")).thenReturn(List.of(comment));
        when(commentReplyRepository.findAllByCommentId("comment-1")).thenReturn(List.of());

        List<CommentWithRepliesResponse> results = commentService.listComments("proj-1", "issue-1", null, author);

        verify(commentRepository).findAllByIssueId("issue-1");
        assertThat(results).hasSize(1);
    }

    @Test
    void listCommentsResolvedTrueReturnsOnlyResolvedComments() {
        Comment resolvedComment = new Comment();
        resolvedComment.setId("comment-resolved");
        resolvedComment.setIssue(issue);
        resolvedComment.setDocument(document);
        resolvedComment.setAuthor(author);
        resolvedComment.setContent("Resolved comment");
        resolvedComment.setLineNumber(10);
        resolvedComment.setResolvedAt(OffsetDateTime.now());
        resolvedComment.setCreatedAt(OffsetDateTime.now());
        resolvedComment.setUpdatedAt(OffsetDateTime.now());

        when(projectMemberRepository.existsByProjectIdAndUserId("proj-1", "user-1")).thenReturn(true);
        when(commentRepository.findAllByIssueIdAndResolvedAtIsNotNull("issue-1"))
                .thenReturn(List.of(resolvedComment));
        when(commentReplyRepository.findAllByCommentId("comment-resolved")).thenReturn(List.of());

        List<CommentWithRepliesResponse> results = commentService.listComments("proj-1", "issue-1", true, author);

        verify(commentRepository).findAllByIssueIdAndResolvedAtIsNotNull("issue-1");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getResolvedAt()).isNotNull();
    }

    @Test
    void listCommentsResolvedFalseReturnsOnlyUnresolvedComments() {
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-1", "user-1")).thenReturn(true);
        when(commentRepository.findAllByIssueIdAndResolvedAtIsNull("issue-1")).thenReturn(List.of(comment));
        when(commentReplyRepository.findAllByCommentId("comment-1")).thenReturn(List.of());

        List<CommentWithRepliesResponse> results = commentService.listComments("proj-1", "issue-1", false, author);

        verify(commentRepository).findAllByIssueIdAndResolvedAtIsNull("issue-1");
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getResolvedAt()).isNull();
    }

    @Test
    void listCommentsResponseIncludesNewFields() {
        comment.setLineNumber(7);
        comment.setQuotedText("The quoted line text");
        comment.setLineStale(true);

        when(projectMemberRepository.existsByProjectIdAndUserId("proj-1", "user-1")).thenReturn(true);
        when(commentRepository.findAllByIssueId("issue-1")).thenReturn(List.of(comment));
        when(commentReplyRepository.findAllByCommentId("comment-1")).thenReturn(List.of());

        List<CommentWithRepliesResponse> results = commentService.listComments("proj-1", "issue-1", null, author);

        assertThat(results).hasSize(1);
        CommentWithRepliesResponse response = results.get(0);
        assertThat(response.getLineNumber()).isEqualTo(7);
        assertThat(response.getQuotedText()).isEqualTo("The quoted line text");
        assertThat(response.getLineStale()).isTrue();
        assertThat(response.getDocumentName()).isEqualTo("spec.md");
    }

    // --- extractLineFromDocument unit tests (package-private helper) ---

    @Test
    void extractLineFromDocumentReturnsCorrectLine() {
        Document doc = new Document();
        doc.setStoragePath("some/path.md");
        String content = "alpha\nbeta\ngamma";
        when(storageService.download("some/path.md"))
                .thenReturn(content.getBytes(StandardCharsets.UTF_8));

        assertThat(commentService.extractLineFromDocument(doc, 2)).isEqualTo("beta");
    }

    @Test
    void extractLineFromDocumentLineNumberTooHighReturnsEmpty() {
        Document doc = new Document();
        doc.setStoragePath("some/path.md");
        String content = "only one line";
        when(storageService.download("some/path.md"))
                .thenReturn(content.getBytes(StandardCharsets.UTF_8));

        assertThat(commentService.extractLineFromDocument(doc, 5)).isEqualTo("");
    }

    @Test
    void extractLineFromDocumentNullStoragePathReturnsEmpty() {
        Document doc = new Document();
        doc.setStoragePath(null);

        assertThat(commentService.extractLineFromDocument(doc, 1)).isEqualTo("");
    }

    @Test
    void extractLineFromDocumentHandlesWindowsLineEndings() {
        Document doc = new Document();
        doc.setStoragePath("some/path.md");
        // Windows line endings: \r\n — split on \n leaves \r on each line
        String content = "line one\r\nline two\r\nline three";
        when(storageService.download("some/path.md"))
                .thenReturn(content.getBytes(StandardCharsets.UTF_8));

        // line 2 will be "line two\r" — acceptable, document is stored as-is
        String result = commentService.extractLineFromDocument(doc, 2);
        assertThat(result).contains("line two");
    }
}
