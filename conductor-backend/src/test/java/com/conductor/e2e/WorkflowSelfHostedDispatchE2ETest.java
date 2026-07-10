package com.conductor.e2e;

import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.repository.DaemonEventRepository;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.workflow.RunTokenService;
import com.conductor.workflow.WorkflowExecutionEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 dispatch refactor: mixed hosted -> self-hosted -> hosted run. Verifies the whole run no
 * longer routes to the daemon at trigger time (the {@code hasSelfHostedJob} bug this replaces) —
 * the hosted `collect` job runs immediately, only the self-hosted `analyze` job dispatches per-job at
 * readiness with a pointer-only DaemonEvent, and `needs`/secrets interpolate correctly at pickup time
 * via the dispatch-payload endpoint. Also covers the legacy whole-run PATCH shim and the pickup-timeout
 * sweep uses a private @Container per the workflow-job-queue testing convention (a shared queue would
 * let another test's scheduler claim this test's jobs).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
@Testcontainers
class WorkflowSelfHostedDispatchE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

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

    @Autowired TestRestTemplate rest;
    @Autowired WorkflowRunRepository runRepository;
    @Autowired WorkflowJobRunRepository jobRunRepository;
    @Autowired DaemonEventRepository daemonEventRepository;
    @Autowired RunTokenService runTokenService;
    @Autowired WorkflowExecutionEngine executionEngine;
    @Autowired ObjectMapper objectMapper;

    HttpHeaders authHeaders;
    String projectId;

    @BeforeEach
    void setup() {
        wireMock.resetAll();

        var loginResp = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "e2e-self-hosted@example.com", "password", "conductor"),
                Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);

        var projResp = rest.exchange(
                url("/api/v1/projects"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Self-Hosted Dispatch E2E " + System.nanoTime(), "description", "test"), authHeaders),
                Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");
    }

    @Test
    void mixedWorkflow_hostedJobRunsImmediately_selfHostedJobDispatchesPointerEvent_thenNeedsAndSecretsInterpolateAtPickup() {
        wireMock.stubFor(get(urlEqualTo("/collect"))
                .willReturn(aResponse().withStatus(200).withBody("{\"data\":\"hello-from-collect\"}")));
        wireMock.stubFor(get(urlEqualTo("/notify"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        createSecret("SEO_TOKEN", "s3cr3t-value");
        String apiKey = createProjectApiKey();

        String yaml = """
                name: Mixed Dispatch
                on:
                  workflow_dispatch: {}
                jobs:
                  collect:
                    runs-on: conductor
                    steps:
                      - id: gsc
                        name: Collect
                        type: http
                        method: GET
                        url: http://localhost:%d/collect
                        outputs: { data: body.data }
                  analyze:
                    needs: [collect]
                    runs-on: self-hosted
                    env:
                      TOKEN: ${{ secrets.SEO_TOKEN }}
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "Data is ${{ needs.collect.outputs.data }}, token is ${{ secrets.SEO_TOKEN }}"
                          inputs:
                            gsc.json: ${{ needs.collect.outputs.data }}
                  notify:
                    needs: [analyze]
                    runs-on: conductor
                    steps:
                      - id: post
                        name: Notify
                        type: http
                        method: GET
                        url: http://localhost:%d/notify
                """.formatted(wireMock.port(), wireMock.port());

        String workflowId = createWorkflow("mixed-dispatch-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);

        // The hosted `collect` job runs immediately — this is the mixed-run bug fix: previously the
        // entire run routed to the daemon at trigger time and hosted jobs never executed.
        awaitJobStatus(runId, "collect", "SUCCESS");
        wireMock.verify(getRequestedFor(urlEqualTo("/collect")));

        // The self-hosted `analyze` job dispatches per-job at readiness — AWAITING_PICKUP, not routed
        // to the daemon at trigger time.
        awaitJobStatus(runId, "analyze", "AWAITING_PICKUP");

        var analyzeJob = jobs(getRunDetail(workflowId, runId)).stream()
                .filter(j -> "analyze".equals(j.get("jobId")))
                .findFirst().orElseThrow();
        String analyzeJobRunId = (String) analyzeJob.get("id");

        // Pre-created step run exists (PENDING) before the daemon ever fetches the payload.
        List<Map<String, Object>> preCreatedSteps = steps(analyzeJob);
        assertThat(preCreatedSteps).hasSize(1);
        assertThat(preCreatedSteps.get(0).get("stepType")).isEqualTo("claude-code");

        // The DaemonEvent is a pointer only — no env, no steps, no secrets sitting in JSONB.
        var eventsResp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/daemon/events"),
                HttpMethod.GET,
                new HttpEntity<>(apiKeyHeaders(apiKey)),
                Map.class);
        assertThat(eventsResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> daemonEvents = (List<Map<String, Object>>) eventsResp.getBody().get("events");
        var jobEvent = daemonEvents.stream()
                .filter(e -> "workflow.job".equals(e.get("type")))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> pointerPayload = (Map<String, Object>) jobEvent.get("payload");
        assertThat(pointerPayload).containsKeys("eventId", "protocol", "workflowRunId", "jobId", "jobRunId", "projectId", "workflowName");
        assertThat(pointerPayload).doesNotContainKeys("env", "steps", "secrets");
        assertThat(pointerPayload.get("jobId")).isEqualTo("analyze");

        // The daemon fetches the interpolated dispatch payload via REST at pickup time.
        var payloadResp = rest.exchange(
                url("/api/v1/workflow-runs/" + runId + "/jobs/analyze/dispatch-payload"),
                HttpMethod.GET,
                new HttpEntity<>(apiKeyHeaders(apiKey)),
                Map.class);
        assertThat(payloadResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> payload = payloadResp.getBody();
        assertThat(payload.get("jobRunId")).isEqualTo(analyzeJobRunId);
        assertThat(payload.get("protocol")).isEqualTo(2);

        @SuppressWarnings("unchecked")
        Map<String, Object> jobEnv = (Map<String, Object>) payload.get("env");
        assertThat(jobEnv.get("TOKEN")).isEqualTo("s3cr3t-value");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> payloadSteps = (List<Map<String, Object>>) payload.get("steps");
        assertThat(payloadSteps).hasSize(1);
        Map<String, Object> stepPayload = payloadSteps.get(0);
        assertThat(stepPayload.get("workerJobId")).isEqualTo(analyzeJobRunId + ":0");
        assertThat((String) stepPayload.get("prompt"))
                .contains("hello-from-collect")
                .contains("s3cr3t-value");
        assertThat((String) stepPayload.get("inputsJson")).contains("hello-from-collect");

        String runToken = (String) payload.get("runToken");
        assertThat(runToken).isNotBlank();
        assertThat(runTokenService.validateRunToken(runToken, runId)).isTrue();

        @SuppressWarnings("unchecked")
        Map<String, Object> callbacks = (Map<String, Object>) payload.get("callbacks");
        assertThat((String) callbacks.get("logChunkUrlTemplate")).contains(runId);
        assertThat((String) callbacks.get("stepCompleteUrlTemplate")).contains("{workerJobId}");

        // Fetching the payload for a job that isn't AWAITING_PICKUP is a conflict.
        var wrongStatusResp = rest.exchange(
                url("/api/v1/workflow-runs/" + runId + "/jobs/collect/dispatch-payload"),
                HttpMethod.GET,
                new HttpEntity<>(apiKeyHeaders(apiKey)),
                Map.class);
        assertThat(wrongStatusResp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        // Daemon reports success — the downstream hosted job then runs and the whole run completes.
        var completeResp = rest.exchange(
                url("/api/v1/workflow-runs/" + runId + "/jobs/analyze/complete"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("status", "SUCCESS"), apiKeyHeaders(apiKey)),
                Void.class);
        assertThat(completeResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        awaitJobStatus(runId, "analyze", "SUCCESS");
        awaitJobStatus(runId, "notify", "SUCCESS");
        wireMock.verify(getRequestedFor(urlEqualTo("/notify")));

        Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var run = runRepository.findById(runId).orElseThrow();
            assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS);
        });

        // Completing an already-terminal job is an idempotent no-op, not an error.
        var idempotentResp = rest.exchange(
                url("/api/v1/workflow-runs/" + runId + "/jobs/analyze/complete"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("status", "SUCCESS"), apiKeyHeaders(apiKey)),
                Void.class);
        assertThat(idempotentResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void dispatchPayload_wrongProjectApiKey_isForbidden() {
        String apiKey = createProjectApiKey();

        // A second, unrelated project's API key.
        var otherProjResp = rest.exchange(
                url("/api/v1/projects"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Other Project " + System.nanoTime(), "description", "test"), authHeaders),
                Map.class);
        String otherProjectId = (String) otherProjResp.getBody().get("id");
        var otherKeyResp = rest.exchange(
                url("/api/v1/projects/" + otherProjectId + "/api-keys"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "other-key"), authHeaders),
                Map.class);
        String otherApiKey = (String) otherKeyResp.getBody().get("key");

        String yaml = """
                name: Forbidden Check
                on:
                  workflow_dispatch: {}
                jobs:
                  solo:
                    runs-on: self-hosted
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: hi
                """;
        String workflowId = createWorkflow("forbidden-check-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);
        awaitJobStatus(runId, "solo", "AWAITING_PICKUP");

        var resp = rest.exchange(
                url("/api/v1/workflow-runs/" + runId + "/jobs/solo/dispatch-payload"),
                HttpMethod.GET,
                new HttpEntity<>(apiKeyHeaders(otherApiKey)),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // The rightful key still works.
        var okResp = rest.exchange(
                url("/api/v1/workflow-runs/" + runId + "/jobs/solo/dispatch-payload"),
                HttpMethod.GET,
                new HttpEntity<>(apiKeyHeaders(apiKey)),
                Map.class);
        assertThat(okResp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void legacyPatchShim_terminalStatusOnRunWithAwaitingPickupJob_closesJobAndPropagatesToDependent() {
        wireMock.stubFor(get(urlEqualTo("/notify-legacy"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));
        String apiKey = createProjectApiKey();

        String yaml = """
                name: Legacy Shim
                on:
                  workflow_dispatch: {}
                jobs:
                  analyze:
                    runs-on: self-hosted
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: hi
                  notify:
                    needs: [analyze]
                    runs-on: conductor
                    steps:
                      - id: post
                        name: Notify
                        type: http
                        method: GET
                        url: http://localhost:%d/notify-legacy
                """.formatted(wireMock.port());

        String workflowId = createWorkflow("legacy-shim-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);
        awaitJobStatus(runId, "analyze", "AWAITING_PICKUP");

        // Old-protocol daemon reports the whole run terminal directly, bypassing per-job dispatch-payload/complete.
        var patchResp = rest.exchange(
                url("/api/v1/workflow-runs/" + runId),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of("status", "SUCCESS"), apiKeyHeaders(apiKey)),
                Map.class);
        assertThat(patchResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        awaitJobStatus(runId, "analyze", "SUCCESS");
        awaitJobStatus(runId, "notify", "SUCCESS");
        wireMock.verify(getRequestedFor(urlEqualTo("/notify-legacy")));

        Awaitility.await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            var run = runRepository.findById(runId).orElseThrow();
            assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS);
        });
    }

    @Test
    void pickupTimeoutSweep_failsAwaitingPickupJobAndPropagatesToDependent() {
        String yaml = """
                name: Pickup Timeout
                on:
                  workflow_dispatch: {}
                jobs:
                  analyze:
                    runs-on: self-hosted
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: hi
                  notify:
                    needs: [analyze]
                    runs-on: conductor
                    steps:
                      - id: post
                        name: ShouldBeSkipped
                        type: http
                        method: GET
                        url: http://localhost:%d/should-not-be-called
                """.formatted(wireMock.port());

        String workflowId = createWorkflow("pickup-timeout-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);
        awaitJobStatus(runId, "analyze", "AWAITING_PICKUP");

        // Backdate startedAt past the 24h cutoff so this jobRun looks abandoned by the daemon, then
        // invoke the daily sweep directly rather than waiting for its real 2am cron trigger.
        var jobRun = jobRunRepository.findByRunIdAndJobId(runId, "analyze").orElseThrow();
        jobRun.setStartedAt(java.time.OffsetDateTime.now().minusHours(25));
        jobRunRepository.save(jobRun);

        executionEngine.cleanupStuckRuns();

        var failedJobRun = jobRunRepository.findByRunIdAndJobId(runId, "analyze").orElseThrow();
        assertThat(failedJobRun.getStatus()).isEqualTo(WorkflowJobStatus.FAILED);

        wireMock.verify(0, getRequestedFor(urlEqualTo("/should-not-be-called")));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

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

    private String createSecret(String key, String value) {
        var resp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/workflow-secrets"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("key", key, "value", value), authHeaders),
                Map.class);
        assertThat(resp.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.CREATED);
        return key;
    }

    private String createProjectApiKey() {
        var resp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/api-keys"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "daemon-key-" + System.nanoTime()), authHeaders),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) resp.getBody().get("key");
    }

    private HttpHeaders apiKeyHeaders(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(apiKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
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

    private void awaitJobStatus(String runId, String jobId, String expectedStatus) {
        Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    List<Map<String, Object>> jobRuns = jobRunRepository.findByRunId(runId).stream()
                            .filter(jr -> jr.getJobId().equals(jobId))
                            .map(jr -> Map.<String, Object>of("status", jr.getStatus().name()))
                            .toList();
                    assertThat(jobRuns).isNotEmpty();
                    assertThat(jobRuns.get(jobRuns.size() - 1).get("status")).isEqualTo(expectedStatus);
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
