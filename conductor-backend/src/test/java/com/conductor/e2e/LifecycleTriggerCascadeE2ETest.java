package com.conductor.e2e;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for the generalized lifecycle {@code status_changed} trigger + cascade (#240 §3) against
 * real Postgres. A user authors a lifecycle whose STARTED -> MID -> DONE edges fire on {@code status_changed};
 * a single human PATCH (OPEN -> STARTED) must auto-cascade the Work Item all the way to DONE.
 *
 * <p>This is the mandatory E2E-with-real-data check (#240 §4): the dispatcher re-loads the Work Item and its
 * enrichment reads the lazy assignee inside its own transaction, so an assigned item exercises the exact
 * LazyInitialization path context-boot tests miss.
 */
class LifecycleTriggerCascadeE2ETest extends AbstractE2ETest {

    HttpHeaders authHeaders;

    @BeforeEach
    void login() {
        var loginResp = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "e2e-cascade@example.com", "password", "conductor"),
                Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void statusChangedTriggerCascadesAWorkItemThroughMultipleHops() {
        // Project (creator is ADMIN/CREATOR, may author + publish + change status).
        var projResp = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Cascade E2E", "description", "test"), authHeaders), Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String projectId = (String) projResp.getBody().get("id");

        // A lifecycle whose two automated edges both fire on status_changed, forming a 2-hop cascade:
        //   OPEN --(human)--> STARTED --(status_changed)--> MID --(status_changed)--> DONE
        Map<String, Object> definition = Map.of(
                "schemaVersion", 1, "id", "CASCADE", "area", "CASCADE", "version", 1, "state", "DRAFT",
                "noun", "Task", "default_view", "list", "types", List.of("TASK"),
                "statuses", List.of(
                        Map.of("id", "OPEN", "category", "open", "initial", true),
                        Map.of("id", "STARTED", "category", "in_progress"),
                        Map.of("id", "MID", "category", "in_progress"),
                        Map.of("id", "DONE", "category", "terminal", "terminal", true)),
                "transitions", List.of(
                        Map.of("from", "OPEN", "to", "STARTED", "label", "Start"),
                        Map.of("from", "STARTED", "to", "MID", "label", "Auto 1", "trigger", "status_changed"),
                        Map.of("from", "MID", "to", "DONE", "label", "Auto 2", "trigger", "status_changed")));

        String workflowsUrl = url("/api/v1/projects/" + projectId + "/workflows");
        var createWfResp = rest.exchange(workflowsUrl, HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Cascade Lifecycle", "area", "CASCADE", "definition", definition), authHeaders),
                Map.class);
        assertThat(createWfResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        Map<String, Object> workflow = (Map<String, Object>) createWfResp.getBody().get("workflow");
        String workflowId = (String) workflow.get("id");

        var publishResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/workflows/" + workflowId + "/publish"),
                HttpMethod.POST, new HttpEntity<>(authHeaders), Map.class);
        assertThat(publishResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publishResp.getBody().get("state")).isEqualTo("PUBLISHED");

        // Create a Work Item on the cascade workflow (initial status OPEN).
        var createWiResp = rest.exchange(url("/api/v2/projects/" + projectId + "/work-items"), HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "Cascade WI", "type", "TASK", "workflow", "CASCADE"), authHeaders), Map.class);
        assertThat(createWiResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String workItemId = (String) createWiResp.getBody().get("id");
        String userId = (String) createWiResp.getBody().get("createdBy");
        assertThat(createWiResp.getBody().get("status")).isEqualTo("OPEN");

        String wiUrl = url("/api/v2/projects/" + projectId + "/work-items/" + workItemId);

        // Assign it, so the per-hop enrichment must initialize the lazy assignee on the freshly re-loaded entity.
        var assignResp = rest.exchange(wiUrl, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("assigneeId", userId), authHeaders), Map.class);
        assertThat(assignResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The single human move OPEN -> STARTED must auto-cascade STARTED -> MID -> DONE.
        var patchResp = rest.exchange(wiUrl, HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "STARTED"), authHeaders), Map.class);
        assertThat(patchResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        var getResp = rest.exchange(wiUrl, HttpMethod.GET, new HttpEntity<>(authHeaders), Map.class);
        assertThat(getResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResp.getBody().get("status")).isEqualTo("DONE");
    }
}
