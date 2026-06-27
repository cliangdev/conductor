package com.conductor.e2e;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StatusTransitionE2ETest extends AbstractE2ETest {

    HttpHeaders adminHeaders;
    HttpHeaders reviewerHeaders;
    String reviewerId;
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
        reviewerId = (String) ((Map<?, ?>) reviewerLogin.getBody().get("user")).get("id");

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

        // IN_REVIEW → READY_FOR_DEVELOPMENT
        var toReady = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "READY_FOR_DEVELOPMENT"), adminHeaders),
                Map.class);
        assertThat(toReady.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(toReady.getBody().get("status")).isEqualTo("READY_FOR_DEVELOPMENT");

        // READY_FOR_DEVELOPMENT → IN_PROGRESS
        var toInProgress = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "IN_PROGRESS"), adminHeaders),
                Map.class);
        assertThat(toInProgress.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(toInProgress.getBody().get("status")).isEqualTo("IN_PROGRESS");

        // IN_PROGRESS → CODE_REVIEW
        var toCodeReview = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "CODE_REVIEW"), adminHeaders),
                Map.class);
        assertThat(toCodeReview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(toCodeReview.getBody().get("status")).isEqualTo("CODE_REVIEW");

        // CODE_REVIEW → DONE is review-gated (COND-18 P0-6): without an approved review it is rejected (422)…
        var blockedDone = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "DONE"), adminHeaders),
                Map.class);
        assertThat(blockedDone.getStatusCode().value()).isEqualTo(422);

        // …assign the reviewer and record an APPROVED review to satisfy the gate.
        rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/reviewers"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", reviewerId), adminHeaders),
                Map.class);
        var reviewResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId + "/reviews"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("verdict", "APPROVED", "body", "LGTM"), reviewerHeaders),
                Map.class);
        assertThat(reviewResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // CODE_REVIEW → DONE now succeeds.
        var toDone = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "DONE"), adminHeaders),
                Map.class);
        assertThat(toDone.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(toDone.getBody().get("status")).isEqualTo("DONE");
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

        // Try DRAFT → READY_FOR_DEVELOPMENT directly (skipping IN_REVIEW) → 400
        var invalidTransition = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues/" + issueId),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "READY_FOR_DEVELOPMENT"), adminHeaders),
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
}
