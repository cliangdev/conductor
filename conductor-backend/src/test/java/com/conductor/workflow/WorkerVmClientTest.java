package com.conductor.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkerVmClientTest {

    @Mock
    private RestTemplate restTemplate;

    private ObjectMapper objectMapper = new ObjectMapper();

    private WorkerVmClient client;

    @BeforeEach
    void setUp() {
        client = new WorkerVmClient(restTemplate, objectMapper, "http://localhost:8081", "worker-secret-123");
    }

    @Test
    void submitJob_includesAuthHeader() throws Exception {
        String responseBody = """
                {"workerJobId":"job-abc-123"}
                """;
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(responseBody));

        WorkerVmClient.RunJobRequest request = new WorkerVmClient.RunJobRequest(
                "run-1", "job-1", "ubuntu:22.04", Map.of(),
                "http://cb/log", "http://cb/outputs", "http://cb/failed", "token-xyz"
        );

        String workerJobId = client.submitJob(request);

        assertThat(workerJobId).isEqualTo("job-abc-123");

        ArgumentCaptor<HttpEntity<?>> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(
                eq("http://localhost:8081/run-job"),
                eq(HttpMethod.POST),
                entityCaptor.capture(),
                eq(String.class)
        );
        HttpEntity<?> captured = entityCaptor.getValue();
        assertThat(captured.getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer worker-secret-123");
    }

    @Test
    void submitJob_retriesOn503WithBackoff() throws Exception {
        String responseBody = """
                {"workerJobId":"job-retry-456"}
                """;

        // First two calls return 503, third succeeds
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpServerErrorException.ServiceUnavailable.create(
                        HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", new HttpHeaders(), new byte[0], null))
                .thenThrow(HttpServerErrorException.ServiceUnavailable.create(
                        HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", new HttpHeaders(), new byte[0], null))
                .thenReturn(ResponseEntity.ok(responseBody));

        WorkerVmClient clientWithFastSleep = new WorkerVmClient(restTemplate, objectMapper, "http://localhost:8081", "secret") {
            @Override
            protected void sleepSeconds(int seconds) {
                // no-op for fast tests
            }
        };

        WorkerVmClient.RunJobRequest request = new WorkerVmClient.RunJobRequest(
                "run-1", "job-1", "ubuntu:22.04", Map.of(),
                "http://cb/log", "http://cb/outputs", "http://cb/failed", "token"
        );

        String workerJobId = clientWithFastSleep.submitJob(request);

        assertThat(workerJobId).isEqualTo("job-retry-456");
        verify(restTemplate, times(3)).exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class));
    }

    @Test
    void submitJob_throwsAfterMaxRetries() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenThrow(HttpServerErrorException.ServiceUnavailable.create(
                        HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", new HttpHeaders(), new byte[0], null));

        WorkerVmClient clientWithFastSleep = new WorkerVmClient(restTemplate, objectMapper, "http://localhost:8081", "secret") {
            @Override
            protected void sleepSeconds(int seconds) {}
        };

        WorkerVmClient.RunJobRequest request = new WorkerVmClient.RunJobRequest(
                "run-1", "job-1", "ubuntu:22.04", Map.of(),
                "http://cb/log", "http://cb/outputs", "http://cb/failed", "token"
        );

        assertThatThrownBy(() -> clientWithFastSleep.submitJob(request))
                .isInstanceOf(WorkerVmClient.WorkerUnavailableException.class);

        // 1 initial + 4 retries = 5 calls
        verify(restTemplate, times(5)).exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class));
    }

    @Test
    void getJobStatus_returnsStatus() throws Exception {
        String responseBody = """
                {"workerJobId":"job-xyz","status":"SUCCESS","exitCode":0}
                """;
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(responseBody));

        WorkerVmClient.WorkerJobStatus status = client.getJobStatus("job-xyz");

        assertThat(status.status()).isEqualTo("SUCCESS");
        assertThat(status.exitCode()).isEqualTo(0);
        assertThat(status.isTerminal()).isTrue();
    }

    @Test
    void workerBaseUrl_usedForRequests() throws Exception {
        WorkerVmClient customClient = new WorkerVmClient(
                restTemplate, objectMapper, "http://worker.example.com:9090", "secret");

        String responseBody = """
                {"workerJobId":"j1"}
                """;
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(responseBody));

        WorkerVmClient.RunJobRequest req = new WorkerVmClient.RunJobRequest(
                "r1", "j1", "img", Map.of(), "cb1", "cb2", "cb3", "t");
        customClient.submitJob(req);

        verify(restTemplate).exchange(
                eq("http://worker.example.com:9090/run-job"),
                any(), any(), eq(String.class));
    }
}
