package com.conductor.e2e;

import com.conductor.entity.WorkflowRunStatus;
import com.conductor.repository.WorkflowRunRepository;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
@Testcontainers
class WorkflowExecutionE2ETest {

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

    HttpHeaders authHeaders;
    String projectId;

    @BeforeEach
    void setup() {
        wireMock.resetAll();

        var loginResp = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "e2e-workflow@example.com", "password", "conductor"),
                Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);

        var projResp = rest.exchange(
                url("/api/v1/projects"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Workflow E2E", "description", "test"), authHeaders),
                Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");
    }

    /** Single HTTP step returning 200 → run and job both end SUCCESS */
    @Test
    void manualDispatch_singleHttpStep_runsToSuccess() {
        wireMock.stubFor(get(urlEqualTo("/ping"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\":true}")));

        String yaml = """
                name: Happy Path
                on:
                  workflow_dispatch: {}
                jobs:
                  test-job:
                    steps:
                      - name: Ping
                        type: http
                        method: GET
                        url: http://localhost:%d/ping
                """.formatted(wireMock.port());

        String workflowId = createWorkflow("happy-path", yaml);
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
        assertThat((String) steps.get(0).get("log")).contains("← 200");
    }

    /** HTTP step returning 500 → step and job FAILED, run FAILED */
    @Test
    void manualDispatch_httpStepReturns500_runsAsFailed() {
        wireMock.stubFor(post(urlEqualTo("/error"))
                .willReturn(aResponse().withStatus(500).withBody("{\"error\":\"boom\"}")));

        String yaml = """
                name: Failing Step
                on:
                  workflow_dispatch: {}
                jobs:
                  test-job:
                    steps:
                      - name: Will Fail
                        type: http
                        method: POST
                        url: http://localhost:%d/error
                """.formatted(wireMock.port());

        String workflowId = createWorkflow("failing-step", yaml);
        String runId = dispatchWorkflow(workflowId);

        awaitTerminalStatus(runId);

        var detail = getRunDetail(workflowId, runId);
        assertThat(detail.get("status")).isEqualTo("FAILED");

        List<Map<String, Object>> jobs = jobs(detail);
        assertThat(jobs.get(0).get("status")).isEqualTo("FAILED");

        List<Map<String, Object>> steps = steps(jobs.get(0));
        assertThat(steps.get(0).get("status")).isEqualTo("FAILED");
        assertThat((String) steps.get(0).get("errorReason")).contains("500");
    }

    /** Job with `if: "false"` → job is SKIPPED, run ends SUCCESS, step URL never called */
    @Test
    void manualDispatch_jobWithFalseCondition_isSkippedAndRunSucceeds() {
        String yaml = """
                name: Skipped Job
                on:
                  workflow_dispatch: {}
                jobs:
                  skipped-job:
                    if: "false"
                    steps:
                      - name: Never Runs
                        type: http
                        method: GET
                        url: http://localhost:%d/should-not-be-called
                """.formatted(wireMock.port());

        String workflowId = createWorkflow("skipped-job", yaml);
        String runId = dispatchWorkflow(workflowId);

        awaitTerminalStatus(runId);

        var detail = getRunDetail(workflowId, runId);
        assertThat(detail.get("status")).isEqualTo("SUCCESS");

        List<Map<String, Object>> jobs = jobs(detail);
        assertThat(jobs.get(0).get("status")).isEqualTo("SKIPPED");

        wireMock.verify(0, getRequestedFor(urlEqualTo("/should-not-be-called")));
    }

    /** job-b needs job-a → both execute in order, run ends SUCCESS */
    @Test
    void manualDispatch_dependentJobs_runInOrder() {
        wireMock.stubFor(get(urlEqualTo("/job-a"))
                .willReturn(aResponse().withStatus(200).withBody("{\"done\":\"a\"}")));
        wireMock.stubFor(get(urlEqualTo("/job-b"))
                .willReturn(aResponse().withStatus(200).withBody("{\"done\":\"b\"}")));

        String yaml = """
                name: Dependent Jobs
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
                    needs: [job-a]
                    steps:
                      - name: Step B
                        type: http
                        method: GET
                        url: http://localhost:%d/job-b
                """.formatted(wireMock.port(), wireMock.port());

        String workflowId = createWorkflow("dependent-jobs", yaml);
        String runId = dispatchWorkflow(workflowId);

        awaitTerminalStatus(runId);

        var detail = getRunDetail(workflowId, runId);
        assertThat(detail.get("status")).isEqualTo("SUCCESS");

        List<Map<String, Object>> jobs = jobs(detail);
        assertThat(jobs).hasSize(2);
        assertThat(jobs.stream().map(j -> j.get("status"))).containsOnly("SUCCESS");

        wireMock.verify(1, getRequestedFor(urlEqualTo("/job-a")));
        wireMock.verify(1, getRequestedFor(urlEqualTo("/job-b")));
    }

    /** job-b needs job-a; job-a fails → job-b is SKIPPED, run FAILED */
    @Test
    void manualDispatch_upstreamJobFails_dependentJobIsSkipped() {
        wireMock.stubFor(get(urlEqualTo("/job-a-fail"))
                .willReturn(aResponse().withStatus(500)));

        String yaml = """
                name: Upstream Failure
                on:
                  workflow_dispatch: {}
                jobs:
                  job-a:
                    steps:
                      - name: Fail
                        type: http
                        method: GET
                        url: http://localhost:%d/job-a-fail
                  job-b:
                    needs: [job-a]
                    steps:
                      - name: Should Not Run
                        type: http
                        method: GET
                        url: http://localhost:%d/job-b-never
                """.formatted(wireMock.port(), wireMock.port());

        String workflowId = createWorkflow("upstream-failure", yaml);
        String runId = dispatchWorkflow(workflowId);

        awaitTerminalStatus(runId);

        var detail = getRunDetail(workflowId, runId);
        assertThat(detail.get("status")).isEqualTo("FAILED");

        List<Map<String, Object>> jobs = jobs(detail);
        Map<String, Object> jobA = jobs.stream()
                .filter(j -> "job-a".equals(j.get("jobId"))).findFirst().orElseThrow();
        Map<String, Object> jobB = jobs.stream()
                .filter(j -> "job-b".equals(j.get("jobId"))).findFirst().orElseThrow();

        assertThat(jobA.get("status")).isEqualTo("FAILED");
        assertThat(jobB.get("status")).isEqualTo("SKIPPED");

        wireMock.verify(0, getRequestedFor(urlEqualTo("/job-b-never")));
    }

    /**
     * A→(B fails)→{C if: failure(), D no-if, E if: always()}: C and E run despite B's failure,
     * D is SKIPPED (its implicit success() sees B's failure), and the run is still FAILED overall
     * even though C and E both ran to SUCCESS afterward.
     */
    @Test
    void manualDispatch_diamondWithFailureTolerantJobs_runsFailureAndAlwaysJobs() {
        wireMock.stubFor(get(urlEqualTo("/diamond-a")).willReturn(aResponse().withStatus(200)));
        wireMock.stubFor(get(urlEqualTo("/diamond-b")).willReturn(aResponse().withStatus(500)));
        wireMock.stubFor(get(urlEqualTo("/diamond-c")).willReturn(aResponse().withStatus(200)));
        wireMock.stubFor(get(urlEqualTo("/diamond-d")).willReturn(aResponse().withStatus(200)));
        wireMock.stubFor(get(urlEqualTo("/diamond-e")).willReturn(aResponse().withStatus(200)));

        String yaml = """
                name: Diamond Failure
                on:
                  workflow_dispatch: {}
                jobs:
                  a:
                    steps:
                      - name: A
                        type: http
                        method: GET
                        url: http://localhost:%1$d/diamond-a
                  b:
                    needs: [a]
                    steps:
                      - name: B
                        type: http
                        method: GET
                        url: http://localhost:%1$d/diamond-b
                  c:
                    needs: [b]
                    if: "failure()"
                    steps:
                      - name: C
                        type: http
                        method: GET
                        url: http://localhost:%1$d/diamond-c
                  d:
                    needs: [b]
                    steps:
                      - name: D
                        type: http
                        method: GET
                        url: http://localhost:%1$d/diamond-d
                  e:
                    needs: [b]
                    if: "always()"
                    steps:
                      - name: E
                        type: http
                        method: GET
                        url: http://localhost:%1$d/diamond-e
                """.formatted(wireMock.port());

        String workflowId = createWorkflow("diamond-failure-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);
        awaitTerminalStatus(runId);

        var detail = getRunDetail(workflowId, runId);
        assertThat(detail.get("status")).isEqualTo("FAILED");

        List<Map<String, Object>> jobs = jobs(detail);
        assertThat(statusOf(jobs, "b")).isEqualTo("FAILED");
        assertThat(statusOf(jobs, "c")).isEqualTo("SUCCESS");
        assertThat(statusOf(jobs, "d")).isEqualTo("SKIPPED");
        assertThat(statusOf(jobs, "e")).isEqualTo("SUCCESS");

        wireMock.verify(1, getRequestedFor(urlEqualTo("/diamond-c")));
        wireMock.verify(0, getRequestedFor(urlEqualTo("/diamond-d")));
        wireMock.verify(1, getRequestedFor(urlEqualTo("/diamond-e")));
    }

    /**
     * A fails → B (needs A, no if:) → C (needs B, no if:): the skip cascades through the implicit
     * success() at every hop, matching GitHub Actions and the old hard-skip-cascade behavior —
     * BOTH B and C end SKIPPED (not just the immediate dependent), and the run is FAILED overall.
     */
    @Test
    void manualDispatch_noIfChain_failureCascadesThroughMultipleHops() {
        wireMock.stubFor(get(urlEqualTo("/chain-a")).willReturn(aResponse().withStatus(500)));
        wireMock.stubFor(get(urlEqualTo("/chain-b")).willReturn(aResponse().withStatus(200)));
        wireMock.stubFor(get(urlEqualTo("/chain-c")).willReturn(aResponse().withStatus(200)));

        String yaml = """
                name: No-If Chain
                on:
                  workflow_dispatch: {}
                jobs:
                  a:
                    steps:
                      - name: A
                        type: http
                        method: GET
                        url: http://localhost:%1$d/chain-a
                  b:
                    needs: [a]
                    steps:
                      - name: B
                        type: http
                        method: GET
                        url: http://localhost:%1$d/chain-b
                  c:
                    needs: [b]
                    steps:
                      - name: C
                        type: http
                        method: GET
                        url: http://localhost:%1$d/chain-c
                """.formatted(wireMock.port());

        String workflowId = createWorkflow("no-if-chain-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);
        awaitTerminalStatus(runId);

        var detail = getRunDetail(workflowId, runId);
        assertThat(detail.get("status")).isEqualTo("FAILED");

        List<Map<String, Object>> jobs = jobs(detail);
        assertThat(statusOf(jobs, "a")).isEqualTo("FAILED");
        assertThat(statusOf(jobs, "b")).isEqualTo("SKIPPED");
        assertThat(statusOf(jobs, "c")).isEqualTo("SKIPPED");

        wireMock.verify(0, getRequestedFor(urlEqualTo("/chain-b")));
        wireMock.verify(0, getRequestedFor(urlEqualTo("/chain-c")));
    }

    /**
     * A fails → B (needs A, if: always()) still runs and succeeds → C (needs B, no if:) RUNS: since
     * B itself SUCCEEDED, C's implicit success() sees an all-succeeded needs set — the chain "heals"
     * after an always() job, even though the run is still FAILED overall (A failed).
     */
    @Test
    void manualDispatch_alwaysJobHealsChain_downstreamNoIfJobRuns() {
        wireMock.stubFor(get(urlEqualTo("/heal-a")).willReturn(aResponse().withStatus(500)));
        wireMock.stubFor(get(urlEqualTo("/heal-b")).willReturn(aResponse().withStatus(200)));
        wireMock.stubFor(get(urlEqualTo("/heal-c")).willReturn(aResponse().withStatus(200)));

        String yaml = """
                name: Always Heals Chain
                on:
                  workflow_dispatch: {}
                jobs:
                  a:
                    steps:
                      - name: A
                        type: http
                        method: GET
                        url: http://localhost:%1$d/heal-a
                  b:
                    needs: [a]
                    if: "always()"
                    steps:
                      - name: B
                        type: http
                        method: GET
                        url: http://localhost:%1$d/heal-b
                  c:
                    needs: [b]
                    steps:
                      - name: C
                        type: http
                        method: GET
                        url: http://localhost:%1$d/heal-c
                """.formatted(wireMock.port());

        String workflowId = createWorkflow("always-heals-chain-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);
        awaitTerminalStatus(runId);

        var detail = getRunDetail(workflowId, runId);
        assertThat(detail.get("status")).isEqualTo("FAILED");

        List<Map<String, Object>> jobs = jobs(detail);
        assertThat(statusOf(jobs, "a")).isEqualTo("FAILED");
        assertThat(statusOf(jobs, "b")).isEqualTo("SUCCESS");
        assertThat(statusOf(jobs, "c")).isEqualTo("SUCCESS");

        wireMock.verify(1, getRequestedFor(urlEqualTo("/heal-b")));
        wireMock.verify(1, getRequestedFor(urlEqualTo("/heal-c")));
    }

    /**
     * A step marked continue-on-error keeps the job running (and it still reaches SUCCESS) even
     * though that step itself ends FAILED — and a later step's `if:` can read its result via
     * steps.<id>.result.
     */
    @Test
    void manualDispatch_continueOnErrorStep_jobSucceedsAndLaterStepSeesFailureResult() {
        wireMock.stubFor(get(urlEqualTo("/coe-fails")).willReturn(aResponse().withStatus(500)));
        wireMock.stubFor(get(urlEqualTo("/coe-after")).willReturn(aResponse().withStatus(200)));

        String yaml = """
                name: Continue On Error
                on:
                  workflow_dispatch: {}
                jobs:
                  job1:
                    steps:
                      - id: might-fail
                        name: Might Fail
                        type: http
                        method: GET
                        url: http://localhost:%1$d/coe-fails
                        continue-on-error: true
                      - id: check
                        name: Check Result
                        type: http
                        method: GET
                        url: http://localhost:%1$d/coe-after
                        if: "${{ steps.might-fail.result }} == 'failure'"
                """.formatted(wireMock.port());

        String workflowId = createWorkflow("continue-on-error-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);
        awaitTerminalStatus(runId);

        var detail = getRunDetail(workflowId, runId);
        assertThat(detail.get("status")).isEqualTo("SUCCESS");

        List<Map<String, Object>> jobs = jobs(detail);
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).get("status")).isEqualTo("SUCCESS");

        List<Map<String, Object>> steps = steps(jobs.get(0));
        Map<String, Object> mightFail = steps.stream()
                .filter(s -> "might-fail".equals(s.get("stepId"))).findFirst().orElseThrow();
        Map<String, Object> check = steps.stream()
                .filter(s -> "check".equals(s.get("stepId"))).findFirst().orElseThrow();
        assertThat(mightFail.get("status")).isEqualTo("FAILED");
        assertThat(check.get("status")).isEqualTo("SUCCESS");

        wireMock.verify(1, getRequestedFor(urlEqualTo("/coe-after")));
    }

    private String statusOf(List<Map<String, Object>> jobs, String jobId) {
        return (String) jobs.stream()
                .filter(j -> jobId.equals(j.get("jobId"))).findFirst().orElseThrow().get("status");
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

    /** Polls the repository directly until the run reaches a terminal status. */
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
