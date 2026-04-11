package com.conductor.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate

@ActiveProfiles("local")
@Testcontainers
class CommentFlowE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    static final Path STORAGE_DIR;

    static {
        try {
            STORAGE_DIR = Files.createTempDirectory("conductor-comment-e2e-storage");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("local.storage.path", STORAGE_DIR::toString);
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

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
    void selectionCommentValidation() {
        // 1. Post with selectionStart + selectionLength → 201
        var selectionResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/comments"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "documentId", documentId,
                        "content", "Selection comment",
                        "selectionStart", 5,
                        "selectionLength", 10
                ), adminHeaders),
                Map.class);
        assertThat(selectionResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(selectionResp.getBody().get("selectionStart")).isEqualTo(5);
        assertThat(selectionResp.getBody().get("selectionLength")).isEqualTo(10);

        // 2. Post with neither lineNumber nor selection → 400
        var invalidResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/comments"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "documentId", documentId,
                        "content", "No anchor comment"
                ), adminHeaders),
                Map.class);
        assertThat(invalidResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
