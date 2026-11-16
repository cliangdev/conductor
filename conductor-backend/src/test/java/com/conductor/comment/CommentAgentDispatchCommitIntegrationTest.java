package com.conductor.comment;

import com.conductor.entity.User;
import com.conductor.repository.UserRepository;
import com.conductor.service.CommentService;
import com.conductor.service.CommentTarget;
import com.conductor.service.EmailService;
import com.conductor.service.ProjectActor;
import com.conductor.service.view.CommentWithRepliesView;
import com.conductor.workflow.AgentRuntimeResolver;
import com.conductor.workflow.WorkflowJobDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * The real path of an {@code @agent} mention: a comment written in a real transaction, the dispatch after it, the
 * real {@code fireTrigger}. The unit and E2E tests stub around this, which is how a dispatch that joined the
 * already-committed transaction (and so never committed its run) went unnoticed.
 *
 * <p>Own private {@code @Container}: firing a trigger enqueues workflow jobs, which a shared database would let
 * another context's scheduler claim (docs/testing-guidelines.md). The job dispatcher is a mock so nothing runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
@Testcontainers
class CommentAgentDispatchCommitIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("local.storage.path",
                () -> java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "conductor-dispatch-commit-storage")
                        .toString());
    }

    @MockitoBean WorkflowJobDispatcher jobDispatcher;
    @MockitoBean AgentRuntimeResolver runtimeResolver;
    @MockitoSpyBean CommentAgentDispatchService dispatchService;
    @MockitoSpyBean EmailService emailService;

    @LocalServerPort int port;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;
    @Autowired CommentService commentService;
    @Autowired UserRepository userRepository;
    @Autowired PlatformTransactionManager transactionManager;

    HttpHeaders admin;
    User adminUser;
    String projectId;
    String projectDocId;
    String agentId;
    String mention;
    /** Whether a transaction was active when the follow-up started, one entry per call. */
    final List<Boolean> txActiveAtDispatch = new ArrayList<>();
    final List<Boolean> txActiveAtEmail = new ArrayList<>();

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String email = "admin-" + tag + "@dispatch-commit.test";
        admin = login(email);
        adminUser = userRepository.findByEmail(email).orElseThrow();
        projectId = (String) post("/api/v1/projects", Map.of("name", "Commit " + tag, "description", "t")).get("id");
        projectDocId = (String) post("/api/v1/projects/" + projectId + "/docs", Map.of("title", "Notes")).get("id");
        agentId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO agents (id, project_id, name, slug, provider, config_json, tool_ids, state) "
                        + "VALUES (?, ?, ?, ?, 'claude', ?::jsonb, '[]'::jsonb, 'ACTIVE')",
                agentId, projectId, "Helper", "helper-" + tag, "{\"addressable\": true}");
        mention = "@[Helper](agent:" + agentId + ") please look";

        txActiveAtDispatch.clear();
        txActiveAtEmail.clear();
        doAnswer(inv -> {
            txActiveAtDispatch.add(TransactionSynchronizationManager.isActualTransactionActive());
            return inv.callRealMethod();
        }).when(dispatchService).dispatch(anyString(), anyString(), any(), any());
        doAnswer(inv -> {
            txActiveAtEmail.add(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(emailService).sendMentionEmail(any(), any(), any(), any(), any());
    }

    @Test
    void aMentionOverHttpCommitsTheWorkflowRunAndLinksItToTheRequest() {
        Map<String, Object> thread = post("/api/v2/projects/" + projectId + "/comments",
                Map.of("target", Map.of("projectDocId", projectDocId), "content", mention));

        assertRunCommittedAndLinked((String) thread.get("id"));
        assertThat(txActiveAtDispatch).as("dispatch started once the comment's transaction had ended")
                .containsExactly(false);
    }

    @Test
    void aMentionWrittenInsideACallersTransactionCommitsTheRunAfterThatTransactionCommits() {
        String[] commentId = new String[1];
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            CommentWithRepliesView created = commentService.createThread(projectId,
                    new CommentTarget.ProjectDoc(projectDocId), mention, null, null, null, ProjectActor.of(adminUser));
            commentId[0] = created.id();
            // Nothing is dispatched while the caller's transaction is still open.
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_agent_requests WHERE comment_id = ?",
                    Integer.class, created.id())).isZero();
        });

        // The caller's transaction is the one the after-commit work ran behind: the REQUIRES_NEW writes still land.
        assertRunCommittedAndLinked(commentId[0]);
    }

    @Test
    void theMentionEmailAndTheDispatchRunAfterTheWritingTransactionHasEnded() {
        String bobEmail = "bob-" + UUID.randomUUID().toString().substring(0, 8) + "@dispatch-commit.test";
        HttpHeaders bob = login(bobEmail);
        var invite = post("/api/v1/projects/" + projectId + "/invites", Map.of("email", bobEmail, "role", "REVIEWER"));
        rest.exchange(url("/api/v1/invites/" + invite.get("token") + "/accept"), HttpMethod.POST,
                new HttpEntity<>(Map.of(), bob), Map.class);
        String bobId = userRepository.findByEmail(bobEmail).orElseThrow().getId();

        commentService.createThread(projectId, new CommentTarget.ProjectDoc(projectDocId),
                mention + " and @[Bob](user:" + bobId + ")", null, null, null, ProjectActor.of(adminUser));

        assertThat(txActiveAtEmail).containsExactly(false);
        assertThat(txActiveAtDispatch).containsExactly(false);
    }

    private void assertRunCommittedAndLinked(String commentId) {
        List<Map<String, Object>> requests = jdbc.queryForList(
                "SELECT id, workflow_run_id, error FROM comment_agent_requests WHERE comment_id = ?", commentId);
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).get("error")).as("dispatch refused: %s", requests.get(0).get("error")).isNull();
        String runId = (String) requests.get(0).get("workflow_run_id");
        assertThat(runId).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM workflow_runs r JOIN workflow_definitions d ON d.id = r.workflow_id "
                        + "WHERE r.id = ? AND d.project_id = ? AND d.name = ?",
                Integer.class, runId, projectId, CommentAgentWorkflowProvisioner.WORKFLOW_NAME)).isEqualTo(1);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(String path, Map<String, Object> body) {
        var resp = rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, admin), Map.class);
        assertThat(resp.getStatusCode()).as(String.valueOf(resp.getBody())).isEqualTo(HttpStatus.CREATED);
        return resp.getBody();
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
