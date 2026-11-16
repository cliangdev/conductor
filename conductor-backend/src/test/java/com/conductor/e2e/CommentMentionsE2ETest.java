package com.conductor.e2e;

import com.conductor.comment.CommentAgentRequestService;
import com.conductor.support.AbstractE2ETest;
import com.conductor.workflow.RunTokenService;
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
 * Mentions and {@code @agent} against real Postgres through the comments API: token validation, the picker
 * endpoint, a dispatch that cannot start (no AI provider, so no workflow is fired and the shared job queue stays
 * untouched), request status derived from the run, run-token attribution to the asked agent, the loop guard, and
 * anchor context filled in for a quote-only comment. See {@code docs/comments.md}.
 */
class CommentMentionsE2ETest extends AbstractE2ETest {

    @Autowired JdbcTemplate jdbc;
    @Autowired RunTokenService runTokenService;

    HttpHeaders admin;
    HttpHeaders reviewer;
    String projectId;
    String workItemId;
    String docId;
    String projectDocId;
    String adminId;
    String reviewerId;
    String outsiderId;
    String botId;
    String inactiveId;
    String quietId;
    String otherProjectAgentId;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        admin = login("admin-" + tag + "@mentions.test");
        String reviewerEmail = "reviewer-" + tag + "@mentions.test";
        reviewer = login(reviewerEmail);
        login("outsider-" + tag + "@mentions.test");

        projectId = (String) post("/api/v1/projects", Map.of("name", "Mentions " + tag, "description", "t"), admin,
                HttpStatus.CREATED).get("id");
        var invite = post("/api/v1/projects/" + projectId + "/invites",
                Map.of("email", reviewerEmail, "role", "REVIEWER"), admin, HttpStatus.CREATED);
        post("/api/v1/invites/" + invite.get("token") + "/accept", Map.of(), reviewer, HttpStatus.OK);

        adminId = userId("admin-" + tag + "@mentions.test");
        reviewerId = userId(reviewerEmail);
        outsiderId = userId("outsider-" + tag + "@mentions.test");

        workItemId = (String) post("/api/v2/projects/" + projectId + "/work-items",
                Map.of("title", "PRD", "type", "PRD", "workflow", "ENGINEERING"), admin, HttpStatus.CREATED).get("id");
        docId = putDocument("spec.md", "first line\nthe second line\nthe second line again\nthird");
        projectDocId = (String) post("/api/v1/projects/" + projectId + "/docs", Map.of("title", "Notes"), admin,
                HttpStatus.CREATED).get("id");
        rest.exchange(url("/api/v1/projects/" + projectId + "/docs/" + projectDocId), HttpMethod.PUT,
                new HttpEntity<>(Map.of("content", "alpha beta gamma"), admin), Map.class);

