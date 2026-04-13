package com.conductor.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Component
public class WorkerVmClient {

    private static final Logger log = LoggerFactory.getLogger(WorkerVmClient.class);

    private static final int[] RETRY_BACKOFF_SECONDS = {5, 10, 30, 60};

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String workerBaseUrl;
    private final String workerSecret;

    public WorkerVmClient(
            RestTemplate restTemplate,
            ObjectMapper objectMapper,
            @Value("${conductor.worker.url:http://localhost:8081}") String workerBaseUrl,
            @Value("${conductor.worker.secret:}") String workerSecret) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.workerBaseUrl = workerBaseUrl;
        this.workerSecret = workerSecret;
    }

    /**
     * POST /run-job. Returns workerJobId on success.
     * On 503, retries with backoff (5s, 10s, 30s, 60s) — max 4 retries.
     * Job stays claimed during retries — does NOT re-enqueue.
     */
    public String submitJob(RunJobRequest request) {
        String url = workerBaseUrl + "/run-job";
        HttpHeaders headers = buildHeaders();
        HttpEntity<RunJobRequest> entity = new HttpEntity<>(request, headers);

        int attempts = 0;
        while (true) {
            try {
                ResponseEntity<String> response = restTemplate.exchange(
                        url, HttpMethod.POST, entity, String.class);
                JsonNode node = objectMapper.readTree(response.getBody());
                return node.get("workerJobId").asText();
            } catch (HttpServerErrorException.ServiceUnavailable e) {
                if (attempts >= RETRY_BACKOFF_SECONDS.length) {
                    throw new WorkerUnavailableException(
                            "Worker still unavailable after " + attempts + " retries", e);
                }
                int backoffSeconds = RETRY_BACKOFF_SECONDS[attempts];
                log.warn("Worker returned 503, retrying in {}s (attempt {}/{})",
                        backoffSeconds, attempts + 1, RETRY_BACKOFF_SECONDS.length);
                sleepSeconds(backoffSeconds);
                attempts++;
            } catch (Exception e) {
                throw new WorkerCommunicationException("Failed to submit job to worker: " + e.getMessage(), e);
            }
        }
    }

    /** GET /job/{workerJobId}/status */
    public WorkerJobStatus getJobStatus(String workerJobId) {
        String url = workerBaseUrl + "/job/" + workerJobId + "/status";
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class);
            JsonNode node = objectMapper.readTree(response.getBody());
            String status = node.get("status").asText();
            Integer exitCode = node.has("exitCode") ? node.get("exitCode").asInt() : null;
            return new WorkerJobStatus(status, exitCode);
        } catch (Exception e) {
            throw new WorkerCommunicationException("Failed to get job status: " + e.getMessage(), e);
        }
    }

    /** DELETE /job/{workerJobId} */
    public void cancelJob(String workerJobId) {
        String url = workerBaseUrl + "/job/" + workerJobId;
        HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
        try {
            restTemplate.exchange(url, HttpMethod.DELETE, entity, String.class);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Job {} not found on worker during cancel", workerJobId);
        } catch (Exception e) {
            throw new WorkerCommunicationException("Failed to cancel job: " + e.getMessage(), e);
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (workerSecret != null && !workerSecret.isBlank()) {
            headers.setBearerAuth(workerSecret);
        }
        return headers;
    }

    protected void sleepSeconds(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- Nested types ---

    public record RunJobRequest(
            String runId,
            String jobId,
            String image,
            Map<String, String> env,
            String logCallbackUrl,
            String outputsCallbackUrl,
            String jobFailedCallbackUrl,
            String ephemeralToken
    ) {}

    public record WorkerJobStatus(String status, Integer exitCode) {
        public boolean isTerminal() {
            return "SUCCESS".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
        }
    }

    public static class WorkerUnavailableException extends RuntimeException {
        public WorkerUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class WorkerCommunicationException extends RuntimeException {
        public WorkerCommunicationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
