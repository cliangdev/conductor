package com.conductor.e2e;

import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.workflow.RunTokenService;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end roundtrip for the local-profile artifact protocol: create (declares a PENDING row and
 * returns the internal passthrough upload URL, since {@code LocalStorageService} can't mint a signed
 * PUT URL) -> passthrough PUT content -> complete (idempotent) -> resolve (signed GET, reusing the
 * existing {@code /api/v1/local-files/**} static endpoint). Own private {@code @Container} per the
 * workflow-job-queue testing convention (this test dispatches a real workflow run).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local")
@Testcontainers
class WorkflowArtifactE2ETest {

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
        registry.add("local.storage.path",
                () -> java.nio.file.Paths.get(System.getProperty("java.io.tmpdir"), "conductor-artifact-e2e-storage")
                        .toString());
    }

    @LocalServerPort
    int port;

    @Autowired TestRestTemplate rest;
    @Autowired WorkflowRunRepository runRepository;
    @Autowired WorkflowJobRunRepository jobRunRepository;
    @Autowired RunTokenService runTokenService;

    HttpHeaders authHeaders;
    String projectId;

    @BeforeEach
    void setup() {
        var loginResp = rest.postForEntity(
                url("/api/v1/auth/local"),
                Map.of("email", "e2e-artifacts@example.com", "password", "conductor"),
                Map.class);
        assertThat(loginResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        authHeaders = new HttpHeaders();
        authHeaders.setBearerAuth((String) loginResp.getBody().get("accessToken"));
        authHeaders.setContentType(MediaType.APPLICATION_JSON);

        var projResp = rest.exchange(
                url("/api/v1/projects"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Artifact E2E " + System.nanoTime(), "description", "test"), authHeaders),
                Map.class);
        assertThat(projResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        projectId = (String) projResp.getBody().get("id");
    }

    @Test
    void createUploadCompleteResolve_roundtripsThroughLocalPassthrough() {
        String apiKey = createProjectApiKey();
        String workflowId = createWorkflow("artifact-roundtrip-" + System.nanoTime(), soloSelfHostedYaml());
        String runId = dispatchWorkflow(workflowId);
        awaitJobStatus(runId, "solo");

        Map<String, Object> payload = dispatchPayload(runId, "solo", apiKey);
        String runToken = (String) payload.get("runToken");
        assertThat(runTokenService.validateRunToken(runToken, runId)).isTrue();

        // 1. Create — declares a PENDING row, returns the internal passthrough upload URL (local
        // storage can't mint a signed PUT URL).
        var createResp = rest.exchange(
                url("/internal/v1/workflow-runs/" + runId + "/artifacts"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("jobId", "solo", "name", "report", "contentType", "application/json"),
                        runTokenHeaders(runToken)),
                Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String artifactId = (String) createResp.getBody().get("artifactId");
        String uploadUrl = (String) createResp.getBody().get("uploadUrl");
        assertThat(artifactId).isNotBlank();
        assertThat(uploadUrl).contains("/internal/v1/workflow-runs/" + runId + "/artifacts/" + artifactId + "/content");

        // Before completion, resolve returns an empty downloadUrl (not yet UPLOADED).
        var beforeComplete = resolveArtifact(runId, "report", runToken);
        assertThat(beforeComplete.get("downloadUrl")).isEqualTo("");

        // 2. Passthrough PUT — streams the raw bytes into local storage. uploadUrl is built from the
        // conductor.backend.url property (defaults to localhost:8080), not this test's actual random
        // port — retarget to the real test server, keeping only the path the service constructed.
        HttpHeaders putHeaders = runTokenHeaders(runToken);
        putHeaders.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        byte[] content = "{\"summary\":\"all good\"}".getBytes();
        var putResp = rest.exchange(retargetToTestServer(uploadUrl), HttpMethod.PUT,
                new HttpEntity<>(content, putHeaders), Void.class);
        assertThat(putResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 3. Complete — idempotent.
        var completeResp = rest.exchange(
                url("/internal/v1/workflow-runs/" + runId + "/artifacts/" + artifactId + "/complete"),
                HttpMethod.POST,
                new HttpEntity<>(runTokenHeaders(runToken)),
                Void.class);
        assertThat(completeResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var completeAgainResp = rest.exchange(
                url("/internal/v1/workflow-runs/" + runId + "/artifacts/" + artifactId + "/complete"),
                HttpMethod.POST,
                new HttpEntity<>(runTokenHeaders(runToken)),
                Void.class);
        assertThat(completeAgainResp.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 4. Resolve — now returns a working download URL (the existing local-files static endpoint).
        Map<String, Object> resolved = resolveArtifact(runId, "report", runToken);
        String downloadUrl = (String) resolved.get("downloadUrl");
        assertThat(downloadUrl).isNotBlank();

        var downloadResp = rest.getForEntity(retargetToTestServer(downloadUrl), byte[].class);
        assertThat(downloadResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloadResp.getBody()).isEqualTo(content);

        // Unknown artifact name still resolves cleanly to an empty downloadUrl.
        assertThat(resolveArtifact(runId, "does-not-exist", runToken).get("downloadUrl")).isEqualTo("");
    }

    @Test
    void artifactEndpoints_invalidRunToken_areUnauthorized() {
        String apiKey = createProjectApiKey();
        String workflowId = createWorkflow("artifact-bad-token-" + System.nanoTime(), soloSelfHostedYaml());
        String runId = dispatchWorkflow(workflowId);
        awaitJobStatus(runId, "solo");

        var createResp = rest.exchange(
                url("/internal/v1/workflow-runs/" + runId + "/artifacts"),
                HttpMethod.POST,
                new HttpEntity<>(Map.of("jobId", "solo", "name", "report"), runTokenHeaders("not-a-real-token")),
                Map.class);
        assertThat(createResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        var resolveResp = rest.exchange(
                url("/internal/v1/workflow-runs/" + runId + "/artifacts?name=report"),
                HttpMethod.GET,
                new HttpEntity<>(runTokenHeaders("not-a-real-token")),
                Map.class);
        assertThat(resolveResp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> resolveArtifact(String runId, String name, String runToken) {
        var resp = rest.exchange(
                url("/internal/v1/workflow-runs/" + runId + "/artifacts?name=" + name),
                HttpMethod.GET,
                new HttpEntity<>(runTokenHeaders(runToken)),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

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

    private HttpHeaders runTokenHeaders(String runToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(runToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private Map<String, Object> dispatchPayload(String runId, String jobId, String apiKey) {
        var resp = rest.exchange(
                url("/api/v1/workflow-runs/" + runId + "/jobs/" + jobId + "/dispatch-payload"),
                HttpMethod.GET,
                new HttpEntity<>(apiKeyHeaders(apiKey)),
                Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private String soloSelfHostedYaml() {
        return """
                name: Solo Self Hosted
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
    }

    private void awaitJobStatus(String runId, String jobId) {
        org.awaitility.Awaitility.await()
                .atMost(30, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    List<com.conductor.entity.WorkflowJobRun> jobRuns = jobRunRepository.findByRunId(runId).stream()
                            .filter(jr -> jr.getJobId().equals(jobId))
                            .toList();
                    assertThat(jobRuns).isNotEmpty();
                    assertThat(jobRuns.get(jobRuns.size() - 1).getStatus().name()).isEqualTo("AWAITING_PICKUP");
                });
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * Service-generated URLs (upload/download) are built from {@code conductor.backend.url} /
     * {@code server.base-url}, which default to {@code localhost:8080} — not this test's actual
     * random port. Keeps everything from the first {@code /} after the host:port and re-bases it
     * onto the real test server.
     */
    private String retargetToTestServer(String serviceUrl) {
        java.net.URI uri = java.net.URI.create(serviceUrl);
        return url(uri.getRawPath() + (uri.getRawQuery() != null ? "?" + uri.getRawQuery() : ""));
    }
}