        botId = agent(projectId, "Editor Bot", "editor-bot", "ACTIVE", true);
        inactiveId = agent(projectId, "Sleepy Bot", "sleepy-bot", "DRAFT", true);
        quietId = agent(projectId, "Quiet Bot", "quiet-bot", "ACTIVE", false);
        String otherProject = (String) post("/api/v1/projects", Map.of("name", "Other " + tag, "description", "t"),
                admin, HttpStatus.CREATED).get("id");
        otherProjectAgentId = agent(otherProject, "Foreign Bot", "foreign-bot-" + tag, "ACTIVE", true);
    }

    // --- mentions[] ---------------------------------------------------------------------------------------

    @Test
    void validMemberMentionsAreKeptWithTheirRealNameAndForgedOnesBecomePlainText() {
        String body = "ping @[Totally Forged Name](user:" + reviewerId + ") and @[Mallory](user:" + outsiderId
                + ") and @[Nobody](user:" + UUID.randomUUID() + ")";
        Map<String, Object> thread = create(wiTarget(), body, admin);

        assertThat((String) thread.get("content"))
                .contains("(user:" + reviewerId + ")")
                .doesNotContain("Totally Forged Name")
                .contains("@Mallory").doesNotContain(outsiderId)
                .contains("@Nobody");
        List<Map<String, Object>> mentions = mentions(thread);
        assertThat(mentions).hasSize(1);
        assertThat(mentions.get(0)).containsEntry("kind", "USER").containsEntry("id", reviewerId);
        assertThat(mentions.get(0).get("name")).isNotEqualTo("Totally Forged Name");
    }

    @Test
    void onlyActiveAddressableAgentsOfThisProjectCanBeMentioned() {
        String body = "@[a](agent:" + inactiveId + ") @[b](agent:" + quietId + ") @[c](agent:" + otherProjectAgentId
                + ") @[d](agent:no-such-agent)";
        Map<String, Object> thread = create(projectDocTarget(), body, admin);

        assertThat(mentions(thread)).isEmpty();
        assertThat((String) thread.get("content")).isEqualTo("@a @b @c @d");
        assertThat(requestRows(projectId)).isZero();
    }

    @Test
    void anAgentSlugIsRewrittenToItsIdAndRepliesCarryMentionsToo() {
        Map<String, Object> thread = create(projectDocTarget(), "no mention here", admin);
        Map<String, Object> reply = reply(thread.get("id"), "over to @[x](agent:editor-bot) and @[y](user:" + adminId + ")",
                reviewer);
        assertThat(mentions(reply)).extracting(m -> m.get("kind")).containsExactly("AGENT", "USER");
        assertThat(mentions(reply).get(0)).containsEntry("id", botId).containsEntry("name", "Editor Bot");
    }

    @Test
    void editingKeepsExistingMentionsAndValidatesNewOnes() {
        Map<String, Object> thread = create(wiTarget(), "hi @[R](user:" + reviewerId + ")", admin);
        Map<String, Object> edited = patch(thread.get("id"),
                Map.of("content", "hi @[R](user:" + reviewerId + ") and @[M](user:" + outsiderId + ")"), admin);
        assertThat(mentions(edited)).extracting(m -> m.get("id")).containsExactly(reviewerId);
        assertThat((String) edited.get("content")).contains("@M").doesNotContain(outsiderId);
    }

    // --- mentionables -------------------------------------------------------------------------------------

    @Test
    void mentionablesListMembersThenOnlyActiveAddressableAgents() {
        List<Map<String, Object>> all = mentionables("");
        assertThat(all).extracting(m -> m.get("kind")).containsExactly("USER", "USER", "AGENT");
        assertThat(all.get(2)).containsEntry("id", botId).containsEntry("name", "Editor Bot");
        assertThat(asMap(all.get(2).get("avatar")).get("emoji")).isNotNull();

        assertThat(mentionables("edit")).extracting(m -> m.get("id")).containsExactly(botId);
        assertThat(mentionables("zzz")).isEmpty();
        assertThat(mentionables("")).extracting(m -> m.get("id"))
                .doesNotContain(inactiveId, quietId, otherProjectAgentId);

        var outsider = rest.exchange(url("/api/v2/projects/" + projectId + "/comments/mentionables"), HttpMethod.GET,
                new HttpEntity<>(login("nobody-" + UUID.randomUUID() + "@mentions.test")), String.class);
        assertThat(outsider.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- @agent dispatch ----------------------------------------------------------------------------------

    @Test
    void anAgentMentionWithNoAiProviderIsRecordedAsAFailureInPlainWordsAndFiresNothing() {
        Map<String, Object> thread = create(wiTarget(), "@[Editor Bot](agent:" + botId + ") tighten this", admin);

        List<Map<String, Object>> listed = list("?workItemId=" + workItemId + "&documentId=" + docId);
        Map<String, Object> mine = listed.stream().filter(t -> t.get("id").equals(thread.get("id"))).findFirst().orElseThrow();
        List<Map<String, Object>> requests = requests(mine);
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0)).containsEntry("agentId", botId).containsEntry("agentName", "Editor Bot")
                .containsEntry("status", "FAILED");
        assertThat((String) requests.get(0).get("error")).contains("Settings → AI Providers");
        assertThat(asMap(requests.get(0).get("avatar")).get("emoji")).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workflow_definitions WHERE project_id = ? AND name = ?",
                Integer.class, projectId, "conductor-comment-agent")).isZero();
    }

    @Test
    void aFailedRequestDoesNotBlockAskingAgain() {
        Map<String, Object> thread = create(wiTarget(), "@[Editor Bot](agent:" + botId + ") first", admin);
        reply(thread.get("id"), "@[Editor Bot](agent:" + botId + ") second", admin);
        assertThat(requestRows(projectId)).isEqualTo(2);
    }

    @Test
    void requestStatusFollowsTheWorkflowRun() {
        Map<String, Object> thread = create(wiTarget(), "plain", admin);
        String runId = seedRun("RUNNING");
        jdbc.update("INSERT INTO comment_agent_requests (id, project_id, comment_id, agent_id, workflow_run_id) "
                + "VALUES (?, ?, ?, ?, ?)", UUID.randomUUID().toString(), projectId, thread.get("id"), botId, runId);

        assertThat(statusOf(thread)).isEqualTo("RUNNING");
        jdbc.update("UPDATE workflow_runs SET status = 'PENDING' WHERE id = ?", runId);
        assertThat(statusOf(thread)).isEqualTo("PENDING");
        jdbc.update("UPDATE workflow_runs SET status = 'SUCCESS' WHERE id = ?", runId);
        assertThat(statusOf(thread)).isEqualTo("SUCCEEDED");
        jdbc.update("UPDATE workflow_runs SET status = 'FAILED' WHERE id = ?", runId);
        assertThat(statusOf(thread)).isEqualTo("FAILED");
        assertThat((String) requests(find(thread)).get(0).get("error"))
                .isEqualTo(CommentAgentRequestService.RUN_FAILED_MESSAGE);
        jdbc.update("UPDATE workflow_runs SET status = 'CANCELLED' WHERE id = ?", runId);
        assertThat(statusOf(thread)).isEqualTo("FAILED");
    }

    @Test
    void aRunTokenOfACommentAgentRunWritesAsThatAgentAndItsReplyIsLinked() {
        Map<String, Object> thread = create(wiTarget(), "please fix", admin);
        String runId = seedRun("RUNNING");
        String requestId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO comment_agent_requests (id, project_id, comment_id, agent_id, workflow_run_id) "
                + "VALUES (?, ?, ?, ?, ?)", requestId, projectId, thread.get("id"), botId, runId);
        HttpHeaders asRun = bearer(runTokenService.generateMcpToken(runId, projectId, 1));

        // The agent tries to start another run by mentioning an agent: the mention is flattened, no new request.
        Map<String, Object> reply = reply(thread.get("id"),
                "Changed: tightened the intro. cc @[Editor Bot](agent:" + botId + ")", asRun);

        assertThat(reply).containsEntry("authorKind", "AGENT").containsEntry("authorName", "Editor Bot");
        assertThat(asMap(reply.get("authorAgent"))).containsEntry("id", botId).containsEntry("name", "Editor Bot");
        assertThat(mentions(reply)).isEmpty();
        assertThat((String) reply.get("content")).contains("cc @Editor Bot").doesNotContain("agent:");
        assertThat(jdbc.queryForObject("SELECT reply_id FROM comment_agent_requests WHERE id = ?", String.class,
                requestId)).isEqualTo(reply.get("id"));
        assertThat(requestRows(projectId)).isEqualTo(1);

        // A run token that is not a comment-agent run stays the anonymous agent.
        String otherRun = seedRun("RUNNING");
        Map<String, Object> anon = reply(thread.get("id"), "hello",
                bearer(runTokenService.generateMcpToken(otherRun, projectId, 1)));
        assertThat(anon.get("authorAgent")).isNull();
        assertThat((String) anon.get("authorName")).startsWith("Agent (run ");
    }

    @Test
    void textAnAgentWritesNeverStartsAnotherRun() {
        String runId = seedRun("RUNNING");
        Map<String, Object> thread = createAs(wiTarget(), "ask @[Editor Bot](agent:" + botId + ")",
                bearer(runTokenService.generateMcpToken(runId, projectId, 1)));
        assertThat(mentions(thread)).isEmpty();
        assertThat(requestRows(projectId)).isZero();
    }

    // --- Part C: anchor context ---------------------------------------------------------------------------

    @Test
    void aQuoteOnlyAnchorGetsItsContextFromTheDocument() {
        ResponseEntity<Map> resp = rawCreate(wiTarget(), "check this", Map.of("exact", "second line again"), admin);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> anchor = asMap(asMap(resp.getBody()).get("anchor"));
        assertThat(anchor).containsEntry("exact", "second line again");
        assertThat((String) anchor.get("prefix")).endsWith("the ").isNotEmpty();
        assertThat((String) anchor.get("suffix")).startsWith(" third");
    }

    @Test
    void aRepeatedQuoteTakesTheFirstOccurrenceAndAProjectDocWorksToo() {
        Map<String, Object> first = asMap(createAnchored(wiTarget(), Map.of("exact", "second line")).get("anchor"));
        assertThat((String) first.get("suffix")).startsWith(" the second line again");
        Map<String, Object> pd = asMap(createAnchored(projectDocTarget(), Map.of("exact", "beta")).get("anchor"));
        assertThat(pd).containsEntry("prefix", "alpha ");
        assertThat((String) pd.get("suffix")).startsWith(" gamma");
    }

    @Test
    void aQuoteThatIsNotInTheDocumentIsRefused() {
        ResponseEntity<Map> resp = rawCreate(wiTarget(), "x", Map.of("exact", "never written"), admin);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(resp.getBody())).contains("That text isn't in the document");
    }

    @Test
    void anAnchorThatAlreadyHasContextIsLeftAlone() {
        Map<String, Object> anchor = Map.of("exact", "third", "prefix", "browser ", "suffix", " computed");
        Map<String, Object> created = createAnchored(wiTarget(), anchor);
        assertThat(asMap(created.get("anchor"))).containsEntry("prefix", "browser ").containsEntry("suffix", " computed");
    }

    // --- helpers ------------------------------------------------------------------------------------------

    private String agent(String project, String name, String slug, String state, boolean addressable) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO agents (id, project_id, name, slug, provider, config_json, tool_ids, state) "
                        + "VALUES (?, ?, ?, ?, 'claude', ?::jsonb, '[]'::jsonb, ?)",
                id, project, name, slug, "{\"addressable\": " + addressable + "}", state);
        return id;
    }

    private String seedRun(String status) {
        String workflowId = jdbc.query("SELECT id FROM workflow_definitions WHERE project_id = ? AND name = 'seed'",
                rs -> rs.next() ? rs.getString(1) : null, projectId);
        if (workflowId == null) {
            workflowId = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO workflow_definitions (id, project_id, name, yaml, enabled) VALUES (?, ?, 'seed', 'name: seed', false)",
                    workflowId, projectId);
        }
        String runId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO workflow_runs (id, workflow_id, trigger_type, status) VALUES (?, ?, 'workflow_dispatch', ?)",
                runId, workflowId, status);
        return runId;
    }

    private int requestRows(String project) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM comment_agent_requests WHERE project_id = ?", Integer.class,
                project);
    }

    private String userId(String email) {
        return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", String.class, email);
    }

    private String base() {
        return "/api/v2/projects/" + projectId + "/comments";
    }

    private Map<String, Object> wiTarget() {
        return Map.of("workItemId", workItemId, "documentId", docId);
    }

    private Map<String, Object> projectDocTarget() {
        return Map.of("projectDocId", projectDocId);
    }

    private ResponseEntity<Map> rawCreate(Map<String, Object> target, String content, Map<String, Object> anchor,
                                          HttpHeaders as) {
        Map<String, Object> body = new HashMap<>();
        body.put("target", target);
        body.put("content", content);
        if (anchor != null) {
            body.put("anchor", anchor);
        }
        return rest.exchange(url(base()), HttpMethod.POST, new HttpEntity<>(body, as), Map.class);
    }

    private Map<String, Object> createAs(Map<String, Object> target, String content, HttpHeaders as) {
        ResponseEntity<Map> resp = rawCreate(target, content, null, as);
        assertThat(resp.getStatusCode()).as(String.valueOf(resp.getBody())).isEqualTo(HttpStatus.CREATED);
        return asMap(resp.getBody());
    }

    private Map<String, Object> create(Map<String, Object> target, String content, HttpHeaders as) {
        return createAs(target, content, as);
    }

    private Map<String, Object> createAnchored(Map<String, Object> target, Map<String, Object> anchor) {
        ResponseEntity<Map> resp = rawCreate(target, "c", anchor, admin);
        assertThat(resp.getStatusCode()).as(String.valueOf(resp.getBody())).isEqualTo(HttpStatus.CREATED);
        return asMap(resp.getBody());
    }

    private Map<String, Object> patch(Object id, Map<String, Object> body, HttpHeaders as) {
        var resp = rest.exchange(url(base() + "/" + id), HttpMethod.PATCH, new HttpEntity<>(body, as), Map.class);
        assertThat(resp.getStatusCode()).as(String.valueOf(resp.getBody())).isEqualTo(HttpStatus.OK);
        return asMap(resp.getBody());
    }

    private Map<String, Object> reply(Object commentId, String content, HttpHeaders as) {
        return post(base() + "/" + commentId + "/replies", Map.of("content", content), as, HttpStatus.CREATED);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String query) {
        var resp = rest.exchange(url(base() + query), HttpMethod.GET, new HttpEntity<>(admin), List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mentionables(String q) {
        var resp = rest.exchange(url(base() + "/mentionables?q=" + q), HttpMethod.GET, new HttpEntity<>(admin),
                List.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private Map<String, Object> find(Map<String, Object> thread) {
        return list("?workItemId=" + workItemId + "&documentId=" + docId).stream()
                .filter(t -> t.get("id").equals(thread.get("id"))).findFirst().orElseThrow();
    }

    private String statusOf(Map<String, Object> thread) {
        return (String) requests(find(thread)).get(0).get("status");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mentions(Map<String, Object> item) {
        return (List<Map<String, Object>>) item.get("mentions");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> requests(Map<String, Object> thread) {
        return (List<Map<String, Object>>) thread.get("agentRequests");
    }

    private Map<String, Object> post(String path, Map<String, Object> body, HttpHeaders as, HttpStatus expected) {
        var resp = rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, as), Map.class);
        assertThat(resp.getStatusCode()).as(String.valueOf(resp.getBody())).isEqualTo(expected);
        return asMap(resp.getBody());
    }

    private String putDocument(String filename, String content) {
        var resp = rest.exchange(url("/api/v2/projects/" + projectId + "/work-items/" + workItemId + "/documents/" + filename),
                HttpMethod.PUT, new HttpEntity<>(Map.of("content", content, "contentType", "text/markdown"), admin),
                Map.class);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
