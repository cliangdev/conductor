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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The unified comments resource ({@code /api/v2/projects/{p}/comments}) against real Postgres: V204/V205, the
 * kinds on both targets, who may edit and delete, agent authors, the legacy-table triggers, and the two shim
 * APIs (v1 project-doc comments, v2 Work Item comments) keeping their shapes.
 */
class UnifiedCommentsE2ETest extends AbstractE2ETest {

    private static final Map<String, Object> ANCHOR = Map.of("exact", "second", "prefix", "the ", "suffix", " line");

    @Autowired JdbcTemplate jdbc;

    HttpHeaders admin;
    HttpHeaders reviewer;
    HttpHeaders agent;
    HttpHeaders outsider;
    String projectId;
    String workItemId;
    String mdDocId;
    String htmlDocId;
    String projectDocId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        admin = login("admin-" + tag + "@unified-comments.test");
        String reviewerEmail = "reviewer-" + tag + "@unified-comments.test";
        reviewer = login(reviewerEmail);
        outsider = login("outsider-" + tag + "@unified-comments.test");

        projectId = (String) post("/api/v1/projects", Map.of("name", "Unified " + tag, "description", "t"), admin,
                HttpStatus.CREATED).get("id");

        var invite = post("/api/v1/projects/" + projectId + "/invites",
                Map.of("email", reviewerEmail, "role", "REVIEWER"), admin, HttpStatus.CREATED);
        post("/api/v1/invites/" + invite.get("token") + "/accept", Map.of(), reviewer, HttpStatus.OK);

        var key = post("/api/v1/projects/" + projectId + "/api-keys", Map.of("name", "agent-" + tag), admin,
                HttpStatus.CREATED);
        agent = bearer((String) key.get("key"));

        workItemId = (String) post("/api/v2/projects/" + projectId + "/work-items",
                Map.of("title", "PRD", "type", "PRD", "workflow", "ENGINEERING"), admin, HttpStatus.CREATED).get("id");
        mdDocId = putDocument("spec.md", "first line\nthe second line\nthird", "text/markdown");
        htmlDocId = putDocument("page.html", "<p>the second line</p>", "text/html");

