package com.conductor.workflow;

import com.conductor.entity.*;
import com.conductor.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * processJob's safety net: an executor that lets an exception escape uncaught (rather than returning a
 * normal StepResult.failed(...)) must not strand the job in RUNNING forever — it's terminalized via the
 * same completeRemoteJob path the daemon-pickup-timeout sweep already uses. Also covers
 * recoverStuckJobsOnStartup wiring recoverStuckJobs() up as an actual startup hook (previously dead code
 * with no caller anywhere in the codebase).
 */
@ExtendWith(MockitoExtension.class)
class WorkflowExecutionEngineFailureRecoveryTest {

    @Mock WorkflowJobQueueRepository queueRepository;
    @Mock WorkflowRunRepository runRepository;
    @Mock WorkflowJobRunRepository jobRunRepository;
    @Mock WorkflowStepRunRepository stepRunRepository;
    @Mock WorkflowDefinitionRepository workflowRepository;
    @Mock WorkflowJobOrchestrator orchestrator;
    @Mock WorkflowFailureCircuitBreaker circuitBreaker;
    @Mock WorkflowRunFailureNotifier runFailureNotifier;
    @Mock CloudTasksJobDispatcher cloudTasksJobDispatcher;

    WorkflowExecutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new WorkflowExecutionEngine(
                queueRepository, runRepository, jobRunRepository,
                stepRunRepository, workflowRepository, orchestrator, new com.conductor.workflow.model.WorkflowYamlParser(),
                circuitBreaker, runFailureNotifier, cloudTasksJobDispatcher, 4);
    }

    @Test
    void processJob_uncaughtExceptionFromExecuteJob_marksJobFailedViaCompleteRemoteJob() {
        doThrow(new RuntimeException("GitHub API call failed: 422 Unprocessable Entity"))
                .when(orchestrator).executeJob("run-1", "detect_changes");

        engine.processJob("run-1", "detect_changes");

        verify(orchestrator).completeRemoteJob(
                eq("run-1"), eq("detect_changes"), eq(WorkflowJobStatus.FAILED),
                contains("GitHub API call failed"));
    }

    @Test
    void processJob_normalExecution_neverCallsCompleteRemoteJob() {
        // executeJob succeeding (or itself returning a handled StepResult.failed(...) internally,
        // which never throws) must not trip the safety net.
        engine.processJob("run-1", "build");

        verify(orchestrator, never()).completeRemoteJob(any(), any(), any(), any());
    }

    @Test
    void processJob_completeRemoteJobItselfThrows_propagatesToOuterSafetyNet() {
        // processJob only wraps executeJob(), not the completeRemoteJob(...) recovery call itself — if
        // the recovery write also fails, it legitimately propagates out to pollQueueOnce's outer
        // log-only catch (defense in depth), rather than being silently swallowed here too.
        doThrow(new RuntimeException("boom")).when(orchestrator).executeJob("run-1", "detect_changes");
        doThrow(new RuntimeException("db unavailable"))
                .when(orchestrator).completeRemoteJob(any(), any(), any(), any());

        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> engine.processJob("run-1", "detect_changes"));
    }

    @Test
    void recoverStuckJobsOnStartup_delegatesToRecoverStuckJobsViaSelfProxy() {
        // recoverStuckJobsOnStartup calls through the `self` proxy field (Spring-injected), which
        // MockitoExtension doesn't wire automatically for a plain `new WorkflowExecutionEngine(...)` —
        // set it explicitly to the same instance, mirroring how @Lazy self-injection resolves in the
        // real Spring context.
        java.lang.reflect.Field selfField;
        try {
            selfField = WorkflowExecutionEngine.class.getDeclaredField("self");
            selfField.setAccessible(true);
            selfField.set(engine, engine);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        when(jobRunRepository.findByStatus(WorkflowJobStatus.RUNNING)).thenReturn(List.of());

        engine.recoverStuckJobsOnStartup();

        verify(jobRunRepository).findByStatus(WorkflowJobStatus.RUNNING);
    }
}
