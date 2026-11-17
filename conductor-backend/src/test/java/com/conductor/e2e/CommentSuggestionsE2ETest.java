package com.conductor.e2e;

import com.conductor.support.AbstractE2ETest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Suggested edits (V208): create, suggestion-check, accept, reject and their guard rails, on both targets. */
class CommentSuggestionsE2ETest extends AbstractE2ETest {

    private static final String MD = "# Plan\n\nShip in March.\n\nBudget is 10k.\n\nRepeat me. Repeat me.\n";

    @Autowired JdbcTemplate jdbc;

    HttpHeaders admin;
    HttpHeaders agent;
    String projectId;
    String workItemId;
    String docId;
    String projectDocId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        admin = login("admin-" + tag + "@suggest.test");
        projectId = (String) post("/api/v1/projects", Map.of("name", "Suggest " + tag, "description", "t"), admin,
                HttpStatus.CREATED).get("id");
        var key = post("/api/v1/projects/" + projectId + "/api-keys", Map.of("name", "agent-" + tag), admin,
                HttpStatus.CREATED);
        agent = bearer((String) key.get("key"));
        workItemId = (String) post("/api/v2/projects/" + projectId + "/work-items",
                Map.of("title", "PRD", "type", "PRD", "workflow", "ENGINEERING"), admin, HttpStatus.CREATED).get("id");
        docId = (String) rest.exchange(url("/api/v2/projects/" + projectId + "/work-items/" + workItemId + "/documents/spec.md"),
                HttpMethod.PUT, new HttpEntity<>(Map.of("content", MD, "contentType", "text/markdown"), admin), Map.class)
                .getBody().get("id");
        projectDocId = (String) post("/api/v1/projects/" + projectId + "/docs", Map.of("title", "Notes"), admin,
                HttpStatus.CREATED).get("id");
        rest.exchange(url("/api/v1/projects/" + projectId + "/docs/" + projectDocId), HttpMethod.PUT,
                new HttpEntity<>(Map.of("content", MD), admin), Map.class);
    }

    private Map<String, Object> wi() {
        return Map.of("workItemId", workItemId, "documentId", docId);
    }

    private Map<String, Object> pd() {
        return Map.of("projectDocId", projectDocId);
    }

    private String base() {
        return "/api/v2/projects/" + projectId + "/comments";
    }

    private Map<String, Object> anchor(String exact, String prefix, String suffix) {
        return Map.of("exact", exact, "prefix", prefix, "suffix", suffix);
    }

    private String wiContent() {
        return jdbc.queryForObject("SELECT content FROM documents WHERE id = ?", String.class, docId);
    }

    private String pdContent() {
        return jdbc.queryForObject("SELECT content FROM project_docs WHERE id = ?", String.class, projectDocId);
    }

    private Map<String, Object> suggest(Map<String, Object> target, Map<String, Object> anchor, String text,
                                        HttpHeaders as, HttpStatus expected) {
        Map<String, Object> body = new HashMap<>();
        body.put("target", target);
        body.put("content", "Suggesting");
        if (anchor != null) {
            body.put("anchor", anchor);
        }
        body.put("suggestion", Map.of("text", text));
        return post(base(), body, as, expected);
    }

    private Map<String, Object> decide(Object id, String decision, HttpHeaders as, HttpStatus expected) {
        return post(base() + "/" + id + "/suggestion-decision", Map.of("decision", decision), as, expected);
    }

    @Test
    void createsAPendingSuggestionWithTheShapeTheUiNeeds() {
        Map<String, Object> c = suggest(wi(), anchor("Ship in March.", "Plan ", " Budget"), "Ship in April.", admin,
                HttpStatus.CREATED);
        assertThat(c).containsEntry("kind", "QUOTE");
        Map<?, ?> s = (Map<?, ?>) c.get("suggestion");
        assertThat(s.get("text")).isEqualTo("Ship in April.");
        assertThat(s.get("status")).isEqualTo("PENDING");
        assertThat(s.get("decidedByName")).isNull();
        assertThat(s.get("decidedAt")).isNull();

        // An ordinary comment has no suggestion.
        Map<String, Object> plain = post(base(), Map.of("target", wi(), "content", "hm",
                "anchor", anchor("Ship in March.", "Plan ", " Budget")), admin, HttpStatus.CREATED);
        assertThat(plain.get("suggestion")).isNull();
    }

    @Test
    void ambiguousOrMissingQuoteIs422AndNonQuoteIs400() {
        // "Repeat me." appears twice with the same neighbours of one kind -> still two source places? Rendered has two,
        // source has two, so it IS locatable by rank. A quote spanning formatting is the refusal case.
        put("# T\n\nThis is **very** good.\n");
        Map<String, Object> r = suggest(wi(), anchor("This is very good.", "", ""), "x", admin, HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat((String) r.get("detail")).contains("comment instead");

        suggest(wi(), anchor("not in the document", "", ""), "x", admin, HttpStatus.UNPROCESSABLE_CONTENT);

        // No anchor -> 400 (line comment and whole-document alike).
        suggest(wi(), null, "x", admin, HttpStatus.BAD_REQUEST);
        Map<String, Object> line = new HashMap<>();
        line.put("target", wi());
        line.put("content", "c");
        line.put("lineNumber", 1);
        line.put("suggestion", Map.of("text", "x"));
        post(base(), line, admin, HttpStatus.BAD_REQUEST);

        // Same text is not a suggestion.
        put(MD);
        suggest(wi(), anchor("Ship in March.", "Plan ", " Budget"), "Ship in March.", admin, HttpStatus.BAD_REQUEST);
    }

    @Test
    void suggestionCheckTellsTheComposerWhetherToEnableTheToggle() {
        Map<String, Object> ok = post(base() + "/suggestion-check",
                Map.of("target", wi(), "anchor", anchor("Ship in March.", "", "")), admin, HttpStatus.OK);
        assertThat(ok).containsEntry("suggestable", true);
        assertThat(ok.get("reason")).isNull();

        put("# T\n\nThis is **very** good.\n");
        Map<String, Object> no = post(base() + "/suggestion-check",
                Map.of("target", wi(), "anchor", anchor("This is very good.", "", "")), admin, HttpStatus.OK);
        assertThat(no).containsEntry("suggestable", false);
        assertThat((String) no.get("reason")).contains("comment instead");

        Map<String, Object> proj = post(base() + "/suggestion-check",
                Map.of("target", pd(), "anchor", anchor("Budget is 10k.", "", "")), admin, HttpStatus.OK);
        assertThat(proj).containsEntry("suggestable", true);
    }

    @Test
    void acceptingEditsAWorkItemDocumentReanchorsOthersAndResolvesTheThread() {
        Map<String, Object> sug = suggest(wi(), anchor("Ship in March.", "Plan ", " Budget"), "Ship in April.", admin,
                HttpStatus.CREATED);
        Map<String, Object> other = post(base(), Map.of("target", wi(), "content", "budget?",
                "anchor", anchor("Budget is 10k.", "Ship in March. ", " Repeat")), admin, HttpStatus.CREATED);

        Map<String, Object> done = decide(sug.get("id"), "ACCEPT", admin, HttpStatus.OK);
        assertThat(wiContent()).isEqualTo(MD.replace("Ship in March.", "Ship in April."));
        assertThat(done).containsEntry("status", "RESOLVED");
        Map<?, ?> s = (Map<?, ?>) done.get("suggestion");
        assertThat(s.get("status")).isEqualTo("ACCEPTED");
        assertThat(s.get("decidedByName")).isNotNull();
        assertThat(s.get("decidedAt")).isNotNull();
        assertThat(done.get("resolvedAt")).isNotNull();

        Map<String, Object> o = thread(other.get("id"));
        assertThat(o).containsEntry("anchorStatus", "ANCHORED").containsEntry("outdated", false);
        assertThat(((Map<?, ?>) o.get("anchor")).get("prefix").toString()).contains("Ship in April.");
        // The accepted suggestion itself is resolved history, not orphan noise.
        assertThat(thread(sug.get("id"))).containsEntry("status", "RESOLVED");
        assertThat(thread(sug.get("id")).get("anchorStatus")).isNull();
    }

    @Test
    void acceptingOnAProjectDocCreatesAVersion() {
        Integer before = jdbc.queryForObject("SELECT count(*) FROM doc_versions WHERE doc_id = ?", Integer.class, projectDocId);
        Map<String, Object> sug = suggest(pd(), anchor("Budget is 10k.", "Ship in March. ", " Repeat"), "Budget is 12k.",
                admin, HttpStatus.CREATED);
        decide(sug.get("id"), "ACCEPT", admin, HttpStatus.OK);
        assertThat(pdContent()).isEqualTo(MD.replace("Budget is 10k.", "Budget is 12k."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM doc_versions WHERE doc_id = ?", Integer.class, projectDocId))
                .isEqualTo(before + 1);
    }

    @Test
    void aReviewerCanSuggestButOnlyAnEditorCanAcceptOnEitherTarget() {
        String reviewerEmail = "reviewer-" + UUID.randomUUID().toString().substring(0, 8) + "@suggest.test";
        HttpHeaders reviewer = login(reviewerEmail);
        var invite = post("/api/v1/projects/" + projectId + "/invites",
                Map.of("email", reviewerEmail, "role", "REVIEWER"), admin, HttpStatus.CREATED);
        post("/api/v1/invites/" + invite.get("token") + "/accept", Map.of(), reviewer, HttpStatus.OK);

        // Creating (and checking) a suggestion is open to any member.
        post(base() + "/suggestion-check", Map.of("target", pd(), "anchor", anchor("Budget is 10k.", "", "")),
                reviewer, HttpStatus.OK);
        Map<String, Object> onDoc = suggest(pd(), anchor("Budget is 10k.", "Ship in March. ", " Repeat"), "Budget is 12k.",
                reviewer, HttpStatus.CREATED);
        Map<String, Object> onItem = suggest(wi(), anchor("Ship in March.", "Plan ", " Budget"), "Ship in April.",
                reviewer, HttpStatus.CREATED);
        Integer versions = jdbc.queryForObject("SELECT count(*) FROM doc_versions WHERE doc_id = ?", Integer.class, projectDocId);

        Map<String, Object> deniedDoc = decide(onDoc.get("id"), "ACCEPT", reviewer, HttpStatus.FORBIDDEN);
        Map<String, Object> deniedItem = decide(onItem.get("id"), "ACCEPT", reviewer, HttpStatus.FORBIDDEN);
        assertThat(deniedDoc.get("detail")).isEqualTo(deniedItem.get("detail"));
        assertThat(pdContent()).isEqualTo(MD);
        assertThat(wiContent()).isEqualTo(MD);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM doc_versions WHERE doc_id = ?", Integer.class, projectDocId))
                .isEqualTo(versions);
        // The refusal left the suggestion pending, and an editor still can accept it.
        assertThat(jdbc.queryForObject("SELECT suggestion_status FROM comments WHERE id = ?", String.class, onDoc.get("id")))
                .isEqualTo("PENDING");
        decide(onDoc.get("id"), "ACCEPT", admin, HttpStatus.OK);
        assertThat(pdContent()).isEqualTo(MD.replace("Budget is 10k.", "Budget is 12k."));
    }

    @Test
    void acceptingAfterTheTextChangedIs409AndLeavesEverythingAlone() {
        Map<String, Object> sug = suggest(wi(), anchor("Ship in March.", "Plan ", " Budget"), "Ship in April.", admin,
                HttpStatus.CREATED);
        String changed = MD.replace("Ship in March.", "Ship in May.");
        put(changed);

        Map<String, Object> err = decide(sug.get("id"), "ACCEPT", admin, HttpStatus.CONFLICT);
        assertThat((String) err.get("detail")).contains("text changed since this was suggested");
        assertThat(wiContent()).isEqualTo(changed);
        Map<String, Object> t = thread(sug.get("id"));
        assertThat(t).containsEntry("status", "OPEN");
        assertThat(((Map<?, ?>) t.get("suggestion")).get("status")).isEqualTo("PENDING");
    }

    @Test
    void rejectingResolvesWithoutTouchingTheDocument() {
        Map<String, Object> sug = suggest(wi(), anchor("Ship in March.", "Plan ", " Budget"), "Ship in April.", admin,
                HttpStatus.CREATED);
        Map<String, Object> done = decide(sug.get("id"), "REJECT", admin, HttpStatus.OK);
        assertThat(wiContent()).isEqualTo(MD);
        assertThat(done).containsEntry("status", "RESOLVED");
        assertThat(((Map<?, ?>) done.get("suggestion")).get("status")).isEqualTo("REJECTED");
    }

    @Test
    void anAgentCanSuggestButNeverDecide() {
        Map<String, Object> sug = suggest(wi(), anchor("Ship in March.", "Plan ", " Budget"), "Ship in April.", agent,
                HttpStatus.CREATED);
        assertThat(sug).containsEntry("authorKind", "AGENT");
        decide(sug.get("id"), "ACCEPT", agent, HttpStatus.FORBIDDEN);
        decide(sug.get("id"), "REJECT", agent, HttpStatus.FORBIDDEN);
        assertThat(wiContent()).isEqualTo(MD);
        // The person can.
        decide(sug.get("id"), "ACCEPT", admin, HttpStatus.OK);
        assertThat(wiContent()).contains("Ship in April.");
    }

    @Test
    void decidingTwiceOrOnANonSuggestionIsRefused() {
        Map<String, Object> sug = suggest(wi(), anchor("Ship in March.", "Plan ", " Budget"), "Ship in April.", admin,
                HttpStatus.CREATED);
        decide(sug.get("id"), "REJECT", admin, HttpStatus.OK);
        decide(sug.get("id"), "ACCEPT", admin, HttpStatus.CONFLICT);
        assertThat(wiContent()).isEqualTo(MD);

        Map<String, Object> plain = post(base(), Map.of("target", wi(), "content", "hm",
                "anchor", anchor("Ship in March.", "Plan ", " Budget")), admin, HttpStatus.CREATED);
        decide(plain.get("id"), "ACCEPT", admin, HttpStatus.BAD_REQUEST);
    }

    @Test
    void anHtmlDocumentSuggestionEditsOneTextNode() {
        String html = "<p>Launch is <i>soon</i>.</p><p>Budget &amp; scope: small</p>";
        String htmlId = (String) rest.exchange(
                url("/api/v2/projects/" + projectId + "/work-items/" + workItemId + "/documents/page.html"),
                HttpMethod.PUT, new HttpEntity<>(Map.of("content", html, "contentType", "text/html"), admin), Map.class)
                .getBody().get("id");
        Map<String, Object> target = Map.of("workItemId", workItemId, "documentId", htmlId);
        suggest(target, anchor("Launch is soon.", "", ""), "x", admin, HttpStatus.UNPROCESSABLE_CONTENT);
        Map<String, Object> sug = suggest(target, anchor("scope: small", "Budget & ", ""), "scope: large & clear", admin,
                HttpStatus.CREATED);
        decide(sug.get("id"), "ACCEPT", admin, HttpStatus.OK);
        assertThat(jdbc.queryForObject("SELECT content FROM documents WHERE id = ?", String.class, htmlId))
                .isEqualTo("<p>Launch is <i>soon</i>.</p><p>Budget &amp; scope: large &amp; clear</p>");
    }

    // --- helpers ---

    private void put(String content) {
        var resp = rest.exchange(url("/api/v2/projects/" + projectId + "/work-items/" + workItemId + "/documents/spec.md"),
                HttpMethod.PUT, new HttpEntity<>(Map.of("content", content, "contentType", "text/markdown"), admin), Map.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> thread(Object id) {
        var resp = rest.exchange(url(base() + "?workItemId=" + workItemId + "&documentId=" + docId), HttpMethod.GET,
                new HttpEntity<>(admin), java.util.List.class);
        return ((java.util.List<Map<String, Object>>) resp.getBody()).stream()
                .filter(c -> id.equals(c.get("id"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body, HttpHeaders as, HttpStatus expected) {
        var resp = rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, as), Map.class);
        assertThat(resp.getStatusCode()).as(String.valueOf(resp.getBody())).isEqualTo(expected);
        return resp.getBody();
    }

    private HttpHeaders login(String email) {
        var login = rest.postForEntity(url("/api/v1/auth/local"), Map.of("email", email, "password", "conductor"), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        return bearer((String) login.getBody().get("accessToken"));
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
