package com.conductor.e2e;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A member of project A must not reach project B's Work Items by naming B's item id under A's path.
 * Every Work Item sub-resource answers 404 (the item is not in that project), while the owner of B
 * still succeeds on the same calls under B's own path. Also covers the workflow listing's membership
 * check and webhook-token visibility.
 */
class WorkItemSubResourceAccessE2ETest extends AbstractE2ETest {

    HttpHeaders aHeaders;
    HttpHeaders bHeaders;
    String aUserId;
    String projectA;
    String projectB;
    String itemB;
    String docB;
    String commentB;

    @BeforeEach
    void setup() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var a = login("a-" + suffix + "@wi-access.test");
        aHeaders = bearer((String) a.get("accessToken"));
        aUserId = (String) ((Map<?, ?>) a.get("user")).get("id");
        bHeaders = bearer((String) login("b-" + suffix + "@wi-access.test").get("accessToken"));

        projectA = createProject(aHeaders, "Access A");
        projectB = createProject(bHeaders, "Access B");

        var item = rest.exchange(url("/api/v2/projects/" + projectB + "/work-items"), HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "B item", "type", "PRD", "workflow", "ENGINEERING"), bHeaders),
                Map.class);
        assertThat(item.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        itemB = (String) item.getBody().get("id");

        var doc = rest.exchange(url(base(projectB) + "/documents"), HttpMethod.POST,
                new HttpEntity<>(Map.of("filename", "spec.md", "content", "one\ntwo", "contentType", "text/markdown"),
                        bHeaders), Map.class);
        assertThat(doc.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        docB = (String) doc.getBody().get("id");

        var comment = rest.exchange(url(base(projectB) + "/comments"), HttpMethod.POST,
                new HttpEntity<>(Map.of("documentId", docB, "content", "note", "lineNumber", 1), bHeaders), Map.class);
        assertThat(comment.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        commentB = (String) comment.getBody().get("id");
    }

    @Test
    void ownerOfTheProjectStillReadsItsSubResources() {
        for (String path : new String[] {"/documents", "/documents/" + docB, "/comments", "/reviews", "/reviewers",
                "/available-transitions"}) {
            var resp = rest.exchange(url(base(projectB) + path), HttpMethod.GET, new HttpEntity<>(bHeaders), String.class);
            assertThat(resp.getStatusCode()).as("GET " + path).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void memberOfAnotherProjectGetsNotFoundOnReads() {
        for (String path : new String[] {"", "/documents", "/documents/" + docB, "/comments", "/reviews", "/reviewers",
                "/available-transitions", "/assets", "/publish-targets", "/publish-preflight", "/publish-consent",
                "/step-runs", "/metric"}) {
            var resp = rest.exchange(url(base(projectA) + path), HttpMethod.GET, new HttpEntity<>(aHeaders), String.class);
            assertThat(resp.getStatusCode()).as("GET " + path).isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    void tasksAreScopedToTheItemsProject() {
        var put = rest.exchange(url(base(projectB) + "/tasks"), HttpMethod.PUT,
                new HttpEntity<>(Map.of("tasks", List.of()), bHeaders), String.class);
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        var get = rest.exchange(url(base(projectB) + "/tasks"), HttpMethod.GET, new HttpEntity<>(bHeaders), String.class);
        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertNotFound(HttpMethod.GET, base(projectA) + "/tasks", aHeaders, null);
        assertNotFound(HttpMethod.PUT, base(projectA) + "/tasks", aHeaders, Map.of("tasks", List.of()));
    }

    @Test
    void memberOfAnotherProjectGetsNotFoundOnWrites() {
        String a = base(projectA);
        assertNotFound(HttpMethod.POST, a + "/documents", aHeaders,
                Map.of("filename", "x.md", "content", "x", "contentType", "text/markdown"));
        assertNotFound(HttpMethod.POST, a + "/comments", aHeaders,
                Map.of("documentId", docB, "content", "x", "lineNumber", 1));
        assertNotFound(HttpMethod.POST, a + "/comments/" + commentB + "/replies", aHeaders, Map.of("content", "x"));
        assertNotFound(HttpMethod.PATCH, a + "/comments/" + commentB + "/resolve", aHeaders, null);
        assertNotFound(HttpMethod.DELETE, a + "/comments/" + commentB, aHeaders, null);
        assertNotFound(HttpMethod.POST, a + "/reviewers", aHeaders, Map.of("userId", aUserId));
        assertNotFound(HttpMethod.DELETE, a, aHeaders, null);

        // B's item, document and comment are all still there.
        var item = rest.exchange(url(base(projectB)), HttpMethod.GET, new HttpEntity<>(bHeaders), String.class);
        assertThat(item.getStatusCode()).isEqualTo(HttpStatus.OK);
        var comments = rest.exchange(url(base(projectB) + "/comments"), HttpMethod.GET, new HttpEntity<>(bHeaders),
                List.class);
        assertThat(comments.getBody()).hasSize(1);
        var docs = rest.exchange(url(base(projectB) + "/documents"), HttpMethod.GET, new HttpEntity<>(bHeaders),
                List.class);
        assertThat(docs.getBody()).hasSize(1);
    }

    @Test
    void nonMemberOfTheProjectGetsNotFoundOnDocuments() {
        // A is not a member of B at all.
        assertNotFound(HttpMethod.GET, base(projectB) + "/documents", aHeaders, null);
        assertNotFound(HttpMethod.GET, base(projectB) + "/documents/" + docB, aHeaders, null);
        assertNotFound(HttpMethod.POST, base(projectB) + "/documents", aHeaders,
                Map.of("filename", "x.md", "content", "x", "contentType", "text/markdown"));
    }

    @Test
    void workflowReadsRequireMembershipAndAdminSeesWebhookToken() {
        String yaml = """
                name: Hook
                on:
                  webhook: {}
                jobs:
                  j:
                    steps:
                      - name: s
                        uses: docker://myimage:latest
                """;
        var created = rest.exchange(url("/api/v1/projects/" + projectB + "/workflows"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "hook-" + System.nanoTime(), "yaml", yaml), bHeaders), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String workflowId = (String) ((Map<?, ?>) created.getBody().get("workflow")).get("id");

        var own = rest.exchange(url("/api/v1/projects/" + projectB + "/workflows"), HttpMethod.GET,
                new HttpEntity<>(bHeaders), List.class);
        assertThat(own.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(own.getBody().stream().anyMatch(w -> ((Map<?, ?>) w).get("webhookToken") != null)).isTrue();

        var ownOne = rest.exchange(url("/api/v1/projects/" + projectB + "/workflows/" + workflowId), HttpMethod.GET,
                new HttpEntity<>(bHeaders), Map.class);
        assertThat(ownOne.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ownOne.getBody().get("webhookToken")).isNotNull();

        assertNotFound(HttpMethod.GET, "/api/v1/projects/" + projectB + "/workflows", aHeaders, null);
        assertNotFound(HttpMethod.GET, "/api/v1/projects/" + projectB + "/workflows/" + workflowId, aHeaders, null);
    }

    private void assertNotFound(HttpMethod method, String path, HttpHeaders headers, Object body) {
        var resp = rest.exchange(url(path), method, new HttpEntity<>(body, headers), String.class);
        assertThat(resp.getStatusCode()).as(method + " " + path).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private String base(String projectId) {
        return "/api/v2/projects/" + projectId + "/work-items/" + itemB;
    }

    private Map<?, ?> login(String email) {
        var resp = rest.postForEntity(url("/api/v1/auth/local"), Map.of("email", email, "password", "conductor"), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private String createProject(HttpHeaders headers, String name) {
        var resp = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name, "description", "test"), headers), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) resp.getBody().get("id");
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
