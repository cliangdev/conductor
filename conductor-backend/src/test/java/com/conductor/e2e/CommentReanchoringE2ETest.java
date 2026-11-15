package com.conductor.e2e;

import com.conductor.service.DocumentContent;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Saving a document re-anchors its open comments (V206, {@code CommentAnchorService}): quotes found again carry over
 * and are re-stamped, quotes that are gone are orphaned, line comments follow their line text or go stale. Both
 * targets, through the real save paths and the unified {@code /comments} resource.
 */
class CommentReanchoringE2ETest extends AbstractE2ETest {

    private static final String BEFORE = "# Plan\nIntro paragraph.\nKeep this line\nShipping date is March\nDuplicate\nDuplicate\n";
    private static final String AFTER = "# Plan\nPreface one\nPreface two\nKeep this line\nShipping date is March\nDuplicate\nDuplicate\n";

    HttpHeaders admin;
    String projectId;
    String workItemId;
    String docId;
    String projectDocId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        var login = rest.postForEntity(url("/api/v1/auth/local"),
                Map.of("email", "admin-" + tag + "@reanchor.test", "password", "conductor"), Map.class);
        admin = new HttpHeaders();
        admin.setBearerAuth((String) login.getBody().get("accessToken"));
        admin.setContentType(MediaType.APPLICATION_JSON);

