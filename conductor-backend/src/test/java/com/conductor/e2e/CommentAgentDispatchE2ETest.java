package com.conductor.e2e;

import com.conductor.comment.CommentAgentDispatchService;
import com.conductor.comment.CommentAgentRequestService;
import com.conductor.comment.CommentAgentWorkflowProvisioner;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.support.AbstractE2ETest;
import com.conductor.workflow.WorkflowValidationResult;
import com.conductor.workflow.WorkflowValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The request ledger and the system workflow against real Postgres: one request in flight per thread and agent
 * (including under a race), the pending-row unique index, stale rows, plain-words refusals, and the provisioner's
 * idempotency, upgrade and name-collision handling. None of it fires a run, so the shared workflow job queue is
 * untouched (see docs/testing-guidelines.md).
 */
class CommentAgentDispatchE2ETest extends AbstractE2ETest {

    @Autowired JdbcTemplate jdbc;
    @Autowired CommentAgentRequestService requests;
    @Autowired CommentAgentWorkflowProvisioner provisioner;
    @Autowired CommentAgentDispatchService dispatch;
    @Autowired WorkflowValidator validator;

    String projectId;
    String commentId;
    String agentA;
    String agentB;
    String draftAgent;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        HttpHeaders admin = login("admin-" + tag + "@dispatch.test");
        projectId = (String) post("/api/v1/projects", Map.of("name", "Dispatch " + tag, "description", "t"), admin)
                .get("id");
        String docId = (String) post("/api/v1/projects/" + projectId + "/docs", Map.of("title", "Notes"), admin).get("id");
        commentId = (String) post("/api/v2/projects/" + projectId + "/comments",
                Map.of("target", Map.of("projectDocId", docId), "content", "hello"), admin).get("id");
        agentA = agent("Agent A", "agent-a", "ACTIVE", true);
        agentB = agent("Agent B", "agent-b", "ACTIVE", true);
        draftAgent = agent("Draft Agent", "draft-agent", "DRAFT", true);
    }

    // --- one in flight per thread and agent --------------------------------------------------------------

    @Test
    void aSecondRequestForTheSameAgentAndThreadWaitsUntilTheFirstIsDone() {
        Optional<String> first = requests.begin(projectId, commentId, agentA);
        assertThat(first).isPresent();
        assertThat(requests.begin(projectId, commentId, agentA)).as("still pending").isEmpty();
        assertThat(requests.begin(projectId, commentId, agentB)).as("another agent is independent").isPresent();

        String run = seedRun("RUNNING");
        requests.recordRun(first.get(), run);
        assertThat(requests.begin(projectId, commentId, agentA)).as("run in flight").isEmpty();

        jdbc.update("UPDATE workflow_runs SET status = 'PENDING' WHERE id = ?", run);
        assertThat(requests.begin(projectId, commentId, agentA)).as("queued run").isEmpty();

        jdbc.update("UPDATE workflow_runs SET status = 'SUCCESS' WHERE id = ?", run);
        assertThat(requests.begin(projectId, commentId, agentA)).as("finished").isPresent();
    }

    @Test
    void aFailedDispatchFreesTheThreadForANewRequest() {
        String first = requests.begin(projectId, commentId, agentA).orElseThrow();
        requests.recordError(first, "Set up an AI provider in Settings → AI Providers so Agent A can run.");

        assertThat(requests.begin(projectId, commentId, agentA)).isPresent();
    }

    @Test
    void aRequestThatNeverGotARunIsClosedAsStaleAndDoesNotBlockForever() {
        String first = requests.begin(projectId, commentId, agentA).orElseThrow();
        jdbc.update("UPDATE comment_agent_requests SET created_at = now() - interval '11 minutes' WHERE id = ?", first);

        assertThat(requests.begin(projectId, commentId, agentA)).isPresent();
        assertThat(jdbc.queryForObject("SELECT error FROM comment_agent_requests WHERE id = ?", String.class, first))
                .isEqualTo(CommentAgentRequestService.NEVER_STARTED_MESSAGE);
        assertThat(requests.statesFor(List.of(commentId))).extracting(s -> s.status().name())
                .containsExactly("FAILED", "PENDING");
    }

    @Test
    void racingMentionsOfTheSameAgentStartExactlyOneRequest() throws Exception {
        int racers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Optional<String>>> results = new java.util.ArrayList<>();
            for (int i = 0; i < racers; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return requests.begin(projectId, commentId, agentA);
                }));
            }
            go.countDown();
            long winners = 0;
            for (Future<Optional<String>> f : results) {
                if (f.get().isPresent()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_agent_requests WHERE comment_id = ?",
                Integer.class, commentId)).isEqualTo(1);
    }

    @Test
    void theDatabaseItselfRefusesTwoPendingRowsForOneThreadAndAgent() {
        insertRequest(agentA, null, null);
        assertThatThrownBy(() -> insertRequest(agentA, null, null)).isInstanceOf(DataIntegrityViolationException.class);
        insertRequest(agentA, null, "failed earlier");
        insertRequest(agentA, seedRun("SUCCESS"), null);
    }

    @Test
    void theLinkedReplyIsTheNewestRequestsWithoutOne() {
        String first = requests.begin(projectId, commentId, agentA).orElseThrow();
        requests.recordError(first, "x");
        String second = requests.begin(projectId, commentId, agentA).orElseThrow();

        requests.linkReply(commentId, agentA, "reply-1");

        assertThat(jdbc.queryForObject("SELECT reply_id FROM comment_agent_requests WHERE id = ?", String.class, second))
                .isEqualTo("reply-1");
    }

    // --- refusals in plain words ---------------------------------------------------------------------------

    @Test
    void anInactiveAgentIsRefusedOnTheThreadInPlainWords() {
        dispatch.dispatch(projectId, commentId, List.of(draftAgent), "Ann");

        var states = requests.statesFor(List.of(commentId));
        assertThat(states).hasSize(1);
        assertThat(states.get(0).status()).isEqualTo(CommentAgentRequestService.Status.FAILED);
        assertThat(states.get(0).error()).isEqualTo("Draft Agent isn't active, so it can't respond. "
                + "Activate it under Automation → Agents.");
    }

    @Test
    void anAgentWithNoProviderCredentialIsRefusedAndNoWorkflowIsCreated() {
        dispatch.dispatch(projectId, commentId, List.of(agentA), "Ann");

        var states = requests.statesFor(List.of(commentId));
        assertThat(states.get(0).error()).isEqualTo("Set up an AI provider in Settings → AI Providers so Agent A can run.");
        assertThat(workflowCount()).isZero();
    }

    @Test
    void aDeletedCommentOrForeignAgentDispatchesNothing() {
        dispatch.dispatch(projectId, "no-such-comment", List.of(agentA), "Ann");
        dispatch.dispatch(projectId, commentId, List.of(UUID.randomUUID().toString()), "Ann");

        assertThat(requests.statesFor(List.of(commentId))).isEmpty();
    }

    // --- the system workflow ---------------------------------------------------------------------------------

    @Test
    void theSeededYamlPassesTheRealWorkflowValidator() {
        WorkflowDefinition def = provisioner.ensure(projectId);

        WorkflowValidationResult result = validator.validate(def.getYaml(), Set.of());

        assertThat(result.getErrors()).isEmpty();
        assertThat(CommentAgentWorkflowProvisioner.hasMarker(def.getYaml())).isTrue();
        assertThat(def.getYaml()).contains("agent: ${{ event.agentId }}").contains("comment:write_document");
    }

    @Test
    void provisioningIsIdempotent() {
        WorkflowDefinition first = provisioner.ensure(projectId);
        WorkflowDefinition second = provisioner.ensure(projectId);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(workflowCount()).isEqualTo(1);
    }

    @Test
    void anOutdatedCopyOfTheYamlIsRefreshedInPlace() {
        WorkflowDefinition seeded = provisioner.ensure(projectId);
        jdbc.update("UPDATE workflow_definitions SET yaml = ? WHERE id = ?",
                CommentAgentWorkflowProvisioner.MARKER + "\nname: conductor-comment-agent\n# an older release's copy\n",
                seeded.getId());

        WorkflowDefinition refreshed = provisioner.ensure(projectId);

        assertThat(refreshed.getId()).isEqualTo(seeded.getId());
        assertThat(jdbc.queryForObject("SELECT yaml FROM workflow_definitions WHERE id = ?", String.class, seeded.getId()))
                .isEqualTo(seeded.getYaml()).doesNotContain("an older release");
    }

    @Test
    void aUsersOwnWorkflowWithThatNameIsNeverOverwritten() {
        jdbc.update("INSERT INTO workflow_definitions (id, project_id, name, yaml, enabled) VALUES (?, ?, ?, 'name: mine', true)",
                UUID.randomUUID().toString(), projectId, CommentAgentWorkflowProvisioner.WORKFLOW_NAME);

        assertThatThrownBy(() -> provisioner.ensure(projectId))
                .isInstanceOf(CommentAgentWorkflowProvisioner.NameTakenException.class);
        assertThat(jdbc.queryForObject("SELECT yaml FROM workflow_definitions WHERE project_id = ? AND name = ?",
                String.class, projectId, CommentAgentWorkflowProvisioner.WORKFLOW_NAME)).isEqualTo("name: mine");

        // And the dispatcher reports it on the thread instead of throwing.
        dispatch.dispatch(projectId, commentId, List.of(agentA), "Ann");
        assertThat(requests.statesFor(List.of(commentId))).hasSize(1);
    }

    // --- helpers --------------------------------------------------------------------------------------------

    private int workflowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM workflow_definitions WHERE project_id = ? AND name = ?",
                Integer.class, projectId, CommentAgentWorkflowProvisioner.WORKFLOW_NAME);
    }

    private void insertRequest(String agentId, String runId, String error) {
        jdbc.update("INSERT INTO comment_agent_requests (id, project_id, comment_id, agent_id, workflow_run_id, error) "
                + "VALUES (?, ?, ?, ?, ?, ?)", UUID.randomUUID().toString(), projectId, commentId, agentId, runId, error);
    }

    private String agent(String name, String slug, String state, boolean addressable) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO agents (id, project_id, name, slug, provider, config_json, tool_ids, state) "
                        + "VALUES (?, ?, ?, ?, 'claude', ?::jsonb, '[]'::jsonb, ?)",
                id, projectId, name, slug, "{\"addressable\": " + addressable + "}", state);
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

    private Map<String, Object> post(String path, Map<String, Object> body, HttpHeaders as) {
        var resp = rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, as), Map.class);
        assertThat(resp.getStatusCode()).as(String.valueOf(resp.getBody())).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked") Map<String, Object> result = resp.getBody();
        return result;
    }

    private HttpHeaders login(String email) {
        var login = rest.postForEntity(url("/api/v1/auth/local"), Map.of("email", email, "password", "conductor"), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth((String) login.getBody().get("accessToken"));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
