package com.conductor.e2e;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the /api/v2 Work Item documents and tasks sub-resources end-to-end against real Postgres.
 * Creates a project + work item via v2, then drives the documents (create/list/get) and tasks (save/get)
 * endpoints, asserting 2xx. Guards the v1→v2 DTO translation (notably {@code workItemId}→{@code workItemId})
 * and that the shared services are wired correctly under the package-based {@code /api/v2} prefix.
 */
class WorkItemDocumentsV2E2ETest extends AbstractE2ETest {

    HttpHeaders authHeaders;
    String projectId;
    String workItemId;

    @BeforeEach
    void setup() {
        var loginResp = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "e2e-docv2@example.com", "password", "conductor"),
                Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);

        // Project (seeds the ENGINEERING workflow) via v1.
        var projResp = rest.exchange(
                url("/api/v1/projects"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Doc v2 E2E Project", "description", "test"), authHeaders),
                Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");

        // Work item via v2.
        var wiResp = rest.exchange(
                url("/api/v2/projects/" + projectId + "/work-items"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "Doc v2 Work Item", "type", "PRD", "workflow", "ENGINEERING"), authHeaders),
                Map.class);
        assertThat(wiResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        workItemId = (String) wiResp.getBody().get("id");
        assertThat(workItemId).isNotBlank();
    }

    @Test
    void v2DocumentCreateListGet() {
        String docsBase = "/api/v2/projects/" + projectId + "/work-items/" + workItemId + "/documents";

        // Create.
        var createResp = rest.exchange(
                url(docsBase),
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "filename", "spec.md",
                        "content", "# V2 PRD\n\nThis is a v2 test.",
                        "contentType", "text/markdown"
                ), authHeaders),
                Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String docId = (String) createResp.getBody().get("id");
        assertThat(docId).isNotBlank();
        // v2 surfaces the parent ref as workItemId (not workItemId).
        assertThat(createResp.getBody().get("workItemId")).isEqualTo(workItemId);
        assertThat(createResp.getBody()).doesNotContainKey("issueId");

        // List.
        var listResp = rest.exchange(
                url(docsBase),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                List.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResp.getBody()).hasSize(1);

        // Get by id.
        var getResp = rest.exchange(
                url(docsBase + "/" + docId),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                Map.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody().get("workItemId")).isEqualTo(workItemId);
        assertThat(getResp.getBody().get("filename")).isEqualTo("spec.md");
        assertThat((String) getResp.getBody().get("storageUrl")).isNotBlank();
    }

    @Test
    void v2TasksSaveThenGet() {
        String tasksUrl = "/api/v2/projects/" + projectId + "/work-items/" + workItemId + "/tasks";

        // Save.
        var saveResp = rest.exchange(
                url(tasksUrl),
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "tasks", List.of(
                                Map.of("id", "T1", "title", "First task", "status", "todo")
                        )
                ), authHeaders),
                Map.class);
        assertThat(saveResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(saveResp.getBody().get("message")).isEqualTo("saved");

        // Get.
        var getResp = rest.exchange(
                url(tasksUrl),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                Map.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody().get("tasks")).isNotNull();
    }

    @Test
    void retiredV1IssuesRouteReturns404() {
        // The v1 /issues surface was retired; an unmapped path must surface a 404 (NoResourceFoundException
        // handler), not fall through to the catch-all as a 500.
        var resp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/issues"),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void arrayShapedTasksBodyReturns400() {
        // A top-level JSON array can't bind to the tasks Map body — a malformed body is a client error
        // (HttpMessageNotReadableException handler → 400), not a 500.
        var resp = rest.exchange(
                url("/api/v2/projects/" + projectId + "/work-items/" + workItemId + "/tasks"),
                HttpMethod.PUT,
                new HttpEntity<>(List.of(Map.of("id", "T1", "title", "x", "status", "todo")), authHeaders),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
