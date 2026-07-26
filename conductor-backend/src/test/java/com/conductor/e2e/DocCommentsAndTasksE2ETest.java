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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack coverage for reading a doc's comments and ticking its checkboxes.
 *
 * <p>These have to run over real HTTP rather than as a service or {@code @DataJpaTest} slice: both bugs
 * they pin only appear once the transaction has closed. The comment mapper runs in the controller, so
 * a lazily-loaded author blows up with LazyInitializationException *after* the service returns — a
 * slice test with an open session would pass while production 500s.
 */
class DocCommentsAndTasksE2ETest extends AbstractE2ETest {

    HttpHeaders authHeaders;
    String projectId;
    String docId;

    private static final String CONTENT = String.join("\n",
            "# Checklist",
            "",
            "- [ ] first item",
            "- [x] second item",
            "");

    @BeforeEach
    void setUp() {
        var login = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "e2e-doc-comments@example.com", "password", "conductor"),
                Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);

        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) login.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);

        var project = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Doc Comments E2E", "description", "test"), authHeaders),
                Map.class);
        assertThat(project.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) project.getBody().get("id");

        var doc = rest.exchange(url("/api/v1/projects/" + projectId + "/docs"), HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "Checklist"), authHeaders), Map.class);
        assertThat(doc.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        docId = (String) doc.getBody().get("id");

        var update = rest.exchange(url(docUrl()), HttpMethod.PUT,
                new HttpEntity<>(Map.of("content", CONTENT), authHeaders), Map.class);
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private String docUrl() {
        return "/api/v1/projects/" + projectId + "/docs/" + docId;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listComments() {
        var resp = rest.exchange(url(docUrl() + "/comments"), HttpMethod.GET,
                new HttpEntity<>(authHeaders), List.class);
        // The regression: this used to be a 500 (LazyInitializationException on the comment author),
        // and the doc viewer swallowed it, so comments were invisible on every doc.
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    @Test
    void listsCommentsWithAuthorNamesInsteadOf500() {
        var created = rest.exchange(url(docUrl() + "/comments"), HttpMethod.POST,
                new HttpEntity<>(Map.of("content", "needs work", "lineNumber", 3), authHeaders), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> comments = listComments();
        assertThat(comments).hasSize(1);
        assertThat(comments.get(0).get("content")).isEqualTo("needs work");
        assertThat(comments.get(0).get("lineNumber")).isEqualTo(3);
        assertThat((String) comments.get(0).get("authorName")).isNotBlank();
    }

    @Test
    void listsRepliesWithAuthorNames() {
        var created = rest.exchange(url(docUrl() + "/comments"), HttpMethod.POST,
                new HttpEntity<>(Map.of("content", "parent", "lineNumber", 3), authHeaders), Map.class);
        String commentId = (String) created.getBody().get("id");

        var reply = rest.exchange(url(docUrl() + "/comments/" + commentId + "/replies"), HttpMethod.POST,
                new HttpEntity<>(Map.of("content", "a reply"), authHeaders), Map.class);
        assertThat(reply.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> comments = listComments();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> replies = (List<Map<String, Object>>) comments.get(0).get("replies");
        assertThat(replies).hasSize(1);
        assertThat((String) replies.get(0).get("authorName")).isNotBlank();
    }

    @Test
    void resolvingAThreadReturnsTheCommentInsteadOf500() {
        var created = rest.exchange(url(docUrl() + "/comments"), HttpMethod.POST,
                new HttpEntity<>(Map.of("content", "done with this", "lineNumber", 3), authHeaders), Map.class);
        String commentId = (String) created.getBody().get("id");

        var resolved = rest.exchange(url(docUrl() + "/comments/" + commentId + "/resolve"), HttpMethod.PATCH,
                new HttpEntity<>(authHeaders), Map.class);
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolved.getBody().get("resolvedAt")).isNotNull();
        assertThat((String) resolved.getBody().get("authorName")).isNotBlank();
    }

    @Test
    @SuppressWarnings("unchecked")
    void tickingACheckboxPersistsWithoutAVersionOrStaleComments() {
        rest.exchange(url(docUrl() + "/comments"), HttpMethod.POST,
                new HttpEntity<>(Map.of("content", "watch this stay fresh", "lineNumber", 4), authHeaders), Map.class);

        int versionsBefore = rest.exchange(url(docUrl() + "/versions"), HttpMethod.GET,
                new HttpEntity<>(authHeaders), List.class).getBody().size();

        var toggled = rest.exchange(url(docUrl() + "/tasks/3"), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("checked", true), authHeaders), Map.class);
        assertThat(toggled.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Only line 3 flipped; the trailing newline and every other line survived untouched.
        assertThat((String) toggled.getBody().get("content")).isEqualTo(
                CONTENT.replace("- [ ] first item", "- [x] first item"));

        int versionsAfter = rest.exchange(url(docUrl() + "/versions"), HttpMethod.GET,
                new HttpEntity<>(authHeaders), List.class).getBody().size();
        assertThat(versionsAfter).isEqualTo(versionsBefore);

        // Ticking a box can't move a line, so anchors must not be invalidated.
        assertThat(listComments()).allSatisfy(c -> assertThat(c.get("lineStale")).isEqualTo(false));
    }

    @Test
    void togglingALineThatIsNotATaskItemConflicts() {
        var resp = rest.exchange(url(docUrl() + "/tasks/1"), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("checked", true), authHeaders), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void togglingAnOutOfRangeLineIsRejectedAsABadRequest() {
        var resp = rest.exchange(url(docUrl() + "/tasks/999"), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("checked", true), authHeaders), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
