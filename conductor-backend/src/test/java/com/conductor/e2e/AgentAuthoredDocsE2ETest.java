package com.conductor.e2e;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack coverage for docs written by a machine actor — an agent holding a project-scoped
 * credential rather than a user session.
 *
 * <p>This has to run over real HTTP against Postgres: the two things it pins only exist there. The
 * {@code V103} migration is what allows a null author at all (the H2 unit profile builds its schema
 * from the entities and never runs Flyway), and an agent-authored doc is invisible to any listing
 * whose fetch join is an inner join — a bug that no service-level test can see, because the join
 * lives in the repository query.
 */
class AgentAuthoredDocsE2ETest extends AbstractE2ETest {

    HttpHeaders authHeaders;
    HttpHeaders agentHeaders;
    HttpHeaders outsiderHeaders;
    String projectId;

    @BeforeEach
    void setUp() {
        authHeaders = login("e2e-agent-docs@example.com");
        outsiderHeaders = login("e2e-agent-docs-outsider@example.com");

        var project = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Agent Docs E2E", "description", "test"), authHeaders),
                Map.class);
        assertThat(project.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) project.getBody().get("id");

        var key = rest.exchange(url("/api/v1/projects/" + projectId + "/api-keys"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "agent-key-" + System.nanoTime()), authHeaders), Map.class);
        assertThat(key.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        agentHeaders = bearer((String) key.getBody().get("key"));
    }

    private HttpHeaders login(String email) {
        var login = rest.postForEntity(url("/api/v1/auth/local"),
                Map.of("email", email, "password", "conductor"), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return bearer((String) login.getBody().get("accessToken"));
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String docsUrl() {
        return "/api/v1/projects/" + projectId + "/docs";
    }

    private String createFolder(String name, String parentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("parentId", parentId);
        var resp = rest.exchange(url(docsUrl() + "/folders"), HttpMethod.POST,
                new HttpEntity<>(body, agentHeaders), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) resp.getBody().get("id");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAgentCanAuthorADocAndAHumanStillSeesItWithAByline() {
        var created = rest.exchange(url(docsUrl()), HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "Agent Plan", "content", "- [ ] ship it"), agentHeaders),
                Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String docId = (String) created.getBody().get("id");
        assertThat((String) created.getBody().get("createdByName")).isEqualTo("Agent");

        // The listing is where an inner fetch join on createdBy would have swallowed this doc entirely.
        var listed = rest.exchange(url(docsUrl()), HttpMethod.GET, new HttpEntity<>(authHeaders), List.class);
        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map<String, Object>>) listed.getBody())
                .anySatisfy(d -> {
                    assertThat(d.get("id")).isEqualTo(docId);
                    assertThat((String) d.get("createdByName")).isEqualTo("Agent");
                });

        var fetched = rest.exchange(url(docsUrl() + "/" + docId), HttpMethod.GET,
                new HttpEntity<>(authHeaders), Map.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((String) fetched.getBody().get("updatedByName")).isEqualTo("Agent");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anAgentVersionAndCommentCarryALabelAndANullAuthorId() {
        var created = rest.exchange(url(docsUrl()), HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "Agent Notes", "content", "line one"), agentHeaders),
                Map.class);
        String docId = (String) created.getBody().get("id");

        var comment = rest.exchange(url(docsUrl() + "/" + docId + "/comments"), HttpMethod.POST,
                new HttpEntity<>(Map.of("content", "answering the review", "lineNumber", 1), agentHeaders),
                Map.class);
        assertThat(comment.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(comment.getBody().get("authorId")).isNull();
        assertThat((String) comment.getBody().get("authorName")).isEqualTo("Agent");

        // Read back as the human: the mapper runs after the transaction closes, so a null author has
        // to survive that boundary too.
        var comments = rest.exchange(url(docsUrl() + "/" + docId + "/comments"), HttpMethod.GET,
                new HttpEntity<>(authHeaders), List.class);
        assertThat(comments.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map<String, Object>>) comments.getBody()).hasSize(1);

        var versions = rest.exchange(url(docsUrl() + "/" + docId + "/versions"), HttpMethod.GET,
                new HttpEntity<>(authHeaders), List.class);
        assertThat(versions.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<Map<String, Object>>) versions.getBody())
                .isNotEmpty()
                .allSatisfy(v -> {
                    assertThat(v.get("authorId")).isNull();
                    assertThat((String) v.get("authorName")).isEqualTo("Agent");
                });
    }

    @Test
    void anAgentCanTickACheckboxItWrote() {
        var created = rest.exchange(url(docsUrl()), HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "Agent Tasks", "content", "- [ ] alpha"), agentHeaders),
                Map.class);
        String docId = (String) created.getBody().get("id");

        var toggled = rest.exchange(url(docsUrl() + "/" + docId + "/tasks/1"), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("checked", true), agentHeaders), Map.class);
        assertThat(toggled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(toggled.getBody().get("content")).isEqualTo("- [x] alpha");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedFoldersAreListedAlongsideRootOnes() {
        String parent = createFolder("Plans " + System.nanoTime(), null);
        String child = createFolder("Q3", parent);

        var folders = rest.exchange(url(docsUrl() + "/folders"), HttpMethod.GET,
                new HttpEntity<>(authHeaders), List.class);
        assertThat(folders.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> body = (List<Map<String, Object>>) folders.getBody();
        // Sub-folders were creatable but never listed, so the tree UI could not render them and a
        // path-addressed client could not resolve "Plans/Q3" at all.
        assertThat(body).anySatisfy(f -> assertThat(f.get("id")).isEqualTo(parent));
        assertThat(body).anySatisfy(f -> {
            assertThat(f.get("id")).isEqualTo(child);
            assertThat(f.get("parentId")).isEqualTo(parent);
        });
    }

    @Test
    void aNonMemberCannotReadTheProjectsDocs() {
        var resp = rest.exchange(url(docsUrl()), HttpMethod.GET,
                new HttpEntity<>(outsiderHeaders), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void aDocFromAnotherProjectIsNotReachableThroughThisProjectsPath() {
        var otherProject = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Other Docs E2E", "description", "test"), outsiderHeaders),
                Map.class);
        String otherProjectId = (String) otherProject.getBody().get("id");

        var otherDoc = rest.exchange(url("/api/v1/projects/" + otherProjectId + "/docs"), HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "Private", "content", "secret"), outsiderHeaders), Map.class);
        String otherDocId = (String) otherDoc.getBody().get("id");

        // Membership in *this* project is not access to another project's doc, whatever id is in the path.
        var resp = rest.exchange(url(docsUrl() + "/" + otherDocId), HttpMethod.GET,
                new HttpEntity<>(authHeaders), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
