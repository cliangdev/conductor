package com.conductor.e2e;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the /api/v2 work-items surface end-to-end against real Postgres. Regression guard for the
 * LazyInitializationException that slipped to prod: the v2 controller maps WorkItem entities to DTOs, and
 * with open-in-view disabled the mapping must run inside a transaction or accessing the lazy assignee/
 * createdBy User proxy throws. Context-boot tests don't catch it — only calling the endpoints with a
 * work item that HAS an assignee does.
 */
class WorkItemV2FlowE2ETest extends AbstractE2ETest {

    HttpHeaders authHeaders;

    @BeforeEach
    void login() {
        var loginResp = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "e2e-wiv2@example.com", "password", "conductor"),
                Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void v2WorkItemCrudWithAssigneeDoesNotLazyInitFail() {
        // Project (seeds the ENGINEERING workflow) via v1.
        var projResp = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "WI v2 E2E", "description", "test"), authHeaders), Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String projectId = (String) projResp.getBody().get("id");

        // Create a work item via v2.
        var createResp = rest.exchange(url("/api/v2/projects/" + projectId + "/work-items"), HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "V2 Work Item", "type", "PRD", "workflow", "ENGINEERING"), authHeaders), Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String workItemId = (String) createResp.getBody().get("id");
        String displayId = (String) createResp.getBody().get("displayId");
        String userId = (String) createResp.getBody().get("createdBy");
        assertThat(workItemId).isNotBlank();
        assertThat(displayId).isNotBlank();
        assertThat(createResp.getBody().get("workflow")).isEqualTo("ENGINEERING");

        // Assign it (so the mapping has to initialize the lazy assignee User — the exact path that failed).
        var patchResp = rest.exchange(url("/api/v2/projects/" + projectId + "/work-items/" + workItemId),
                HttpMethod.PATCH, new HttpEntity<>(Map.of("assigneeId", userId), authHeaders), Map.class);
        assertThat(patchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) patchResp.getBody().get("assignee")).get("name")).isNotNull();

        // List, get-by-id, and get-by-display must all map the assigned item without LazyInitializationException.
        var listResp = rest.exchange(url("/api/v2/projects/" + projectId + "/work-items"), HttpMethod.GET,
                new HttpEntity<>(authHeaders), List.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResp.getBody()).hasSize(1);

        var getResp = rest.exchange(url("/api/v2/projects/" + projectId + "/work-items/" + workItemId),
                HttpMethod.GET, new HttpEntity<>(authHeaders), Map.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Map<?, ?>) getResp.getBody().get("assignee")).get("name")).isNotNull();

        var byDisplayResp = rest.exchange(
                url("/api/v2/projects/" + projectId + "/work-items/by-display/" + displayId),
                HttpMethod.GET, new HttpEntity<>(authHeaders), Map.class);
        assertThat(byDisplayResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(byDisplayResp.getBody().get("id")).isEqualTo(workItemId);
    }
}
