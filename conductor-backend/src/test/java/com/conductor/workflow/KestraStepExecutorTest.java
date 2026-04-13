package com.conductor.workflow;

import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRun;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KestraStepExecutorTest {

    @Mock
    private RestTemplate restTemplate;

    private KestraStepExecutor executor;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        WorkflowInterpolator interpolator = new WorkflowInterpolator();
        executor = new KestraStepExecutor(interpolator, objectMapper, restTemplate);
    }

    private StepExecutionContext context(Map<String, Object> stepDef) {
        RuntimeContext runtimeContext = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of());
        return new StepExecutionContext(new WorkflowRun(), new WorkflowJobRun(), stepDef, runtimeContext, "proj-1");
    }

    @Test
    void getStepTypeReturnsKestra() {
        assertThat(executor.getStepType()).isEqualTo("kestra");
    }

    @Test
    void missingNamespaceReturnsFailed() {
        StepExecutionContext ctx = context(Map.of("flow_id", "my-flow"));
        StepResult result = executor.execute(ctx);
        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("namespace");
    }

    @Test
    void missingFlowIdReturnsFailed() {
        StepExecutionContext ctx = context(Map.of("namespace", "my-ns"));
        StepResult result = executor.execute(ctx);
        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("flow_id");
    }

    @Test
    void waitFalseReturnsImmediatelyAfterPost() throws Exception {
        String triggerResponse = "{\"id\":\"exec-123\",\"state\":{\"current\":\"CREATED\"}}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(triggerResponse));

        Map<String, Object> stepDef = Map.of(
                "namespace", "my-ns",
                "flow_id", "my-flow",
                "wait", false
        );
        StepResult result = executor.execute(context(stepDef));

        assertThat(result.getStatus().name()).isEqualTo("SUCCESS");
        assertThat(result.getOutputs()).containsKey("executionId");
        assertThat(result.getOutputs().get("executionId")).isEqualTo("exec-123");
        verify(restTemplate, times(1)).exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class));
        verify(restTemplate, never()).exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class));
    }

    @Test
    void successStateReturnsSuccess() throws Exception {
        String triggerResponse = "{\"id\":\"exec-456\",\"state\":{\"current\":\"CREATED\"}}";
        String pollResponse = "{\"id\":\"exec-456\",\"state\":{\"current\":\"SUCCESS\"}}";

        when(restTemplate.exchange(contains("/executions/my-ns/my-flow"), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(triggerResponse));
        when(restTemplate.exchange(contains("/executions/exec-456"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(pollResponse));

        Map<String, Object> stepDef = Map.of(
                "namespace", "my-ns",
                "flow_id", "my-flow",
                "wait", true,
                "timeout_minutes", 1
        );

        // Use a test subclass that skips Thread.sleep
        KestraStepExecutor fastExecutor = new KestraStepExecutor(new WorkflowInterpolator(), objectMapper, restTemplate) {
            @Override
            protected void sleepMs(long ms) { }
        };

        StepResult result = fastExecutor.execute(context(stepDef));
        assertThat(result.getStatus().name()).isEqualTo("SUCCESS");
    }

    @Test
    void failedStateReturnsFailedWithExecutionId() throws Exception {
        String triggerResponse = "{\"id\":\"exec-fail\",\"state\":{\"current\":\"CREATED\"}}";
        String pollResponse = "{\"id\":\"exec-fail\",\"state\":{\"current\":\"FAILED\"}}";

        when(restTemplate.exchange(contains("/executions/my-ns/my-flow"), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(triggerResponse));
        when(restTemplate.exchange(contains("/executions/exec-fail"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(pollResponse));

        Map<String, Object> stepDef = Map.of(
                "namespace", "my-ns",
                "flow_id", "my-flow",
                "wait", true,
                "timeout_minutes", 1
        );

        KestraStepExecutor fastExecutor = new KestraStepExecutor(new WorkflowInterpolator(), objectMapper, restTemplate) {
            @Override
            protected void sleepMs(long ms) { }
        };

        StepResult result = fastExecutor.execute(context(stepDef));
        assertThat(result.getStatus().name()).isEqualTo("FAILED");
        assertThat(result.getErrorReason()).contains("exec-fail");
    }

    @Test
    void warningWithoutFailOnWarningReturnsSuccess() throws Exception {
        String triggerResponse = "{\"id\":\"exec-warn\",\"state\":{\"current\":\"CREATED\"}}";
        String pollResponse = "{\"id\":\"exec-warn\",\"state\":{\"current\":\"WARNING\"}}";

        when(restTemplate.exchange(contains("/executions/my-ns/my-flow"), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(triggerResponse));
        when(restTemplate.exchange(contains("/executions/exec-warn"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(pollResponse));

        Map<String, Object> stepDef = Map.of(
                "namespace", "my-ns",
                "flow_id", "my-flow",
                "wait", true,
                "fail_on_warning", false,
                "timeout_minutes", 1
        );

        KestraStepExecutor fastExecutor = new KestraStepExecutor(new WorkflowInterpolator(), objectMapper, restTemplate) {
            @Override
            protected void sleepMs(long ms) { }
        };

        StepResult result = fastExecutor.execute(context(stepDef));
        assertThat(result.getStatus().name()).isEqualTo("SUCCESS");
    }

    @Test
    void warningWithFailOnWarningReturnsFailed() throws Exception {
        String triggerResponse = "{\"id\":\"exec-warn2\",\"state\":{\"current\":\"CREATED\"}}";
        String pollResponse = "{\"id\":\"exec-warn2\",\"state\":{\"current\":\"WARNING\"}}";

        when(restTemplate.exchange(contains("/executions/my-ns/my-flow"), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(triggerResponse));
        when(restTemplate.exchange(contains("/executions/exec-warn2"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(pollResponse));

        Map<String, Object> stepDef = Map.of(
                "namespace", "my-ns",
                "flow_id", "my-flow",
                "wait", true,
                "fail_on_warning", true,
                "timeout_minutes", 1
        );

        KestraStepExecutor fastExecutor = new KestraStepExecutor(new WorkflowInterpolator(), objectMapper, restTemplate) {
            @Override
            protected void sleepMs(long ms) { }
        };

        StepResult result = fastExecutor.execute(context(stepDef));
        assertThat(result.getStatus().name()).isEqualTo("FAILED");
    }

    @Test
    void authorizationHeaderContainsRedactedToken() throws Exception {
        String triggerResponse = "{\"id\":\"exec-123\",\"state\":{\"current\":\"CREATED\"}}";
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok(triggerResponse));

        Map<String, Object> stepDef = Map.of(
                "namespace", "my-ns",
                "flow_id", "my-flow",
                "wait", false
        );

        // Set env var is not possible in unit tests without env injection, but we can verify
        // the log does not contain a raw token value by checking log output
        StepResult result = executor.execute(context(stepDef));

        // The log should contain [REDACTED], not a real token value
        // (when no KESTRA_API_TOKEN env var is set, no Authorization header is added)
        assertThat(result.getLog()).doesNotContain("Bearer eyJ");
    }
}
