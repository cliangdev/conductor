package com.conductor.e2e;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the /api/v2 Work Item comments, reviewers, and reviews sub-resources end-to-end against real
 * Postgres. Mirrors the v1 {@code CommentFlowE2ETest} + {@code ReviewWorkflowE2ETest} flows but drives the
 * v2 controllers ({@code WorkItemCommentsController}, {@code WorkItemReviewersController},
 * {@code WorkItemReviewsController}) which delegate to the same shared services and map v1 DTOs → v2 DTOs.
 */
class WorkItemCommentsReviewsV2E2ETest extends AbstractE2ETest {

    HttpHeaders adminHeaders;
    HttpHeaders reviewerHeaders;
    String reviewerId;
    String projectId;

    @BeforeEach
    void setup() {
        // Admin — creates user on first login.
        var adminLogin = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "admin@wi-cr-v2.test", "password", "conductor"),
                Map.class);
        assertThat(adminLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        adminHeaders = bearerHeaders((String) adminLogin.getBody().get("accessToken"));

        // Reviewer — creates user on first login.
        var reviewerLogin = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "reviewer@wi-cr-v2.test", "password", "conductor"),
                Map.class);
        assertThat(reviewerLogin.getStatusCode()).isEqualTo(HttpStatus.OK);
        reviewerHeaders = bearerHeaders((String) reviewerLogin.getBody().get("accessToken"));
        reviewerId = (String) ((Map<?, ?>) reviewerLogin.getBody().get("user")).get("id");

        // Admin creates project (seeds the ENGINEERING workflow), becomes ADMIN member.
        var projResp = rest.exchange(
                url("/api/v1/projects"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "WI Comments/Reviews v2", "description", "test"), adminHeaders),
                Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");

        // Admin invites reviewer (REVIEWER role) and reviewer accepts.
        var inviteResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/invites"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("email", "reviewer@wi-cr-v2.test", "role", "REVIEWER"), adminHeaders),
                Map.class);
        assertThat(inviteResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String inviteToken = (String) inviteResp.getBody().get("token");

        var acceptResp = rest.exchange(
                url("/api/v1/invites/" + inviteToken + "/accept"),
                HttpMethod.POST,
                new HttpEntity<>(reviewerHeaders),
                Map.class);
        assertThat(acceptResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void v2WorkItemCommentsReviewersAndReviewsFlow() {
        // Create a Work Item via v2.
        var createResp = rest.exchange(
                url("/api/v2/projects/" + projectId + "/work-items"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "V2 Sub-resource WI", "type", "PRD"), adminHeaders),
                Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String workItemId = (String) createResp.getBody().get("id");
        assertThat(workItemId).isNotBlank();

        String base = "/api/v2/projects/" + projectId + "/work-items/" + workItemId;

        // Create a document on the Work Item (comment anchor) via v2.
        var docResp = rest.exchange(
                url(base + "/documents"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "filename", "spec.md",
                        "content", "# PRD\n\nLine one.\nLine two.\nLine three.",
                        "contentType", "text/markdown"
                ), adminHeaders),
                Map.class);
        assertThat(docResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String documentId = (String) docResp.getBody().get("id");

        // ---- Comments ----
        // Create a line comment → 201.
        var commentResp = rest.exchange(
                url(base + "/comments"),
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

        // Reply to the comment → 201.
        var replyResp = rest.exchange(
                url(base + "/comments/" + commentId + "/replies"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("content", "Done"), adminHeaders),
                Map.class);
        assertThat(replyResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replyResp.getBody().get("commentId")).isEqualTo(commentId);

        // List comments → contains the comment with its nested reply.
        var listCommentsResp = rest.exchange(
                url(base + "/comments"),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                List.class);
        assertThat(listCommentsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> comments = listCommentsResp.getBody();
        assertThat(comments).hasSize(1);
        Map<?, ?> firstComment = (Map<?, ?>) comments.get(0);
        assertThat(firstComment.get("id")).isEqualTo(commentId);
        assertThat((List<?>) firstComment.get("replies")).hasSize(1);

        // ---- Reviewers ----
        // Admin assigns reviewer → 201, response surfaces workItemId.
        var assignResp = rest.exchange(
                url(base + "/reviewers"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("userId", reviewerId), adminHeaders),
                Map.class);
        assertThat(assignResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(assignResp.getBody().get("userId")).isEqualTo(reviewerId);
        assertThat(assignResp.getBody().get("workItemId")).isEqualTo(workItemId);

        // List reviewers → contains the reviewer.
        var listReviewersResp = rest.exchange(
                url(base + "/reviewers"),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                List.class);
        assertThat(listReviewersResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> reviewers = listReviewersResp.getBody();
        assertThat(reviewers).hasSize(1);
        assertThat(((Map<?, ?>) reviewers.get(0)).get("userId")).isEqualTo(reviewerId);

        // ---- Reviews ----
        // Assigned reviewer submits a review → 201.
        var reviewResp = rest.exchange(
                url(base + "/reviews"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("verdict", "APPROVED", "body", "Looks good"), reviewerHeaders),
                Map.class);
        assertThat(reviewResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reviewResp.getBody().get("verdict")).isEqualTo("APPROVED");

        // List reviews → contains the submitted review with reviewer name.
        var listReviewsResp = rest.exchange(
                url(base + "/reviews"),
                HttpMethod.GET,
                new HttpEntity<>(adminHeaders),
                List.class);
        assertThat(listReviewsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> reviews = listReviewsResp.getBody();
        assertThat(reviews).hasSize(1);
        Map<?, ?> firstReview = (Map<?, ?>) reviews.get(0);
        assertThat(firstReview.get("reviewerId")).isEqualTo(reviewerId);
        assertThat(firstReview.get("verdict")).isEqualTo("APPROVED");
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
