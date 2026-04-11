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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)

@ActiveProfiles("local")
@Testcontainers
class ReviewWorkflowE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void dbProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    HttpHeaders adminHeaders;
    HttpHeaders reviewerHeaders;
    HttpHeaders outsiderHeaders;
    String adminId;
    String reviewerId;
    String outsiderId;
    String projectId;

    @BeforeEach
    void setup() {
        // Login as admin — creates user on first call
        var adminLogin = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "admin@review-e2e.test", "password", "conductor"),
                Map.class);
        assertThat(adminLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminHeaders = bearerHeaders((String) adminLogin.getBody().get("accessToken"));
        adminId = (String) ((Map<?, ?>) adminLogin.getBody().get("user")).get("id");

        // Login as reviewer — creates user on first call
        var reviewerLogin = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "reviewer@review-e2e.test", "password", "conductor"),
                Map.class);
        assertThat(reviewerLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        reviewerHeaders = bearerHeaders((String) reviewerLogin.getBody().get("accessToken"));
        reviewerId = (String) ((Map<?, ?>) reviewerLogin.getBody().get("user")).get("id");

        // Login as outsider — creates user on first call
        var outsiderLogin = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "outsider@review-e2e.test", "password", "conductor"),
                Map.class);
        assertThat(outsiderLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        outsiderHeaders = bearerHeaders((String) outsiderLogin.getBody().get("accessToken"));
        outsiderId = (String) ((Map<?, ?>) outsiderLogin.getBody().get("user")).get("id");

        // Admin creates project — becomes ADMIN member automatically
        var projResp = rest.exchange(
                url("/api/v1/projects"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Review E2E Project", "description", "test"), adminHeaders),
                Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");

        // Admin invites reviewer
        var inviteResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/invites"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "reviewer@review-e2e.test", "role", "REVIEWER"), adminHeaders),
                Map.class);
        assertThat(inviteResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String inviteToken = (String) inviteResp.getBody().get("token");

        // Reviewer accepts invite
        var acceptResp = rest.exchange(
                url("/api/v1/invites/" + inviteToken + "/accept"),
                HttpMethod.POST,
                new HttpEntity<>(reviewerHeaders),
                Map.class);
        assertThat(acceptResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void assignReviewerThenSubmitReview() {
        // 1. Admin creates issue
        var issueResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "Review Workflow Issue", "type", "PRD"), adminHeaders),
                Map.class);
        assertThat(issueResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String issueId = (String) issueResp.getBody().get("id");

        // 2. Admin assigns reviewer → 201
        var assignResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/reviewers"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", reviewerId), adminHeaders),
                Map.class);
        assertThat(assignResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(assignResp.getBody().get("userId")).isEqualTo(reviewerId);

        // 3. Duplicate assignment → 409
        var dupAssignResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/reviewers"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", reviewerId), adminHeaders),
                Map.class);
        assertThat(dupAssignResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // 4. List reviewers → contains reviewer
        var listResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/reviewers"),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                List.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> reviewers = listResp.getBody();
        assertThat(reviewers).hasSize(1);
        assertThat(((Map<?, ?>) reviewers.get(0)).get("userId")).isEqualTo(reviewerId);

        // 5. Reviewer submits review → 201
        var reviewResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/reviews"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("verdict", "APPROVED", "body", "Looks good"), reviewerHeaders),
                Map.class);
        assertThat(reviewResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reviewResp.getBody().get("verdict")).isEqualTo("APPROVED");

        // 6. Reviewer re-submits review with different verdict → 201 (upsert)
        var updateReviewResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/reviews"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("verdict", "CHANGES_REQUESTED"), reviewerHeaders),
                Map.class);
        assertThat(updateReviewResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(updateReviewResp.getBody().get("verdict")).isEqualTo("CHANGES_REQUESTED");

        // 7. Get reviews → contains updated verdict CHANGES_REQUESTED
        var getReviewsResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/reviews"),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                List.class);
        assertThat(getReviewsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> reviews = getReviewsResp.getBody();
        assertThat(reviews).hasSize(1);
        assertThat(((Map<?, ?>) reviews.get(0)).get("verdict")).isEqualTo("CHANGES_REQUESTED");

        // 8. Unassign reviewer → 204
        var unassignResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/reviewers/" + reviewerId),
                HttpMethod.DELETE,
                new HttpEntity<>(adminHeaders),
                Void.class);
        assertThat(unassignResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void nonAssignedReviewerCannotSubmitReview() {
        // Admin creates a second project where outsider is not a member
        var proj2Resp = rest.exchange(
                url("/api/v1/projects"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Isolated Project", "description", "test"), adminHeaders),
                Map.class);
        assertThat(proj2Resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String proj2Id = (String) proj2Resp.getBody().get("id");

        var issueResp = rest.exchange(
                url("/api/v1/projects/" + proj2Id + "/issues"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "Isolated Issue", "type", "PRD"), adminHeaders),
                Map.class);
        assertThat(issueResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String issueId = (String) issueResp.getBody().get("id");

        // Outsider (not a member of proj2) tries to submit a review → 403 or 404
        var outsiderReviewResp = rest.exchange(
                url("/api/v1/projects/" + proj2Id + "/issues/" + issueId + "/reviews"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("verdict", "APPROVED"), outsiderHeaders),
                Map.class);
        assertThat(outsiderReviewResp.getStatusCode().value()).isIn(403, 404);
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
