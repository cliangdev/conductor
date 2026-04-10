package com.conductor.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
class StatusTransitionE2ETest {

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
    String projectId;

    @BeforeEach
    void setup() {
        // Login as admin — creates user on first call
        var adminLogin = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "admin@status-e2e.test", "password", "conductor"),
                Map.class);
        assertThat(adminLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminHeaders = bearerHeaders((String) adminLogin.getBody().get("accessToken"));

        // Login as reviewer — creates user on first call
        var reviewerLogin = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "reviewer@status-e2e.test", "password", "conductor"),
                Map.class);
        assertThat(reviewerLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        reviewerHeaders = bearerHeaders((String) reviewerLogin.getBody().get("accessToken"));

        // Admin creates project — becomes ADMIN member automatically
        var projResp = rest.exchange(
                url("/api/v1/projects"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Status Transition E2E Project", "description", "test"), adminHeaders),
                Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");

        // Admin invites reviewer
        var inviteResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/invites"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "reviewer@status-e2e.test", "role", "REVIEWER"), adminHeaders),
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
    void validStatusTransitions() {
        // Create issue — starts as DRAFT
        var issueResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "Status Transition Issue", "type", "PRD"), adminHeaders),
                Map.class);
        assertThat(issueResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String issueId = (String) issueResp.getBody().get("id");
        assertThat(issueResp.getBody().get("status")).isEqualTo("DRAFT");

        // DRAFT → IN_REVIEW
        var toInReview = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "IN_REVIEW"), adminHeaders),
                Map.class);
        assertThat(toInReview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(toInReview.getBody().get("status")).isEqualTo("IN_REVIEW");

        // IN_REVIEW → APPROVED
        var toApproved = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "APPROVED"), adminHeaders),
                Map.class);
        assertThat(toApproved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(toApproved.getBody().get("status")).isEqualTo("APPROVED");

        // APPROVED → ARCHIVED
        var toArchived = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "ARCHIVED"), adminHeaders),
                Map.class);
        assertThat(toArchived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(toArchived.getBody().get("status")).isEqualTo("ARCHIVED");
    }

    @Test
    void invalidTransitionReturns400() {
        // Create a fresh issue (DRAFT)
        var issueResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "Invalid Transition Issue", "type", "PRD"), adminHeaders),
                Map.class);
        assertThat(issueResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String issueId = (String) issueResp.getBody().get("id");

        // Try DRAFT → APPROVED directly (skipping IN_REVIEW) → 400
        var invalidTransition = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "APPROVED"), adminHeaders),
                Map.class);
        assertThat(invalidTransition.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void reviewerCannotChangeStatus() {
        // Create issue as admin
        var issueResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "Reviewer Forbidden Issue", "type", "PRD"), adminHeaders),
                Map.class);
        assertThat(issueResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String issueId = (String) issueResp.getBody().get("id");

        // Reviewer tries to change status → 403
        var reviewerPatch = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "IN_REVIEW"), reviewerHeaders),
                Map.class);
        assertThat(reviewerPatch.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
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