        projectDocId = (String) post("/api/v1/projects/" + projectId + "/docs", Map.of("title", "Notes"), admin,
                HttpStatus.CREATED).get("id");
        exchange("/api/v1/projects/" + projectId + "/docs/" + projectDocId, HttpMethod.PUT,
                Map.of("content", "first line\nthe second line\nthird"), admin);
    }

    // --- create / list: kinds x targets -------------------------------------------------------------

    @Test
    void everyKindCanBeCreatedAndListedOnEveryTarget() {
        for (Map<String, Object> target : List.of(wiTarget(mdDocId), projectDocTarget())) {
            Map<String, Object> quote = create(target, "quote", ANCHOR, null, admin);
            assertThat(quote).containsEntry("kind", "QUOTE").containsEntry("status", "OPEN")
                    .containsEntry("authorKind", "USER").containsEntry("quotedText", "second");
            assertThat(quote.get("lineNumber")).isNull();
            assertThat(asMap(quote.get("anchor"))).containsEntry("exact", "second");

            Map<String, Object> line = create(target, "line", null, 2, admin);
            assertThat(line).containsEntry("kind", "LINE").containsEntry("lineNumber", 2)
                    .containsEntry("quotedText", "the second line");
            assertThat(line.get("anchor")).isNull();

            Map<String, Object> whole = create(target, "whole", null, null, admin);
            assertThat(whole).containsEntry("kind", "DOCUMENT");
            assertThat(whole.get("lineNumber")).isNull();
            assertThat(whole.get("quotedText")).isNull();

            List<Map<String, Object>> listed = list(listQuery(target), admin);
            assertThat(listed).extracting(c -> c.get("kind")).containsExactly("QUOTE", "LINE", "DOCUMENT");
            assertThat(listed).extracting(c -> c.get("authorName")).doesNotContainNull();
        }
    }

    @Test
    void workItemTargetsAreScopedToTheirDocumentAndProjectDocsStayApart() {
        create(wiTarget(mdDocId), "on md", null, null, admin);
        create(wiTarget(htmlDocId), "on html", ANCHOR, null, admin);
        create(projectDocTarget(), "on project doc", null, null, admin);

        assertThat(list("?workItemId=" + workItemId + "&documentId=" + mdDocId, admin)).hasSize(1);
        assertThat(list("?workItemId=" + workItemId, admin)).hasSize(2);
        assertThat(list("?projectDocId=" + projectDocId, admin)).hasSize(1);
        assertThat(list("?projectDocId=" + projectDocId, admin).get(0)).containsEntry("projectDocId", projectDocId)
                .containsEntry("documentName", "Notes");
        assertThat(list("?workItemId=" + workItemId + "&documentId=" + mdDocId, admin).get(0))
                .containsEntry("documentName", "spec.md").containsEntry("workItemId", workItemId);
    }

    @Test
    void htmlDocumentsStillRejectALineAndNeitherTargetNorBothIsAccepted() {
        assertThat(rawCreate(wiTarget(htmlDocId), "x", null, 1, admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rawCreate(wiTarget(mdDocId), "x", ANCHOR, 1, admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rawCreate(wiTarget(mdDocId), "  ", null, null, admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        Map<String, Object> both = new HashMap<>(wiTarget(mdDocId));
        both.put("projectDocId", projectDocId);
        assertThat(rawCreate(both, "x", null, null, admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rawCreate(Map.of(), "x", null, null, admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.exchange(url(base()), HttpMethod.GET, new HttpEntity<>(admin), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // An HTML document takes an anchor, or none at all.
        assertThat(create(wiTarget(htmlDocId), "whole page", null, null, admin)).containsEntry("kind", "DOCUMENT");
    }

    @Test
    void aNonMemberCannotReadOrWrite() {
        assertThat(rawCreate(projectDocTarget(), "x", null, null, outsider).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rest.exchange(url(base() + "?projectDocId=" + projectDocId), HttpMethod.GET,
                new HttpEntity<>(outsider), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- status ---------------------------------------------------------------------------------------

    @Test
    void statusFilterResolveAndReopen() {
        Map<String, Object> open = create(projectDocTarget(), "stays open", null, null, admin);
        Map<String, Object> done = create(projectDocTarget(), "gets resolved", null, null, admin);

        // Anyone with access can resolve: here a reviewer who did not write it.
        Map<String, Object> resolved = patch(done.get("id"), Map.of("status", "RESOLVED"), reviewer, HttpStatus.OK);
        assertThat(resolved).containsEntry("status", "RESOLVED");
        assertThat(resolved.get("resolvedAt")).isNotNull();
        assertThat(resolved.get("resolvedByName")).isNotNull();

        assertThat(ids(list("?projectDocId=" + projectDocId + "&status=open", admin))).containsExactly(open.get("id"));
        assertThat(ids(list("?projectDocId=" + projectDocId + "&status=resolved", admin))).containsExactly(done.get("id"));
        assertThat(list("?projectDocId=" + projectDocId + "&status=all", admin)).hasSize(2);
        assertThat(list("?projectDocId=" + projectDocId, admin)).hasSize(2);

        Map<String, Object> reopened = patch(done.get("id"), Map.of("status", "OPEN"), admin, HttpStatus.OK);
        assertThat(reopened).containsEntry("status", "OPEN");
        assertThat(reopened.get("resolvedAt")).isNull();
        assertThat(reopened.get("resolvedByName")).isNull();
        assertThat(list("?projectDocId=" + projectDocId + "&status=resolved", admin)).isEmpty();
    }

    // --- edit and delete permissions -----------------------------------------------------------------

    @Test
    void onlyTheAuthorEditsAndEditingStampsEditedAt() {
        Map<String, Object> mine = create(wiTarget(mdDocId), "original", null, 1, reviewer);
        assertThat(mine.get("editedAt")).isNull();

        patch(mine.get("id"), Map.of("content", "hijack"), admin, HttpStatus.FORBIDDEN);
        patch(mine.get("id"), Map.of("content", "hijack"), agent, HttpStatus.FORBIDDEN);
        patch(mine.get("id"), Map.of(), reviewer, HttpStatus.BAD_REQUEST);

        Map<String, Object> edited = patch(mine.get("id"), Map.of("content", "revised"), reviewer, HttpStatus.OK);
        assertThat(edited).containsEntry("content", "revised");
        assertThat(edited.get("editedAt")).isNotNull();
    }

    @Test
    void repliesAreEditedByTheirAuthorOnlyAndDeletedByAuthorOrAdmin() {
        Map<String, Object> thread = create(projectDocTarget(), "question", null, null, admin);
        Map<String, Object> reply = reply(thread.get("id"), "answer", reviewer);
        assertThat(reply).containsEntry("authorKind", "USER");

        replyPatch(thread.get("id"), reply.get("id"), "hijack", admin, HttpStatus.FORBIDDEN);
        Map<String, Object> edited = replyPatch(thread.get("id"), reply.get("id"), "better answer", reviewer, HttpStatus.OK);
        assertThat(edited).containsEntry("content", "better answer");
        assertThat(edited.get("editedAt")).isNotNull();

        // The agent may not delete a person's reply.
        assertThat(delete(base() + "/" + thread.get("id") + "/replies/" + reply.get("id"), agent))
                .isEqualTo(HttpStatus.FORBIDDEN);
        // A reviewer who is neither author nor admin may not delete someone else's.
        Map<String, Object> adminReply = reply(thread.get("id"), "admin reply", admin);
        assertThat(delete(base() + "/" + thread.get("id") + "/replies/" + adminReply.get("id"), reviewer))
                .isEqualTo(HttpStatus.FORBIDDEN);
        // The admin (a project admin) may delete the reviewer's reply; the author may delete their own.
        assertThat(delete(base() + "/" + thread.get("id") + "/replies/" + reply.get("id"), admin))
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(delete(base() + "/" + thread.get("id") + "/replies/" + adminReply.get("id"), admin))
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(list("?projectDocId=" + projectDocId, admin).get(0).get("replies")).asList().isEmpty();
    }

    @Test
    void commentDeletionFollowsAuthorAdminAndMachineOnlyRules() {
        Map<String, Object> byReviewer = create(projectDocTarget(), "reviewer wrote", null, null, reviewer);
        Map<String, Object> byAdmin = create(projectDocTarget(), "admin wrote", null, null, admin);
        Map<String, Object> byAgent = create(projectDocTarget(), "agent wrote", null, null, agent);

        // A non-author, non-admin reviewer cannot delete the admin's comment; the agent cannot delete a person's.
        assertThat(delete(base() + "/" + byAdmin.get("id"), reviewer)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delete(base() + "/" + byReviewer.get("id"), agent)).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delete(base() + "/" + byAdmin.get("id"), agent)).isEqualTo(HttpStatus.FORBIDDEN);

        // Author, admin and (for its own) the agent can.
        assertThat(delete(base() + "/" + byReviewer.get("id"), reviewer)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(delete(base() + "/" + byAgent.get("id"), agent)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(delete(base() + "/" + byAdmin.get("id"), admin)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(list("?projectDocId=" + projectDocId, admin)).isEmpty();
    }

    @Test
    void anAgentCanCommentOnAndReplyToAWorkItemCommentAndItIsMarkedAsAnAgent() {
        Map<String, Object> thread = create(wiTarget(mdDocId), "from a person", null, 2, admin);

        Map<String, Object> fromAgent = create(wiTarget(mdDocId), "from the agent", ANCHOR, null, agent);
        assertThat(fromAgent).containsEntry("authorKind", "AGENT").containsEntry("authorName", "Agent");
        assertThat(fromAgent.get("authorId")).isNull();

        Map<String, Object> agentReply = reply(thread.get("id"), "on it", agent);
        assertThat(agentReply).containsEntry("authorKind", "AGENT").containsEntry("authorName", "Agent");
        assertThat(agentReply.get("authorId")).isNull();

        // Anyone, an agent included, can resolve; the byline is the agent's.
        Map<String, Object> resolved = patch(thread.get("id"), Map.of("status", "RESOLVED"), agent, HttpStatus.OK);
        assertThat(resolved).containsEntry("resolvedByName", "Agent");

        // The agent's rows survive a human reading them through the old Work Item route, null author and all.
        List<Map<String, Object>> legacy = legacyList();
        assertThat(legacy).hasSize(2);
        assertThat(legacy).anySatisfy(c -> assertThat(c).containsEntry("authorName", "Agent"));
        Map<String, Object> legacyThread = legacy.stream().filter(c -> c.get("id").equals(thread.get("id"))).findFirst().orElseThrow();
        assertThat(asList(legacyThread.get("replies"))).hasSize(1);
    }

    // --- legacy-table triggers ------------------------------------------------------------------------

    @Test
    void writesToTheLegacyDocCommentTablesShowUpInTheUnifiedOnes() {
        String commentId = UUID.randomUUID().toString();
        String replyId = UUID.randomUUID().toString();
        String userId = jdbc.queryForObject("SELECT created_by FROM project_docs WHERE id = ?", String.class, projectDocId);

        jdbc.update("INSERT INTO doc_comments (id, doc_id, author_id, content, line_number, quoted_text) VALUES (?,?,?,?,?,?)",
                commentId, projectDocId, userId, "old code wrote this", 2, "the second line");
        jdbc.update("INSERT INTO doc_comment_replies (id, comment_id, author_id, content) VALUES (?,?,?,?)",
                replyId, commentId, userId, "old reply");
        jdbc.update("INSERT INTO doc_comments (id, doc_id, author_label, content) VALUES (?,?,?,?)",
                UUID.randomUUID().toString(), projectDocId, "Agent (run abcd1234)", "old agent comment");

        List<Map<String, Object>> threads = list("?projectDocId=" + projectDocId, admin);
        assertThat(threads).hasSize(2);
        Map<String, Object> mirrored = threads.stream().filter(c -> c.get("id").equals(commentId)).findFirst().orElseThrow();
        assertThat(mirrored).containsEntry("kind", "LINE").containsEntry("lineNumber", 2)
                .containsEntry("content", "old code wrote this").containsEntry("authorKind", "USER");
        assertThat(asList(mirrored.get("replies"))).hasSize(1);
        assertThat(threads).anySatisfy(c -> assertThat(c).containsEntry("authorName", "Agent (run abcd1234)")
                .containsEntry("authorKind", "AGENT").containsEntry("kind", "DOCUMENT"));

        // Update (resolve) and delete are mirrored too.
        jdbc.update("UPDATE doc_comments SET resolved_at = now(), resolved_by = ?, content = 'edited' WHERE id = ?",
                userId, commentId);
        Map<String, Object> afterUpdate = list("?projectDocId=" + projectDocId + "&status=resolved", admin).get(0);
        assertThat(afterUpdate).containsEntry("id", commentId).containsEntry("content", "edited");

        jdbc.update("DELETE FROM doc_comment_replies WHERE id = ?", replyId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comment_replies WHERE id = ?", Integer.class, replyId)).isZero();
        jdbc.update("DELETE FROM doc_comments WHERE id = ?", commentId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comments WHERE id = ?", Integer.class, commentId)).isZero();
    }

    @Test
    void newCodeWritesTheUnifiedTableOnlyAndDeletingADocCleansUp() {
        Map<String, Object> created = create(projectDocTarget(), "new code", null, null, admin);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM doc_comments WHERE id = ?", Integer.class,
                created.get("id"))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comments WHERE id = ?", Integer.class,
                created.get("id"))).isEqualTo(1);

        // Legacy rows are removed with their doc without tripping the mirroring trigger.
        jdbc.update("INSERT INTO doc_comments (id, doc_id, author_label, content) VALUES (?,?,?,?)",
                UUID.randomUUID().toString(), projectDocId, "Agent", "legacy");
        assertThat(delete("/api/v1/projects/" + projectId + "/docs/" + projectDocId, admin).is2xxSuccessful()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comments WHERE project_doc_id = ?", Integer.class,
                projectDocId)).isZero();
    }

    // --- shims keep their shapes -----------------------------------------------------------------------

    @Test
    void v1ProjectDocCommentEndpointsKeepTheirShape() {
        String v1 = "/api/v1/projects/" + projectId + "/docs/" + projectDocId + "/comments";
        Map<String, Object> line = post(v1, Map.of("content", "on a line", "lineNumber", 2, "quotedText", "the second line"),
                admin, HttpStatus.CREATED);
        assertThat(line).containsEntry("docId", projectDocId).containsEntry("lineNumber", 2)
                .containsEntry("quotedText", "the second line").containsEntry("lineStale", false)
                .containsKeys("id", "authorId", "authorName", "createdAt", "updatedAt", "replies");
        Map<String, Object> whole = post(v1, Map.of("content", "from an agent", "lineNumber", 1), agent, HttpStatus.CREATED);
        assertThat(whole.get("lineNumber")).isEqualTo(1);
        assertThat(whole.get("authorId")).isNull();
        assertThat(whole).containsEntry("authorName", "Agent");

        Map<String, Object> reply = post(v1 + "/" + line.get("id") + "/replies", Map.of("content", "ok"), reviewer,
                HttpStatus.CREATED);
        assertThat(reply).containsEntry("commentId", line.get("id")).containsKeys("id", "authorId", "authorName", "createdAt");

        Map<String, Object> resolved = patchRaw(v1 + "/" + line.get("id") + "/resolve", admin);
        assertThat(resolved).containsEntry("id", line.get("id"));
        assertThat(resolved.get("resolvedAt")).isNotNull();
        assertThat(resolved.get("resolvedByName")).isNotNull();

        List<Map<String, Object>> listed = rest.exchange(url(v1), HttpMethod.GET, new HttpEntity<>(admin), List.class).getBody();
        assertThat(listed).hasSize(2);
        assertThat(asList(listed.get(0).get("replies"))).hasSize(1);

        // The unified resource sees what the v1 endpoints wrote.
        assertThat(list("?projectDocId=" + projectDocId, admin)).extracting(c -> c.get("kind")).containsExactly("LINE", "LINE");

        // Saving the doc still flags unresolved line comments stale (T5 replaces this).
        exchange("/api/v1/projects/" + projectId + "/docs/" + projectDocId, HttpMethod.PUT, Map.of("content", "changed"), admin);
        List<Map<String, Object>> after = rest.exchange(url(v1), HttpMethod.GET, new HttpEntity<>(admin), List.class).getBody();
        assertThat(after).filteredOn(c -> c.get("id").equals(whole.get("id"))).allSatisfy(c -> assertThat(c).containsEntry("lineStale", true));

        assertThat(delete(v1 + "/" + whole.get("id"), agent)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(delete(v1 + "/" + line.get("id"), agent)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void oldWorkItemCommentRoutesKeepTheirShapeAndRules() {
        String wi = "/api/v2/projects/" + projectId + "/work-items/" + workItemId + "/comments";
        Map<String, Object> created = post(wi, Map.of("documentId", mdDocId, "content", "legacy", "lineNumber", 2), admin,
                HttpStatus.CREATED);
        assertThat(created).containsEntry("documentId", mdDocId).containsEntry("lineNumber", 2).containsEntry("outdated", false)
                .containsKeys("id", "authorId", "createdAt");
        // The original rules still hold on this route: markdown rejects an anchor, HTML rejects a line.
        assertThat(rest.exchange(url(wi), HttpMethod.POST,
                new HttpEntity<>(Map.of("documentId", mdDocId, "content", "x", "anchor", ANCHOR), admin), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.exchange(url(wi), HttpMethod.POST,
                new HttpEntity<>(Map.of("documentId", htmlDocId, "content", "x"), admin), Map.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        post(wi + "/" + created.get("id") + "/replies", Map.of("content", "r"), agent, HttpStatus.CREATED);
        Map<String, Object> resolved = patchRaw(wi + "/" + created.get("id") + "/resolve", reviewer);
        assertThat(resolved.get("resolvedAt")).isNotNull();
        assertThat(legacyList()).hasSize(1);
        assertThat(legacyList().get(0)).containsKeys("documentName", "replies", "authorName");
        // And the unified list sees it as a LINE comment.
        assertThat(list("?workItemId=" + workItemId + "&documentId=" + mdDocId, admin).get(0)).containsEntry("kind", "LINE");
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private String base() {
        return "/api/v2/projects/" + projectId + "/comments";
    }

    private Map<String, Object> wiTarget(String documentId) {
        return Map.of("workItemId", workItemId, "documentId", documentId);
    }

    private Map<String, Object> projectDocTarget() {
        return Map.of("projectDocId", projectDocId);
    }

    private String listQuery(Map<String, Object> target) {
        return target.containsKey("projectDocId")
                ? "?projectDocId=" + target.get("projectDocId")
                : "?workItemId=" + target.get("workItemId") + "&documentId=" + target.get("documentId");
    }

    private ResponseEntity<Map> rawCreate(Map<String, Object> target, String content, Map<String, Object> anchor,
                                          Integer line, HttpHeaders as) {
        Map<String, Object> body = new HashMap<>();
        body.put("target", target);
        body.put("content", content);
        if (anchor != null) {
            body.put("anchor", anchor);
        }
        if (line != null) {
            body.put("lineNumber", line);
        }
        return rest.exchange(url(base()), HttpMethod.POST, new HttpEntity<>(body, as), Map.class);
    }

    private Map<String, Object> create(Map<String, Object> target, String content, Map<String, Object> anchor,
                                       Integer line, HttpHeaders as) {
        ResponseEntity<Map> resp = rawCreate(target, content, anchor, line, as);
        assertThat(resp.getStatusCode()).as(String.valueOf(resp.getBody())).isEqualTo(HttpStatus.CREATED);
        return asMap(resp.getBody());
    }

    private Map<String, Object> patch(Object id, Map<String, Object> body, HttpHeaders as, HttpStatus expected) {
        var resp = rest.exchange(url(base() + "/" + id), HttpMethod.PATCH, new HttpEntity<>(body, as), Map.class);
        assertThat(resp.getStatusCode()).as(String.valueOf(resp.getBody())).isEqualTo(expected);
        return asMap(resp.getBody());
    }

    private Map<String, Object> reply(Object commentId, String content, HttpHeaders as) {
        return post(base() + "/" + commentId + "/replies", Map.of("content", content), as, HttpStatus.CREATED);
    }

    private Map<String, Object> replyPatch(Object commentId, Object replyId, String content, HttpHeaders as,
                                           HttpStatus expected) {
        var resp = rest.exchange(url(base() + "/" + commentId + "/replies/" + replyId), HttpMethod.PATCH,
                new HttpEntity<>(Map.of("content", content), as), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(expected);
        return asMap(resp.getBody());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String query, HttpHeaders as) {
        var resp = rest.exchange(url(base() + query), HttpMethod.GET, new HttpEntity<>(as), List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> legacyList() {
        return rest.exchange(url("/api/v2/projects/" + projectId + "/work-items/" + workItemId + "/comments"),
                HttpMethod.GET, new HttpEntity<>(admin), List.class).getBody();
    }

    private HttpStatus delete(String path, HttpHeaders as) {
        return HttpStatus.valueOf(rest.exchange(url(path), HttpMethod.DELETE, new HttpEntity<>(as), String.class)
                .getStatusCode().value());
    }

    private Map<String, Object> patchRaw(String path, HttpHeaders as) {
        var resp = rest.exchange(url(path), HttpMethod.PATCH, new HttpEntity<>(as), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return asMap(resp.getBody());
    }

    private Map<String, Object> post(String path, Map<String, Object> body, HttpHeaders as, HttpStatus expected) {
        var resp = rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, as), Map.class);
        assertThat(resp.getStatusCode()).as(String.valueOf(resp.getBody())).isEqualTo(expected);
        return asMap(resp.getBody());
    }

    private void exchange(String path, HttpMethod method, Map<String, Object> body, HttpHeaders as) {
        var resp = rest.exchange(url(path), method, new HttpEntity<>(body, as), Map.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
    }

    private String putDocument(String filename, String content, String contentType) {
        var resp = rest.exchange(url("/api/v2/projects/" + projectId + "/work-items/" + workItemId + "/documents/" + filename),
                HttpMethod.PUT, new HttpEntity<>(Map.of("content", content, "contentType", contentType), admin), Map.class);
        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        return (String) resp.getBody().get("id");
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

    private static List<Object> ids(List<Map<String, Object>> threads) {
        return threads.stream().map(t -> t.get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object o) {
        return (List<Object>) o;
    }
}
