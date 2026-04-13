package com.conductor.e2e;

import com.conductor.entity.WorkflowRunStatus;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.workflow.WorkerVmClient;
import com.conductor.workflow.WorkerVmClient.WorkerUnavailableException;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.resttestclient.TestRestTemplate;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * E2E integration tests for Docker step execution.
 *
 * Uses @MockBean WorkerVmClient to avoid real worker HTTP calls. This class
 * gets its own Spring context separate from WorkflowPhase1bE2ETest because
 * @MockBean forces a context reload. This is the cleanest approach: no shared
 * context contamination with WireMock tests.
 *
 * DockerStepExecutor.sleepSeconds() sleeps 5s before the first poll. We accept
 * this delay because it's a protected method on a concrete Spring bean — creating
 * a test subclass would require a complex @TestConfiguration override. The 5s
 * sleep is acceptable for E2E tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
@Testcontainers
class WorkflowDockerE2ETest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine");

    // @MockitoBean replaces the real bean for this Spring context.
    // This avoids real HTTP calls to a worker VM that doesn't exist in tests.
    @MockitoBean
    WorkerVmClient workerVmClient;

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
        var loginResp = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "e2e-docker@example.com", "password", "conductor"),
                Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);

        var projResp = rest.exchange(
                url("/api/v1/projects"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Docker E2E " + System.nanoTime(), "description", "test"), authHeaders),
                Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");
    }

    // ── 7. Docker step — mocked worker success ────────────────────────────────

    @Test
    void dockerStep_workerReturnsSuccess_stepAndJobSuccess() {
        given(workerVmClient.submitJob(any())).willReturn("worker-job-123");
        given(workerVmClient.getJobStatus("worker-job-123"))
                .willReturn(new WorkerVmClient.WorkerJobStatus("SUCCESS", null));

        String yaml = """
                name: Docker Success
                on:
                  workflow_dispatch: {}
                jobs:
                  docker-job:
                    steps:
                      - name: Run Container
                        uses: docker://ghcr.io/test/runner:1
                """;

        String workflowId = createWorkflow("docker-success-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);

        // Docker polling has a 5s sleep before first poll — allow generous timeout
        awaitTerminalStatus(runId, 60);

        var detail = getRunDetail(workflowId, runId);
        assertThat(detail.get("status")).isEqualTo("SUCCESS");

        List<Map<String, Object>> jobs = jobs(detail);
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).get("status")).isEqualTo("SUCCESS");

        // Verify WorkerVmClient.submitJob was called with the correct image
        verify(workerVmClient).submitJob(argThat(req ->
                "ghcr.io/test/runner:1".equals(req.image())
        ));
    }

    // ── 8. Docker step — mocked worker failure ────────────────────────────────

    @Test
    void dockerStep_workerReturnsFailure_stepAndJobFailed() {
        given(workerVmClient.submitJob(any())).willReturn("worker-job-fail-456");
        given(workerVmClient.getJobStatus("worker-job-fail-456"))
                .willReturn(new WorkerVmClient.WorkerJobStatus("FAILED", 1));

        String yaml = """
                name: Docker Failure
                on:
                  workflow_dispatch: {}
                jobs:
                  docker-job:
                    steps:
                      - name: Run Container
                        uses: docker://ghcr.io/test/runner:1
                """;

        String workflowId = createWorkflow("docker-failure-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);
        awaitTerminalStatus(runId, 60);

        var detail = getRunDetail(workflowId, runId);
        assertThat(detail.get("status")).isEqualTo("FAILED");

        List<Map<String, Object>> jobs = jobs(detail);
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).get("status")).isEqualTo("FAILED");

        // Step should have errorReason mentioning exit code
        List<Map<String, Object>> steps = steps(jobs.get(0));
        assertThat(steps).hasSize(1);
        String errorReason = (String) steps.get(0).get("errorReason");
        assertThat(errorReason).contains("Exit code: 1");
    }

    // ── 9. Docker step — worker unavailable ───────────────────────────────────

    @Test
    void dockerStep_workerUnavailable_stepFailed() {
        // WorkerUnavailableException requires a cause — pass a RuntimeException
        given(workerVmClient.submitJob(any()))
                .willThrow(new WorkerUnavailableException(
                        "Worker still unavailable after 4 retries",
                        new RuntimeException("Connection refused")));

        String yaml = """
                name: Docker Unavailable
                on:
                  workflow_dispatch: {}
                jobs:
                  docker-job:
                    steps:
                      - name: Run Container
                        uses: docker://ghcr.io/test/runner:1
                """;

        String workflowId = createWorkflow("docker-unavailable-" + System.nanoTime(), yaml);
        String runId = dispatchWorkflow(workflowId);
        awaitTerminalStatus(runId, 30);

        var detail = getRunDetail(workflowId, runId);
        assertThat(detail.get("status")).isEqualTo("FAILED");

        List<Map<String, Object>> jobs = jobs(detail);
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).get("status")).isEqualTo("FAILED");

        List<Map<String, Object>> steps = steps(jobs.get(0));
        assertThat(steps).hasSize(1);
        String errorReason = (String) steps.get(0).get("errorReason");
        assertThat(errorReason).containsIgnoringCase("Worker unavailable");
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
