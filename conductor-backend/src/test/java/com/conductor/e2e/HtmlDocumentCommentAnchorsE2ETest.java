package com.conductor.e2e;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end proof, against real Postgres (so V166 and the HQL are exercised), of text-selection comment anchors
 * on HTML documents and the {@code outdated} flag that follows a changed document.
 */
class HtmlDocumentCommentAnchorsE2ETest extends AbstractE2ETest {

    private static final Map<String, Object> ANCHOR = Map.of(
            "exact", "20 of 26", "prefix", "funnel: ", "suffix", " signed up",
            "sectionId", "funnel", "sectionTitle", "Funnel");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    private HttpHeaders headers;
    private String base;

    @BeforeEach
    void setup() {
        String email = "admin-" + UUID.randomUUID() + "@html-anchors.test";
        var login = rest.postForEntity(url("/api/v1/auth/local"),
                Map.of("email", email, "password", "conductor"), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        headers = new HttpHeaders();
        headers.setBearerAuth((String) login.getBody().get("accessToken"));
        headers.setContentType(MediaType.APPLICATION_JSON);

        var project = rest.exchange(url("/api/v1/projects"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "HTML anchors " + UUID.randomUUID(), "description", "test"), headers),
                Map.class);
        assertThat(project.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String projectId = (String) project.getBody().get("id");

        var workItem = rest.exchange(url("/api/v2/projects/" + projectId + "/work-items"), HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "HTML PRD", "type", "PRD", "workflow", "ENGINEERING"), headers),
                Map.class);
        assertThat(workItem.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        base = "/api/v2/projects/" + projectId + "/work-items/" + workItem.getBody().get("id");
    }

    private String putDocument(String filename, String content, String contentType) {
        var resp = rest.exchange(url(base + "/documents/" + filename), HttpMethod.PUT,
                new HttpEntity<>(Map.of("content", content, "contentType", contentType), headers), Map.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        return (String) resp.getBody().get("id");
    }

    private ResponseEntity<Map> postComment(Map<String, Object> body) {
        return rest.exchange(url(base + "/comments"), HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listComments() {
        return rest.exchange(url(base + "/comments"), HttpMethod.GET, new HttpEntity<>(headers), List.class).getBody();
    }

    @Test
    void anchoredCommentOnHtml_isCreatedThenGoesOutdatedWhenTheDocumentChanges() {
        String docId = putDocument("prd.html", "<p>funnel: 20 of 26 signed up</p>", "text/html");

        var created = postComment(Map.of("documentId", docId, "content", "Check this", "anchor", ANCHOR));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("quotedText")).isEqualTo("20 of 26");
        assertThat(created.getBody().get("outdated")).isEqualTo(false);
        assertThat(asMap(created.getBody().get("anchor"))).containsEntry("exact", "20 of 26")
                .containsEntry("sectionId", "funnel").containsEntry("sectionTitle", "Funnel");

        assertThat(listComments().get(0)).containsEntry("outdated", false);

        putDocument("prd.html", "<p>funnel: 21 of 26 signed up</p>", "text/html");

        Map<String, Object> afterEdit = listComments().get(0);
        assertThat(afterEdit).containsEntry("outdated", true);
        assertThat(asMap(afterEdit.get("anchor"))).containsEntry("exact", "20 of 26");
        assertThat(afterEdit).containsEntry("lineStale", false);
    }

    @Test
    void lineNumberOnHtml_returns400() {
        String docId = putDocument("prd.html", "<p>hi</p>", "text/html");

        var resp = postComment(Map.of("documentId", docId, "content", "Old client", "lineNumber", 1));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void anchorOnMarkdown_returns400() {
        String docId = putDocument("spec.md", "# Spec", "text/markdown");

        var resp = postComment(Map.of("documentId", docId, "content", "Anchored", "anchor", ANCHOR));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void neitherLineNumberNorAnchorOnMarkdown_returns400() {
        String docId = putDocument("spec.md", "# Spec", "text/markdown");

        var resp = postComment(Map.of("documentId", docId, "content", "Nothing"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void lineCommentSurvivesTheDocumentSwitchingFromMarkdownToHtml() {
        String docId = putDocument("spec.md", "# Spec\nline two", "text/markdown");
        var created = postComment(Map.of("documentId", docId, "content", "Line one", "lineNumber", 1));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        putDocument("spec.md", "<h1>Spec</h1>\n<p>line two</p>", "text/html");

        Map<String, Object> comment = listComments().get(0);
        assertThat(comment).containsEntry("lineNumber", 1);
        assertThat(comment.get("anchor")).isNull();
        assertThat(comment).containsEntry("outdated", true); // the content did change after it was written
    }

    @Test
    void lineCommentOnUnchangedMarkdown_isNotOutdated() {
        String docId = putDocument("spec.md", "# Spec", "text/markdown");
        postComment(Map.of("documentId", docId, "content", "Line one", "lineNumber", 1));

        Map<String, Object> comment = listComments().get(0);
        assertThat(comment).containsEntry("outdated", false);
        assertThat(comment.get("anchor")).isNull();
    }
}
