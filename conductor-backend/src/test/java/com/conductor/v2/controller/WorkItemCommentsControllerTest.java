package com.conductor.v2.controller;

import com.conductor.config.SecurityConfig;
import com.conductor.entity.Comment;
import com.conductor.entity.Document;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.GlobalExceptionHandler;
import com.conductor.repository.ProjectApiKeyRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.UserApiKeyRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.CommentService;
import com.conductor.service.JwtService;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.view.CommentAnchor;
import com.conductor.service.view.CommentWithRepliesView;
import com.conductor.workflow.RunTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @WebMvcTest slice for {@link WorkItemCommentsController}: the request/response contract of text-selection
 * anchors and {@code outdated}. The rules that decide anchor-vs-line live in {@code CommentServiceTest}.
 */
@WebMvcTest(WorkItemCommentsController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, ProjectSecurityService.class})
class WorkItemCommentsControllerTest {

    private static final String BASE = "/api/v2/projects/proj-1/work-items/wi-1/comments";
    private static final String AUTH = "Bearer valid-token";

    @Autowired private MockMvc mockMvc;

    @MockitoBean private CommentService commentService;
    @MockitoBean private ProjectMemberRepository projectMemberRepository;

    @MockitoBean private JwtService jwtService;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private ProjectApiKeyRepository projectApiKeyRepository;
    @MockitoBean private UserApiKeyRepository userApiKeyRepository;
    @MockitoBean private RunTokenService runTokenService;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId("user-1");
        user.setEmail("user@example.com");
        when(jwtService.validateToken("valid-token")).thenReturn(true);
        when(jwtService.getUserIdFromToken("valid-token")).thenReturn("user-1");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(projectMemberRepository.existsByProjectIdAndUserId("proj-1", "user-1")).thenReturn(true);
    }

    private Comment savedComment(boolean anchored) {
        Document document = new Document();
        document.setId("doc-1");
        Comment comment = new Comment();
        comment.setId("c-1");
        comment.setDocument(document);
        comment.setAuthor(user);
        comment.setContent("Check this");
        comment.setCreatedAt(OffsetDateTime.parse("2026-10-29T10:00:00Z"));
        if (anchored) {
            comment.setAnchorExact("20 of 26");
            comment.setAnchorPrefix("funnel: ");
            comment.setAnchorSuffix(" signed up");
            comment.setAnchorSectionId("funnel");
            comment.setAnchorSectionTitle("Funnel");
            comment.setQuotedText("20 of 26");
        } else {
            comment.setLineNumber(4);
            comment.setQuotedText("line four");
        }
        return comment;
    }

    @Test
    void createWithAnchor_passesTheAnchorToTheServiceAndReturnsItWithOutdatedFalse() throws Exception {
        Comment comment = savedComment(true);
        when(commentService.createComment(eq("proj-1"), eq("wi-1"), eq("doc-1"), eq("Check this"),
                isNull(), any(CommentAnchor.class), any(User.class))).thenReturn(comment);
        when(commentService.isOutdated(comment)).thenReturn(false);

        mockMvc.perform(post(BASE).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"documentId":"doc-1","content":"Check this",
                                 "anchor":{"exact":"20 of 26","prefix":"funnel: ","suffix":" signed up",
                                           "sectionId":"funnel","sectionTitle":"Funnel"}}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quotedText").value("20 of 26"))
                .andExpect(jsonPath("$.anchor.exact").value("20 of 26"))
                .andExpect(jsonPath("$.anchor.prefix").value("funnel: "))
                .andExpect(jsonPath("$.anchor.suffix").value(" signed up"))
                .andExpect(jsonPath("$.anchor.sectionId").value("funnel"))
                .andExpect(jsonPath("$.anchor.sectionTitle").value("Funnel"))
                .andExpect(jsonPath("$.outdated").value(false));

        ArgumentCaptor<CommentAnchor> anchor = ArgumentCaptor.forClass(CommentAnchor.class);
        verify(commentService).createComment(any(), any(), any(), any(), isNull(), anchor.capture(), any());
        assertThat(anchor.getValue()).isEqualTo(
                new CommentAnchor("20 of 26", "funnel: ", " signed up", "funnel", "Funnel"));
    }

    @Test
    void createWithLineNumber_stillWorksAndReturnsNullAnchor() throws Exception {
        Comment comment = savedComment(false);
        when(commentService.createComment(eq("proj-1"), eq("wi-1"), eq("doc-1"), eq("Check this"),
                eq(4), isNull(), any(User.class))).thenReturn(comment);

        mockMvc.perform(post(BASE).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"doc-1\",\"content\":\"Check this\",\"lineNumber\":4}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lineNumber").value(4))
                .andExpect(jsonPath("$.anchor").doesNotExist())
                .andExpect(jsonPath("$.outdated").value(false));
    }

    @Test
    void createWithNeitherLineNumberNorAnchor_isNoLongerRejectedBySchemaValidation() throws Exception {
        // lineNumber is optional in the spec now; the service decides per document kind.
        when(commentService.createComment(any(), any(), any(), any(), isNull(), isNull(), any()))
                .thenThrow(new BusinessException("anchor is required on HTML documents"));

        mockMvc.perform(post(BASE).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"doc-1\",\"content\":\"Check this\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("anchor is required on HTML documents"));
    }

    @Test
    void createWithLineNumberOnHtml_returns400ProblemDetail() throws Exception {
        when(commentService.createComment(any(), any(), any(), any(), eq(4), isNull(), any()))
                .thenThrow(new BusinessException("lineNumber is not supported on HTML documents; send an anchor"));

        mockMvc.perform(post(BASE).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"doc-1\",\"content\":\"Check this\",\"lineNumber\":4}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("lineNumber is not supported on HTML documents; send an anchor"));
    }

    @Test
    void createWithAnchorOnMarkdown_returns400ProblemDetail() throws Exception {
        when(commentService.createComment(any(), any(), any(), any(), isNull(), any(CommentAnchor.class), any()))
                .thenThrow(new BusinessException("anchor is only supported on HTML documents; send a lineNumber"));

        mockMvc.perform(post(BASE).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"doc-1\",\"content\":\"Check this\",\"anchor\":{\"exact\":\"x\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("anchor is only supported on HTML documents; send a lineNumber"));
    }

    @Test
    void createWithAnEmptyExact_isRejectedBeforeTheService() throws Exception {
        mockMvc.perform(post(BASE).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"doc-1\",\"content\":\"c\",\"anchor\":{\"exact\":\"\"}}"))
                .andExpect(status().isBadRequest());
        verify(commentService, never()).createComment(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createWithAnAnchorMissingExact_isRejectedBeforeTheService() throws Exception {
        mockMvc.perform(post(BASE).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"doc-1\",\"content\":\"c\",\"anchor\":{\"prefix\":\"p\"}}"))
                .andExpect(status().isBadRequest());
        verify(commentService, never()).createComment(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createWithAnOversizedExact_isRejectedBeforeTheService() throws Exception {
        String tooLong = "x".repeat(2001);
        mockMvc.perform(post(BASE).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"doc-1\",\"content\":\"c\",\"anchor\":{\"exact\":\"" + tooLong + "\"}}"))
                .andExpect(status().isBadRequest());
        verify(commentService, never()).createComment(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createWithAnOversizedPrefix_isRejectedBeforeTheService() throws Exception {
        String tooLong = "p".repeat(65);
        mockMvc.perform(post(BASE).header("Authorization", AUTH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"documentId\":\"doc-1\",\"content\":\"c\",\"anchor\":{\"exact\":\"x\",\"prefix\":\""
                                + tooLong + "\"}}"))
                .andExpect(status().isBadRequest());
        verify(commentService, never()).createComment(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void list_returnsAnchorAndOutdatedForAnchoredAndLineComments() throws Exception {
        CommentAnchor anchor = new CommentAnchor("20 of 26", "funnel: ", " signed up", "funnel", "Funnel");
        CommentWithRepliesView anchored = new CommentWithRepliesView(
                "c-1", "doc-1", "user-1", "Check this", OffsetDateTime.parse("2026-10-29T10:00:00Z"), "User",
                0, "20 of 26", false, anchor, true, "prd.html", null, List.of());
        CommentWithRepliesView line = new CommentWithRepliesView(
                "c-2", "doc-2", "user-1", "Line", OffsetDateTime.parse("2026-10-29T10:00:00Z"), "User",
                4, "line four", false, null, false, "spec.md", null, List.of());
        when(commentService.listComments(eq("proj-1"), eq("wi-1"), any(), any(User.class)))
                .thenReturn(List.of(anchored, line));

        mockMvc.perform(get(BASE).header("Authorization", AUTH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].anchor.exact").value("20 of 26"))
                .andExpect(jsonPath("$[0].anchor.sectionId").value("funnel"))
                .andExpect(jsonPath("$[0].quotedText").value("20 of 26"))
                .andExpect(jsonPath("$[0].outdated").value(true))
                .andExpect(jsonPath("$[1].lineNumber").value(4))
                .andExpect(jsonPath("$[1].anchor").doesNotExist())
                .andExpect(jsonPath("$[1].outdated").value(false));
    }
}