        projectId = (String) send("/api/v1/projects", HttpMethod.POST, Map.of("name", "Reanchor " + tag, "description", "t"))
                .get("id");
        workItemId = (String) send("/api/v2/projects/" + projectId + "/work-items", HttpMethod.POST,
                Map.of("title", "PRD", "type", "PRD", "workflow", "ENGINEERING")).get("id");
        docId = putDocument(BEFORE);
        projectDocId = (String) send("/api/v1/projects/" + projectId + "/docs", HttpMethod.POST, Map.of("title", "Notes"))
                .get("id");
        putProjectDoc(BEFORE);
    }

    @Test
    void aWorkItemDocumentSaveAnchorsMovesAndOrphansComments() {
        Map<String, String> ids = commentOn(Map.of("workItemId", workItemId, "documentId", docId));
        String oldHash = (String) thread(ids.get("quoteKept"), "?workItemId=" + workItemId + "&documentId=" + docId)
                .get("documentHash");
        assertThat(oldHash).isEqualTo(DocumentContent.sha256Hex(BEFORE));

        putDocument(AFTER);

        List<Map<String, Object>> all = list("?workItemId=" + workItemId + "&documentId=" + docId);
        assertOutcomes(all, ids, DocumentContent.sha256Hex(AFTER), true);
    }

    @Test
    void aProjectDocSaveNoLongerMarksEveryCommentStale() {
        Map<String, String> ids = commentOn(Map.of("projectDocId", projectDocId));

        putProjectDoc(AFTER);

        List<Map<String, Object>> all = list("?projectDocId=" + projectDocId);
        assertOutcomes(all, ids, DocumentContent.sha256Hex(AFTER), false);
        assertThat(all).extracting(c -> c.get("lineStale")).containsExactlyInAnyOrder(false, false, true, true, false, false);
    }

    @Test
    void aSaveLeavesResolvedCommentsAlone() {
        Map<String, Object> done = create(Map.of("projectDocId", projectDocId), "done", Map.of("exact", "Intro paragraph."), null);
        send(base() + "/" + done.get("id"), HttpMethod.PATCH, Map.of("status", "RESOLVED"));

        putProjectDoc(AFTER);

        Map<String, Object> after = list("?projectDocId=" + projectDocId).get(0);
        assertThat(after.get("anchorStatus")).isNull();
    }

    private void assertOutcomes(List<Map<String, Object>> all, Map<String, String> ids, String newHash, boolean hashOutdated) {
        Map<String, Object> kept = byId(all, ids.get("quoteKept"));
        assertThat(kept).containsEntry("anchorStatus", "ANCHORED").containsEntry("outdated", false)
                .containsEntry("documentHash", newHash);
        Map<?, ?> anchor = (Map<?, ?>) kept.get("anchor");
        assertThat((String) anchor.get("prefix")).endsWith("Keep this line ").hasSizeLessThanOrEqualTo(32);
        assertThat(anchor.get("suffix")).isEqualTo(" Duplicate Duplicate "); // the paragraph's trailing newline is a space, as in the browser

        Map<String, Object> gone = byId(all, ids.get("quoteGone"));
        assertThat(gone).containsEntry("anchorStatus", "ORPHANED");
        // Untouched: the hash it was written with (project-doc comments carry none).
        assertThat(gone.get("documentHash")).isEqualTo(hashOutdated ? DocumentContent.sha256Hex(BEFORE) : null);
        assertThat(gone.get("anchor")).isNotNull();
        if (hashOutdated) {
            assertThat(gone).containsEntry("outdated", true);
        }

        Map<String, Object> moved = byId(all, ids.get("lineMoved"));
        assertThat(moved).containsEntry("lineNumber", 4).containsEntry("lineStale", false).containsEntry("documentHash", newHash);

        Map<String, Object> removed = byId(all, ids.get("lineGone"));
        assertThat(removed).containsEntry("lineNumber", 2).containsEntry("lineStale", true);

        Map<String, Object> ambiguous = byId(all, ids.get("lineAmbiguous"));
        assertThat(ambiguous).containsEntry("lineStale", true);

        Map<String, Object> whole = byId(all, ids.get("whole"));
        assertThat(whole).containsEntry("documentHash", newHash).containsEntry("lineStale", false);
    }

    /** One of each outcome, on the target, written against {@link #BEFORE}. */
    private Map<String, String> commentOn(Map<String, Object> target) {
        Map<String, String> ids = new HashMap<>();
        ids.put("quoteKept", (String) create(target, "kept", Map.of("exact", "Shipping date is March", "prefix", "Keep this line ",
                "suffix", " Duplicate"), null).get("id"));
        ids.put("quoteGone", (String) create(target, "gone", Map.of("exact", "Intro paragraph."), null).get("id"));
        ids.put("lineMoved", (String) create(target, "moves", null, 3).get("id"));
        ids.put("lineGone", (String) create(target, "removed", null, 2).get("id"));
        ids.put("lineAmbiguous", (String) create(target, "ambiguous", null, 5).get("id"));
        ids.put("whole", (String) create(target, "whole", null, null).get("id"));
        return ids;
    }

    private Map<String, Object> create(Map<String, Object> target, String content, Map<String, Object> anchor, Integer line) {
        Map<String, Object> body = new HashMap<>();
        body.put("target", target);
        body.put("content", content);
        if (anchor != null) {
            body.put("anchor", anchor);
        }
        if (line != null) {
            body.put("lineNumber", line);
        }
        return send(base(), HttpMethod.POST, body);
    }

    private String base() {
        return "/api/v2/projects/" + projectId + "/comments";
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String query) {
        var resp = rest.exchange(url(base() + query), HttpMethod.GET, new HttpEntity<>(admin), List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private Map<String, Object> thread(String id, String query) {
        return byId(list(query), id);
    }

    private static Map<String, Object> byId(List<Map<String, Object>> all, String id) {
        return all.stream().filter(c -> id.equals(c.get("id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> send(String path, HttpMethod method, Map<String, Object> body) {
        var resp = rest.exchange(url(path), method, new HttpEntity<>(body, admin), Map.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).as(String.valueOf(resp.getBody())).isTrue();
        return resp.getBody();
    }

    private String putDocument(String content) {
        return (String) send("/api/v2/projects/" + projectId + "/work-items/" + workItemId + "/documents/spec.md", HttpMethod.PUT,
                Map.of("content", content, "contentType", "text/markdown")).get("id");
    }

    private void putProjectDoc(String content) {
        send("/api/v1/projects/" + projectId + "/docs/" + projectDocId, HttpMethod.PUT, Map.of("content", content));
    }
}
