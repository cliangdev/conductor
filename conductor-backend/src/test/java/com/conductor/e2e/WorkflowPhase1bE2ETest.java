package com.conductor.e2e;

import com.conductor.entity.WorkflowRunStatus;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.workflow.RunTokenService;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E integration tests for Phase 1b features:
 * - Parallel job dispatch and failure isolation
 * - Loop exhaustion and early termination
 * - Condition step routing (then/else branches)
 * - WorkflowValidator API (condition/loop/docker validation)
 * - SSE callbacks (log-chunk auth)
 *
 * Uses the same setup pattern as WorkflowExecutionE2ETest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
@Testcontainers
class WorkflowPhase1bE2ETest {

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
        // AES-256 key: 32 bytes, base64-encoded, required by WorkflowSecretsEncryptionService
        registry.add("workflow.secrets.key", () -> "dGVzdC13b3JrZmxvdy1zZWNyZXRzLWtleS0zMmJ5dGU=");
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    WorkflowRunRepository runRepository;

    @Autowired
    RunTokenService runTokenService;

    HttpHeaders authHeaders;
    String projectId;

    @BeforeEach
    void setup() {
        wireMock.resetAll();

        var loginResp = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "e2e-p1b@example.com", "password", "conductor"),
                Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);

        var projResp = rest.exchange(
                url("/api/v1/projects"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Phase1b E2E " + System.nanoTime(), "description", "test"), authHeaders),
                Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");
    }

    // ── 1. Parallel jobs — both succeed ──────────────────────────────────────

    @Test
    void parallelJobs_twoIndependentJobs_bothRunToSuccess() {
        wireMock.stubFor(get(urlEqualTo("/job-a"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":\"a\"}")));
        wireMock.stubFor(get(urlEqualTo("/job-b"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":\"b\"}")));

        String yaml = """
                name: Parallel Success
                on:
                  workflow_dispatch: {}
                jobs:
                  job-a:
                    steps:
                      - name: Step A
                        type: http
                        method: GET
                        url: http://localhost:%d/job-a
                  job-b:
                    steps:
                      - name: Step B
                        type: http
                        method: GET
                        url: http://localhost:%d/job-b
                """.formatted(wireMock.port(), wireMock.port());

        String workflowId = createWorkflow("parallel-success-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);
        awaitTerminalStatus(runId, 30);

        var detail = getRunDetail(workflowId, runId);
        assertThat(detail.get("status")).isEqualTo("SUCCESS");

        List<Map<String, Object>> jobs = jobs(detail);
        assertThat(jobs).hasSize(2);
        assertThat(jobs.stream().map(j -> j.get("status"))).containsOnly("SUCCESS");

        wireMock.verify(1, getRequestedFor(urlEqualTo("/job-a")));
        wireMock.verify(1, getRequestedFor(urlEqualTo("/job-b")));
    }

    // ── 2. Parallel failure isolation ────────────────────────────────────────

    @Test
    void parallelJobs_oneFails_siblingNotCancelled() {
        wireMock.stubFor(get(urlEqualTo("/job-a-fail"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"boom\"}")));
        wireMock.stubFor(get(urlEqualTo("/job-b-ok"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":\"b\"}")));

        String yaml = """
                name: Parallel Failure Isolation
                on:
                  workflow_dispatch: {}
                jobs:
                  job-a:
                    steps:
                      - name: Fail Step
                        type: http
                        method: GET
                        url: http://localhost:%d/job-a-fail
                  job-b:
                    steps:
                      - name: Success Step
                        type: http
                        method: GET
                        url: http://localhost:%d/job-b-ok
                """.formatted(wireMock.port(), wireMock.port());

        String workflowId = createWorkflow("parallel-failure-isolation-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);
        awaitTerminalStatus(runId, 30);

        var detail = getRunDetail(workflowId, runId);
        assertThat(detail.get("status")).isEqualTo("FAILED");

        List<Map<String, Object>> jobs = jobs(detail);
        Map<String, Object> jobA = jobs.stream().filter(j -> "job-a".equals(j.get("jobId"))).findFirst().orElseThrow();
        Map<String, Object> jobB = jobs.stream().filter(j -> "job-b".equals(j.get("jobId"))).findFirst().orElseThrow();

        assertThat(jobA.get("status")).isEqualTo("FAILED");
        assertThat(jobB.get("status")).isEqualTo("SUCCESS");
    }

    // ── 3. Loop — exhausted (fail_on_exhausted default) ──────────────────────

    @Test
    void loopJob_exhausted_marksLoopExhaustedAndRunFailed() {
        wireMock.stubFor(get(urlEqualTo("/loop-step"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        // "until: false" — YAML parses boolean false, but SnakeYAML maps it to Boolean.FALSE
        // The WorkflowJobOrchestrator calls interpolator.interpolate then conditionEvaluator.evaluate.
        // Using quoted "false" string so it survives YAML parsing as a string.
        String yaml = """
                name: Loop Exhausted
                on:
                  workflow_dispatch: {}
                jobs:
                  loop-job:
                    loop:
                      max_iterations: 2
                      until: "false"
                    steps:
                      - name: Loop Step
                        type: http
                        method: GET
                        url: http://localhost:%d/loop-step
                """.formatted(wireMock.port());

        String workflowId = createWorkflow("loop-exhausted-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);

        // Each loop iteration goes through the poller; allow extra time
        awaitTerminalStatus(runId, 60);

        var detail = getRunDetail(workflowId, runId);
        assertThat(detail.get("status")).isEqualTo("FAILED");

        // With loops, there are multiple job run rows for the same jobId.
        // At least one must have LOOP_EXHAUSTED.
        List<Map<String, Object>> jobs = jobs(detail);
        boolean anyLoopExhausted = jobs.stream()
                .anyMatch(j -> "LOOP_EXHAUSTED".equals(j.get("status")));
        assertThat(anyLoopExhausted).as("Expected at least one job with LOOP_EXHAUSTED status").isTrue();
    }

    // ── 4. Loop — until condition met on first iteration ─────────────────────

    @Test
    void loopJob_untilMetFirstIteration_completesAfterOneIteration() {
        wireMock.stubFor(get(urlEqualTo("/loop-early"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        // "until: true" — always satisfied on first iteration
        String yaml = """
                name: Loop Early Exit
                on:
                  workflow_dispatch: {}
                jobs:
                  loop-job:
                    loop:
                      max_iterations: 5
                      until: "true"
                    steps:
                      - name: Loop Step
                        type: http
                        method: GET
                        url: http://localhost:%d/loop-early
                """.formatted(wireMock.port());

        String workflowId = createWorkflow("loop-early-exit-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);
        awaitTerminalStatus(runId, 60);

        var detail = getRunDetail(workflowId, runId);
        assertThat(detail.get("status")).isEqualTo("SUCCESS");

        // Step was called exactly once — the loop exited after the first iteration
        wireMock.verify(1, getRequestedFor(urlEqualTo("/loop-early")));
    }

    // ── 5. Condition step — true routes to then-branch ───────────────────────

    @Test
    void conditionStep_trueExpression_routesToThenBranch() {
        wireMock.stubFor(get(urlEqualTo("/then"))
                .willReturn(aResponse().withStatus(200).withBody("{\"branch\":\"then\"}")));
        wireMock.stubFor(get(urlEqualTo("/else"))
                .willReturn(aResponse().withStatus(200).withBody("{\"branch\":\"else\"}")));

        String yaml = """
                name: Condition True
                on:
                  workflow_dispatch: {}
                jobs:
                  evaluate:
                    steps:
                      - name: Route
                        type: condition
                        expression: "true"
                        then: then-job
                        else: else-job
                  then-job:
                    steps:
                      - name: Then Work
                        type: http
                        method: GET
                        url: http://localhost:%d/then
                  else-job:
                    steps:
                      - name: Else Work
                        type: http
                        method: GET
                        url: http://localhost:%d/else
                """.formatted(wireMock.port(), wireMock.port());

        String workflowId = createWorkflow("condition-true-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);
        awaitTerminalStatus(runId, 30);

        var detail = getRunDetail(workflowId, runId);
        assertThat(detail.get("status")).isEqualTo("SUCCESS");

        List<Map<String, Object>> jobs = jobs(detail);

        // evaluate job should be SUCCESS
        Map<String, Object> evaluateJob = jobs.stream()
                .filter(j -> "evaluate".equals(j.get("jobId"))).findFirst().orElseThrow();
        assertThat(evaluateJob.get("status")).isEqualTo("SUCCESS");

        // then-job should be SUCCESS
        Map<String, Object> thenJob = jobs.stream()
                .filter(j -> "then-job".equals(j.get("jobId"))).findFirst().orElseThrow();
        assertThat(thenJob.get("status")).isEqualTo("SUCCESS");

        // else-job should be SKIPPED (row created by skipJobWithReason)
        Map<String, Object> elseJob = jobs.stream()
                .filter(j -> "else-job".equals(j.get("jobId"))).findFirst().orElseThrow();
        assertThat(elseJob.get("status")).isEqualTo("SKIPPED");

        wireMock.verify(1, getRequestedFor(urlEqualTo("/then")));
        wireMock.verify(0, getRequestedFor(urlEqualTo("/else")));
    }

    // ── 6. Condition step — false routes to else-branch ──────────────────────

    @Test
    void conditionStep_falseExpression_routesToElseBranch() {
        wireMock.stubFor(get(urlEqualTo("/then2"))
                .willReturn(aResponse().withStatus(200).withBody("{\"branch\":\"then\"}")));
        wireMock.stubFor(get(urlEqualTo("/else2"))
                .willReturn(aResponse().withStatus(200).withBody("{\"branch\":\"else\"}")));

        String yaml = """
                name: Condition False
                on:
                  workflow_dispatch: {}
                jobs:
                  evaluate:
                    steps:
                      - name: Route
                        type: condition
                        expression: "false"
                        then: then-job
                        else: else-job
                  then-job:
                    steps:
                      - name: Then Work
                        type: http
                        method: GET
                        url: http://localhost:%d/then2
                  else-job:
                    steps:
                      - name: Else Work
                        type: http
                        method: GET
                        url: http://localhost:%d/else2
                """.formatted(wireMock.port(), wireMock.port());

        String workflowId = createWorkflow("condition-false-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);
        awaitTerminalStatus(runId, 30);

        var detail = getRunDetail(workflowId, runId);
        assertThat(detail.get("status")).isEqualTo("SUCCESS");

        List<Map<String, Object>> jobs = jobs(detail);

        Map<String, Object> thenJob = jobs.stream()
                .filter(j -> "then-job".equals(j.get("jobId"))).findFirst().orElseThrow();
        assertThat(thenJob.get("status")).isEqualTo("SKIPPED");

        Map<String, Object> elseJob = jobs.stream()
                .filter(j -> "else-job".equals(j.get("jobId"))).findFirst().orElseThrow();
        assertThat(elseJob.get("status")).isEqualTo("SUCCESS");

        wireMock.verify(0, getRequestedFor(urlEqualTo("/then2")));
        wireMock.verify(1, getRequestedFor(urlEqualTo("/else2")));
    }

    // ── 10. SSE callbacks — log-chunk with valid run token ───────────────────

    @Test
    void sseCallbacks_logChunkWithValidToken_returns200() {
        // We need a run to get a valid runId
        wireMock.stubFor(get(urlEqualTo("/token-test"))
                .willReturn(aResponse().withStatus(200)));

        String yaml = """
                name: Token Test
                on:
                  workflow_dispatch: {}
                jobs:
                  job:
                    steps:
                      - name: Step
                        type: http
                        method: GET
                        url: http://localhost:%d/token-test
                """.formatted(wireMock.port());

        String workflowId = createWorkflow("token-test-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);

        // Generate a valid ephemeral token for this run
        String runToken = runTokenService.generateRunToken(runId, 24);

        HttpHeaders tokenHeaders = new HttpHeaders();
        tokenHeaders.setBearerAuth(runToken);
        tokenHeaders.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "workerJobId", "w1",
                "lines", List.of("line1", "line2"),
                "timestamp", java.time.OffsetDateTime.now().toString()
        );

        var resp = rest.exchange(
                url("/internal/workflow-runs/" + runId + "/log-chunk"),
                HttpMethod.POST,
                new HttpEntity<>(body, tokenHeaders),
                Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── 11. SSE callbacks — invalid token rejected ───────────────────────────

    @Test
    void sseCallbacks_invalidToken_returns401() {
        // Need a real runId but any invalid token should be rejected
        wireMock.stubFor(get(urlEqualTo("/invalid-token-test"))
                .willReturn(aResponse().withStatus(200)));

        String yaml = """
                name: Invalid Token Test
                on:
                  workflow_dispatch: {}
                jobs:
                  job:
                    steps:
                      - name: Step
                        type: http
                        method: GET
                        url: http://localhost:%d/invalid-token-test
                """.formatted(wireMock.port());

        String workflowId = createWorkflow("invalid-token-test-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);

        HttpHeaders badHeaders = new HttpHeaders();
        badHeaders.setBearerAuth("invalid-token");
        badHeaders.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = Map.of(
                "workerJobId", "w1",
                "lines", List.of("line1"),
                "timestamp", java.time.OffsetDateTime.now().toString()
        );

        var resp = rest.exchange(
                url("/internal/workflow-runs/" + runId + "/log-chunk"),
                HttpMethod.POST,
                new HttpEntity<>(body, badHeaders),
                Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── 12. WorkflowValidator — rejects condition step missing 'then' ─────────

    @Test
    void createWorkflow_conditionStepMissingFields_returns400() {
        // condition step is missing 'then' field — validator should reject with error mentioning "condition"
        String yaml = """
                name: Bad Condition
                on:
                  workflow_dispatch: {}
                jobs:
                  evaluate:
                    steps:
                      - name: Route
                        type: condition
                        expression: "true"
                        else: other-job
                  other-job:
                    steps:
                      - name: Work
                        type: http
                        method: GET
                        url: http://localhost:%d/other
                """.formatted(wireMock.port());

        var resp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/workflows"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "bad-condition-" + System.nanoTime(), "yaml", yaml), authHeaders),
                Map.class);

        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
        String detail = (String) resp.getBody().get("detail");
        assertThat(detail).containsIgnoringCase("condition");
    }

    // ── 13. WorkflowValidator — rejects loop missing max_iterations ──────────

    @Test
    void createWorkflow_loopMissingMaxIterations_returns400() {
        String yaml = """
                name: Bad Loop
                on:
                  workflow_dispatch: {}
                jobs:
                  loop-job:
                    loop:
                      until: "true"
                    steps:
                      - name: Step
                        type: http
                        method: GET
                        url: http://localhost:%d/step
                """.formatted(wireMock.port());

        var resp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/workflows"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "bad-loop-" + System.nanoTime(), "yaml", yaml), authHeaders),
                Map.class);

        assertThat(resp.getStatusCode().is4xxClientError()).isTrue();
        String detail = (String) resp.getBody().get("detail");
        assertThat(detail).containsIgnoringCase("loop");
    }

    // ── 14. WorkflowValidator — accepts docker step syntax ───────────────────

    @Test
    void createWorkflow_dockerStepSyntax_acceptedByValidator() {
        String yaml = """
                name: Docker Valid
                on:
                  workflow_dispatch: {}
                jobs:
                  docker-job:
                    steps:
                      - name: Run Container
                        uses: docker://myimage:latest
                """;

        var resp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/workflows"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "docker-valid-" + System.nanoTime(), "yaml", yaml), authHeaders),
                Map.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
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

    private Map<String, Object> getRunDetail(String workflowId, String runId) {
        var resp = rest.exchange(
                url("/api/v1/projects/" + projectId + "/workflows/" + workflowId + "/runs/" + runId),
                HttpMethod.GET,
                new HttpEntity<>(authHeaders),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private void awaitTerminalStatus(String runId, int atMostSeconds) {
        Awaitility.await()
                .atMost(atMostSeconds, TimeUnit.SECONDS)
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
