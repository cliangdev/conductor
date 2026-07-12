package com.conductor.e2e;

import com.conductor.entity.ActionInvocationStatus;
import com.conductor.entity.Connection;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.integration.ActionResult;
import com.conductor.repository.ActionInvocationRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.service.ActionInvocationService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end coverage for the {@code action} workflow step type against
 * {@link com.conductor.integration.connector.local.LocalDiscordConnector} (local profile stub — no
 * real Discord webhook is called). Needs its own private {@code @Container}: it dispatches and
 * enqueues real workflow jobs, and the shared-container base's job-queue scheduler would otherwise
 * be free to pick up jobs from other tests (see docs/testing-guidelines.md).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
@Testcontainers
class WorkflowActionE2ETest {

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
        registry.add("workflow.secrets.key", () -> "dGVzdC13b3JrZmxvdy1zZWNyZXRzLWtleS0zMmJ5dGU=");
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    WorkflowRunRepository runRepository;

    @Autowired
    WorkflowJobRunRepository jobRunRepository;

    @Autowired
    ActionInvocationRepository actionInvocationRepository;

    @Autowired
    ConnectionRepository connectionRepository;

    @Autowired
    ActionInvocationService actionInvocationService;

    HttpHeaders authHeaders;
    String projectId;

    @BeforeEach
    void setup() {
        var loginResp = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "e2e-action@example.com", "password", "conductor"),
                Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);

        var projResp = rest.exchange(
                url("/api/v1/projects"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Workflow Action E2E", "description", "test"), authHeaders),
                Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");

        var connResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/integrations/discord/connections"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("apiKey", "https://discord.com/api/webhooks/1/token"), authHeaders),
                Map.class);
        assertThat(connResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void actionStep_postsToDiscordStub_succeedsAndPersistsOneInvocation_reInvokeUnderSameKeyDoesNotDoubleFire() throws Exception {
        String yaml = """
                name: Notify Discord
                on:
                  workflow_dispatch: {}
                jobs:
                  notify:
                    steps:
                      - id: post
                        uses: action
                        with:
                          connector: discord
                          action: post_message
                          input:
                            content: "hello from conductor"
                """;

        String workflowId = createWorkflow("notify-discord", yaml);
        String runId = dispatchWorkflow(workflowId);
        awaitTerminalStatus(runId);

        var detail = getRunDetail(workflowId, runId);
        assertThat(detail.get("status")).isEqualTo("SUCCESS");

        List<Map<String, Object>> jobs = jobs(detail);
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).get("status")).isEqualTo("SUCCESS");

        List<Map<String, Object>> steps = steps(jobs.get(0));
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).get("status")).isEqualTo("SUCCESS");
        // outputJson is round-tripped through a JSONB column, so PostgreSQL's own jsonb-to-text
        // formatting (space after ':' and ',') applies — parse rather than substring-match.
        String outputJson = (String) steps.get(0).get("outputJson");
        @SuppressWarnings("unchecked")
        Map<String, Object> parsedOutputs = new com.fasterxml.jackson.databind.ObjectMapper().readValue(outputJson, Map.class);
        assertThat(parsedOutputs).containsEntry("message_id", "local-1");
        assertThat(parsedOutputs).containsEntry("channel_id", "local");

        assertThat(actionInvocationRepository.count()).isEqualTo(1);
        var invocation = actionInvocationRepository.findAll().get(0);
        assertThat(invocation.getStatus()).isEqualTo(ActionInvocationStatus.SUCCEEDED);
        assertThat(invocation.getAttempts()).isEqualTo(1);
        assertThat(invocation.getIdempotencyKey()).startsWith("wfstep:");

        // Re-invoking under the SAME idempotency key (the same job run + step, as if the job step
        // were re-driven) must return the stored result without firing the connector again or
        // creating a second row / bumping attempts.
        WorkflowJobRun jobRun = jobRunRepository.findByRunId(runId).get(0);
        Connection conn = connectionRepository.findByProjectIdAndConnectorId(projectId, "discord").get(0);
        String idempotencyKey = "wfstep:" + jobRun.getId() + ":post";

        ActionResult replay = actionInvocationService.invoke(
                conn, "post_message", Map.of("content", "hello from conductor"), idempotencyKey);

        assertThat(replay.success()).isTrue();
        assertThat(replay.output()).containsEntry("message_id", "local-1");
        assertThat(actionInvocationRepository.count()).isEqualTo(1);
        assertThat(actionInvocationRepository.findAll().get(0).getAttempts()).isEqualTo(1);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String createWorkflow(String name, String yaml) {
        var resp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/workflows"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name, "yaml", yaml), authHeaders),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        @SuppressWarnings("unchecked")
        Map<String, Object> workflow = (Map<String, Object>) resp.getBody().get("workflow");
        return (String) workflow.get("id");
    }

    private String dispatchWorkflow(String workflowId) {
        var resp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/workflows/" + workflowId + "/dispatch"),
                HttpMethod.POST,
                new HttpEntity<>(authHeaders),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return (String) resp.getBody().get("id");
    }

    private Map<String, Object> getRunDetail(String workflowId, String runId) {
        var resp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/workflows/" + workflowId + "/runs/" + runId),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private void awaitTerminalStatus(String runId) {
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var run = runRepository.findById(runId).orElseThrow();
                    assertThat(run.getStatus()).isIn(
                            WorkflowRunStatus.SUCCESS,
                            WorkflowRunStatus.FAILED);
                });
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> jobs(Map<String, Object> runDetail) {
        return (List<Map<String, Object>>) runDetail.get("jobs");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> steps(Map<String, Object> job) {
        return (List<Map<String, Object>>) job.get("steps");
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
