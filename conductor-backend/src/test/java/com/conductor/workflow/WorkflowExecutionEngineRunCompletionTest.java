package com.conductor.workflow;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowJobQueueRepository;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalBus;
import com.conductor.signal.SignalTypes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code checkRunCompletion}'s notification contract: a run that settles to FAILED emits exactly one
 * {@code CONDUCTOR_WORKFLOW_RUN_FAILED} signal, a run that settles to CANCELLED emits none, an
 * already-terminal run (a straggling second call for the same run) emits none either, and a failing
 * signal bus must never stop the FAILED status from being persisted.
 *
 * <p>Uses a real {@link WorkflowRunFailureNotifier} (backed by a mocked {@link SignalBus}) rather than a
 * mocked notifier, so "emits exactly one signal" is verified end-to-end through the actual seam, not just
 * "the seam was called."
 */
@ExtendWith(MockitoExtension.class)
class WorkflowExecutionEngineRunCompletionTest {

    @Mock WorkflowJobQueueRepository queueRepository;
    @Mock WorkflowRunRepository runRepository;
    @Mock WorkflowJobRunRepository jobRunRepository;
    @Mock WorkflowStepRunRepository stepRunRepository;
    @Mock WorkflowDefinitionRepository workflowRepository;
    @Mock WorkflowJobOrchestrator orchestrator;
    @Mock WorkflowFailureCircuitBreaker circuitBreaker;
    @Mock SignalBus signalBus;
    @Mock WorkflowJobDispatcher cloudTasksJobDispatcher;

    WorkflowExecutionEngine engine;

    @BeforeEach
    void setUp() {
        WorkflowRunFailureNotifier realNotifier = new WorkflowRunFailureNotifier(
                signalBus, jobRunRepository, stepRunRepository, "http://localhost:3000");
        engine = new WorkflowExecutionEngine(
                queueRepository, runRepository, jobRunRepository,
                stepRunRepository, workflowRepository, orchestrator, new com.conductor.workflow.model.WorkflowYamlParser(),
                circuitBreaker, realNotifier, cloudTasksJobDispatcher);
    }

    private WorkflowRun runWithOneJob(WorkflowRunStatus runStatus, WorkflowJobStatus jobStatus) {
        Project project = new Project();
        project.setId("proj-1");
        WorkflowDefinition workflow = new WorkflowDefinition();
        workflow.setId("wf-1");
        workflow.setName("Nightly Sync");
        workflow.setProject(project);
        workflow.setYaml("""
                jobs:
                  build:
                    runs-on: cloud-run
                    steps: []
                """);

        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        run.setWorkflow(workflow);
        run.setStatus(runStatus);

        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setId("jobrun-1");
        jobRun.setJobId("build");
        jobRun.setStatus(jobStatus);
        // Not every test reaches these -- an already-terminal run never queries at all, and the
        // notifier's own findFailingStep only queries stepRunRepository for a FAILED/LOOP_EXHAUSTED job.
        lenient().when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));
        lenient().when(stepRunRepository.findByJobRunId("jobrun-1")).thenReturn(List.of());

        return run;
    }

    @Test
    void allJobsFailed_marksRunFailedAndEmitsExactlyOneRunFailedSignal() {
        WorkflowRun run = runWithOneJob(WorkflowRunStatus.RUNNING, WorkflowJobStatus.FAILED);

        engine.checkRunCompletion(run);

        assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.FAILED);
        verify(circuitBreaker).recordOutcome(run);

        ArgumentCaptor<Signal> captor = ArgumentCaptor.forClass(Signal.class);
        verify(signalBus).publish(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(SignalTypes.CONDUCTOR_WORKFLOW_RUN_FAILED);
        assertThat(captor.getValue().payload()).containsEntry("runId", "run-1").containsEntry("workflowId", "wf-1");
    }

    @Test
    void cancellingRun_settlesToCancelled_neverEmitsARunFailedSignal() {
        WorkflowRun run = runWithOneJob(WorkflowRunStatus.CANCELLING, WorkflowJobStatus.CANCELLED);

        engine.checkRunCompletion(run);

        assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.CANCELLED);
        verify(signalBus, never()).publish(any());
    }

    @Test
    void alreadyTerminalRun_returnsEarly_doesNotDoubleNotify() {
        WorkflowRun run = runWithOneJob(WorkflowRunStatus.FAILED, WorkflowJobStatus.FAILED);

        engine.checkRunCompletion(run);

        // The isTerminal() guard at the top of checkRunCompletion fires before any job lookup — this is
        // exactly what makes the run-completion paths safe against a straggler recomputing (and
        // re-notifying for) a run some other path already finalized.
        verify(jobRunRepository, never()).findByRunId(any());
        verify(signalBus, never()).publish(any());
    }

    @Test
    void successfulRun_neverEmitsARunFailedSignal() {
        WorkflowRun run = runWithOneJob(WorkflowRunStatus.RUNNING, WorkflowJobStatus.SUCCESS);

        engine.checkRunCompletion(run);

        assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS);
        verify(signalBus, never()).publish(any());
    }

    @Test
    void aThrowingSignalBus_stillLeavesTheRunPersistedFailed() {
        WorkflowRun run = runWithOneJob(WorkflowRunStatus.RUNNING, WorkflowJobStatus.FAILED);
        doThrow(new RuntimeException("Discord webhook unreachable")).when(signalBus).publish(any());

        // No active Spring transaction here, so SafeSignalPublish publishes immediately (not deferred) --
        // this exercises its belt-and-braces catch directly: even the immediate-publish path must not
        // let a signal-bus failure escape checkRunCompletion and, in production, roll back the very
        // transaction that just recorded the run as FAILED.
        assertThatNoException().isThrownBy(() -> engine.checkRunCompletion(run));

        assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.FAILED);
        verify(runRepository).save(run);
    }
}
