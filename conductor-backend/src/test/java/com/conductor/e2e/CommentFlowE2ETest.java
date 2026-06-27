package com.conductor.e2e;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CommentFlowE2ETest extends AbstractE2ETest {

    HttpHeaders adminHeaders;
    String projectId;
    String issueId;
    String documentId;

    @BeforeEach
    void setup() {
        // Login as admin
        var adminLogin = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "admin@comment-e2e.test", "password", "conductor"),
                Map.class);
        assertThat(adminLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminHeaders = bearerHeaders((String) adminLogin.getBody().get("accessToken"));

        // Create project
        var projResp = rest.exchange(
                url("/api/v1/projects"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Comment E2E Project", "description", "test"), adminHeaders),
                Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");

        // Create issue
        var issueResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "Comment Flow Issue", "type", "PRD"), adminHeaders),
                Map.class);
        assertThat(issueResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        issueId = (String) issueResp.getBody().get("id");

        // Create document (needed for comment anchor)
        var docResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/documents"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "filename", "spec.md",
                        "content", "# PRD\n\nLine one.\nLine two.\nLine three.",
                        "contentType", "text/markdown"
                ), adminHeaders),
                Map.class);
        assertThat(docResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        documentId = (String) docResp.getBody().get("id");
    }

    @Test
    void lineCommentLifecycle() {
        // 1. Post line comment → 201
        var commentResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/comments"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "documentId", documentId,
                        "content", "Fix this",
                        "lineNumber", 1
                ), adminHeaders),
                Map.class);
        assertThat(commentResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String commentId = (String) commentResp.getBody().get("id");
        assertThat(commentId).isNotBlank();
        assertThat(commentResp.getBody().get("content")).isEqualTo("Fix this");
        assertThat(commentResp.getBody().get("lineNumber")).isEqualTo(1);

        // 2. Post reply → 201
        var replyResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/comments/" + commentId + "/replies"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("content", "Done"), adminHeaders),
                Map.class);
        assertThat(replyResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replyResp.getBody().get("content")).isEqualTo("Done");
        assertThat(replyResp.getBody().get("commentId")).isEqualTo(commentId);

        // 3. List comments → contains comment with reply
        var listResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/comments"),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                List.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> comments = listResp.getBody();
        assertThat(comments).hasSize(1);
        Map<?, ?> firstComment = (Map<?, ?>) comments.get(0);
        assertThat(firstComment.get("id")).isEqualTo(commentId);
        List<?> replies = (List<?>) firstComment.get("replies");
        assertThat(replies).hasSize(1);
        assertThat(((Map<?, ?>) replies.get(0)).get("content")).isEqualTo("Done");

        // 4. Resolve comment → 200 with resolvedAt set
        var resolveResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/comments/" + commentId + "/resolve"),
                HttpMethod.PATCH,
                new HttpEntity<>(adminHeaders),
                Map.class);
        assertThat(resolveResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolveResp.getBody().get("resolvedAt")).isNotNull();

        // 5. Delete comment → 204
        var deleteResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/comments/" + commentId),
                HttpMethod.DELETE,
                new HttpEntity<>(adminHeaders),
                Void.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void lineNumberValidation() {
        // 1. Post without lineNumber → 400
        var missingLineResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/comments"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "documentId", documentId,
                        "content", "No anchor comment"
                ), adminHeaders),
                Map.class);
        assertThat(missingLineResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // 2. Post with lineNumber → 201 and quotedText is populated server-side
        var validResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/comments"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "documentId", documentId,
                        "content", "Line comment",
                        "lineNumber", 3
                ), adminHeaders),
                Map.class);
        assertThat(validResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(validResp.getBody().get("lineNumber")).isEqualTo(3);
        assertThat(validResp.getBody().get("quotedText")).isNotNull();
        assertThat(validResp.getBody().get("lineStale")).isEqualTo(false);
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
