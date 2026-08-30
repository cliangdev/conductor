package com.conductor.e2e;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for the project-scoped skill registry (#240 §3) against real Postgres, and the payoff it
 * unblocks: a user-authored lifecycle Workflow that binds a custom (non-built-in) skill can be published without
 * a backend redeploy. Login → create project → list built-in skills → attempt to publish a statechart binding an
 * unregistered skill (expect 422) → register the skill → publish succeeds.
 */
class ProjectSkillsE2ETest extends AbstractE2ETest {

    HttpHeaders authHeaders;

    @BeforeEach
    void login() {
        var loginResp = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "e2e-skills@example.com", "password", "conductor"),
                Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);
    }

    @Test
    void registerSkillUnblocksCustomLifecyclePublish() {
        // Project (creator is ADMIN/CREATOR, so may register skills + publish).
        var projResp = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Skills E2E", "description", "test"), authHeaders), Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String projectId = (String) projResp.getBody().get("id");

        String skillsUrl = url("/api/v1/projects/" + projectId + "/skills");
        String workflowsUrl = url("/api/v1/projects/" + projectId + "/workflows");

        // --- Built-ins are listed and flagged builtIn=true; the custom skill is absent. ---
        var listResp = rest.exchange(skillsUrl, HttpMethod.GET, new HttpEntity<>(authHeaders), List.class);
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> before = listResp.getBody();
        assertThat(before).anyMatch(s -> "conductor:implement".equals(s.get("id")) && Boolean.TRUE.equals(s.get("builtIn")));
        assertThat(before).noneMatch(s -> "marketing:seo-report".equals(s.get("id")));

        // --- Author a custom SEO lifecycle that binds an unregistered skill. Create succeeds (create does not
        //     validate the statechart); publish is the gate. ---
        // NOTE: the slug must not collide with a workflow the platform seeds into every new project
        // (ENGINEERING, and MARKETING since COND-23) — a project cannot author a second workflow under a
        // seeded slug. This test is about skill registration gating publish; the slug is incidental.
        Map<String, Object> definition = Map.of(
                "schemaVersion", 1, "id", "SEO", "area", "SEO", "version", 1, "state", "DRAFT",
                "noun", "Campaign", "default_view", "list", "types", List.of("SEO_AUDIT"),
                "statuses", List.of(
                        Map.of("id", "OPEN", "category", "open", "initial", true),
                        Map.of("id", "DONE", "category", "terminal", "terminal", true)),
                "transitions", List.of(Map.of(
                        "from", "OPEN", "to", "DONE", "label", "Finish",
                        "steps", List.of(Map.of("kind", "skill", "mode", "BLOCKING", "skill", "marketing:seo-report")))));
        var createResp = rest.exchange(workflowsUrl, HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "SEO Lifecycle", "area", "SEO", "definition", definition), authHeaders),
                Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        Map<String, Object> workflow = (Map<String, Object>) createResp.getBody().get("workflow");
        String workflowId = (String) workflow.get("id");
        assertThat(workflowId).isNotBlank();

        String publishUrl = url("/api/v1/projects/" + projectId + "/workflows/" + workflowId + "/publish");

        // --- Publish is rejected: the bound skill is not registered. (Assert the numeric 422 rather than the
        //     HttpStatus enum — Spring 6.2 renamed the constant UNPROCESSABLE_ENTITY → UNPROCESSABLE_CONTENT.) ---
        var reject = rest.exchange(publishUrl, HttpMethod.POST, new HttpEntity<>(authHeaders), Map.class);
        assertThat(reject.getStatusCode().value()).isEqualTo(422);
        assertThat(reject.getBody().toString()).contains("marketing:seo-report");

        // --- Register the custom skill (201, builtIn=false). ---
        var registerResp = rest.exchange(skillsUrl, HttpMethod.POST,
                new HttpEntity<>(Map.of("id", "marketing:seo-report", "label", "SEO report",
                        "description", "Generates a weekly SEO report"), authHeaders),
                Map.class);
        assertThat(registerResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(registerResp.getBody().get("id")).isEqualTo("marketing:seo-report");
        assertThat(registerResp.getBody().get("builtIn")).isEqualTo(false);

        // --- Re-registering the same id is an idempotent update → 200, not 201. ---
        var reRegisterResp = rest.exchange(skillsUrl, HttpMethod.POST,
                new HttpEntity<>(Map.of("id", "marketing:seo-report", "label", "SEO report v2"), authHeaders),
                Map.class);
        assertThat(reRegisterResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reRegisterResp.getBody().get("label")).isEqualTo("SEO report v2");

        // --- It now appears in the list. ---
        var afterResp = rest.exchange(skillsUrl, HttpMethod.GET, new HttpEntity<>(authHeaders), List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> after = afterResp.getBody();
        assertThat(after).anyMatch(s -> "marketing:seo-report".equals(s.get("id")) && Boolean.FALSE.equals(s.get("builtIn")));

        // --- Publish now succeeds — the same definition, no backend redeploy. ---
        var publishResp = rest.exchange(publishUrl, HttpMethod.POST, new HttpEntity<>(authHeaders), Map.class);
        assertThat(publishResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publishResp.getBody().get("state")).isEqualTo("PUBLISHED");
    }
}
