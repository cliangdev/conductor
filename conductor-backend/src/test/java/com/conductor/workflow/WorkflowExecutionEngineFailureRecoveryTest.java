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
 * recoverStuckJobsOnStartup wiring recoverStuckJobs() up as an actual startup hook, and
 * recoverOrphanedClaims — the crash-recovery paths that stay independent of the dispatch mechanism.
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
    @Mock WorkflowJobDispatcher cloudTasksJobDispatcher;

    WorkflowExecutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new WorkflowExecutionEngine(
                queueRepository, runRepository, jobRunRepository,
                stepRunRepository, workflowRepository, orchestrator, new com.conductor.workflow.model.WorkflowYamlParser(),
                circuitBreaker, runFailureNotifier, cloudTasksJobDispatcher);
    }

    private WorkflowJobQueue makeQueueEntry(String runId, String jobId) {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);

        WorkflowJobQueue entry = new WorkflowJobQueue();
        entry.setId(java.util.UUID.randomUUID().toString());
        entry.setRun(run);
        entry.setJobId(jobId);
        return entry;
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
        // the recovery write also fails, it legitimately propagates out to the dispatch endpoint's
        // caller (Cloud Tasks), whose non-2xx-triggered retry is the actual recovery path here now
        // (defense in depth), rather than being silently swallowed here too.
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

    @Test
    void recoverOrphanedClaims_reopensClaimedRowsThatNeverProducedAJobRun() {
        // The instance died between claimQueuedJob and creating its workflow_job_runs row. Nothing else
        // in the system can see such a row again, so startup must clear the claim.
        WorkflowJobQueue orphaned = makeQueueEntry("run-1", "job-a");
        orphaned.setClaimedAt(java.time.OffsetDateTime.now());
        when(queueRepository.findClaimedWithoutJobRun()).thenReturn(List.of(orphaned));

        engine.recoverOrphanedClaims();

        org.mockito.ArgumentCaptor<WorkflowJobQueue> saved = org.mockito.ArgumentCaptor.forClass(WorkflowJobQueue.class);
        verify(queueRepository).save(saved.capture());
        org.assertj.core.api.Assertions.assertThat(saved.getValue().getClaimedAt())
                .as("cleared claim is what makes the row claimable again by a redelivered Cloud Task")
                .isNull();
    }

    @Test
    void recoverOrphanedClaims_doesNotEnqueueASecondQueueRow() {
        // Re-opening the existing row is deliberate: inserting a fresh one alongside it would let the
        // same job run twice.
        WorkflowJobQueue orphaned = makeQueueEntry("run-1", "job-a");
        orphaned.setClaimedAt(java.time.OffsetDateTime.now());
        when(queueRepository.findClaimedWithoutJobRun()).thenReturn(List.of(orphaned));

        engine.recoverOrphanedClaims();

        verify(queueRepository, never()).findByRunIdAndJobIdAndClaimedAtIsNull(anyString(), anyString());
    }
}
