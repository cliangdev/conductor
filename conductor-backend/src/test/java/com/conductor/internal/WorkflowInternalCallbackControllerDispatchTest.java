package com.conductor.internal;

import com.conductor.service.WorkflowArtifactService;
import com.conductor.workflow.RunTokenService;
import com.conductor.workflow.WorkflowExecutionEngine;
import com.conductor.workflow.WorkflowRunLogBroker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Cloud-Tasks-triggered dispatch endpoint: must reject a missing/invalid run token exactly like
 * every other {@code /internal/v1} callback, and must not run the job a second time when the queue row
 * was already claimed — a duplicate Cloud Tasks delivery or a race with the fallback poller.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowInternalCallbackControllerDispatchTest {

    @Mock RunTokenService runTokenService;
    @Mock WorkflowRunLogBroker broker;
    @Mock WorkflowArtifactService artifactService;
    @Mock WorkflowExecutionEngine engine;

    WorkflowInternalCallbackController controller;

    @BeforeEach
    void setUp() {
        controller = new WorkflowInternalCallbackController(runTokenService, broker, artifactService, engine);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void withBearerToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (token != null) {
            request.addHeader("Authorization", "Bearer " + token);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void dispatchWorkflowJob_returns401_whenTokenMissing() {
        withBearerToken(null);

        ResponseEntity<Void> response = controller.dispatchWorkflowJob("run-1", "job-1");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(engine, never()).claimQueuedJob(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void dispatchWorkflowJob_returns401_whenTokenInvalid() {
        withBearerToken("bad-token");
        when(runTokenService.validateRunToken("bad-token", "run-1")).thenReturn(false);

        ResponseEntity<Void> response = controller.dispatchWorkflowJob("run-1", "job-1");

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void dispatchWorkflowJob_runsJob_whenRowClaimed() {
        withBearerToken("good-token");
        when(runTokenService.validateRunToken("good-token", "run-1")).thenReturn(true);
        when(engine.claimQueuedJob("run-1", "job-1")).thenReturn(true);

        ResponseEntity<Void> response = controller.dispatchWorkflowJob("run-1", "job-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(engine).processJob("run-1", "job-1");
        verify(engine).checkRunCompletionAfterCommit("run-1");
    }

    @Test
    void dispatchWorkflowJob_isNoOp_whenRowAlreadyClaimed() {
        withBearerToken("good-token");
        when(runTokenService.validateRunToken("good-token", "run-1")).thenReturn(true);
        when(engine.claimQueuedJob("run-1", "job-1")).thenReturn(false);

        ResponseEntity<Void> response = controller.dispatchWorkflowJob("run-1", "job-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        verify(engine, never()).processJob(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(engine, never()).checkRunCompletionAfterCommit(org.mockito.ArgumentMatchers.any());
    }
}
