package com.conductor.workflow;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.entity.WorkflowStepStatus;
import com.conductor.repository.WorkflowJobRunRepository;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowRunFailureNotifierTest {

    private static final String FRONTEND_URL = "http://localhost:3000";

    @Mock SignalBus signalBus;
    @Mock WorkflowJobRunRepository jobRunRepository;
    @Mock WorkflowStepRunRepository stepRunRepository;

    private WorkflowRunFailureNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new WorkflowRunFailureNotifier(signalBus, jobRunRepository, stepRunRepository, FRONTEND_URL);
    }

    private WorkflowRun run(WorkflowRunStatus status) {
        Project project = new Project();
        project.setId("proj-1");
        WorkflowDefinition workflow = new WorkflowDefinition();
        workflow.setId("wf-1");
        workflow.setName("Nightly Sync");
        workflow.setProject(project);

        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        run.setWorkflow(workflow);
        run.setStatus(status);
        return run;
    }

    @Test
    void notifyFailed_noOpsForNonFailedStatus() {
        notifier.notifyFailed(run(WorkflowRunStatus.CANCELLED));
        notifier.notifyFailed(run(WorkflowRunStatus.SUCCESS));
        notifier.notifyFailed(run(WorkflowRunStatus.RUNNING));

        verify(signalBus, never()).publish(any());
    }

    @Test
    void notifyFailed_publishesWithCoreMetadataAndRunUrl_whenNoFailingStepResolvable() {
        WorkflowRun run = run(WorkflowRunStatus.FAILED);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of());

        notifier.notifyFailed(run);

        ArgumentCaptor<Signal> captor = ArgumentCaptor.forClass(Signal.class);
        verify(signalBus).publish(captor.capture());
        Signal signal = captor.getValue();

        assertThat(signal.type()).isEqualTo(SignalTypes.CONDUCTOR_WORKFLOW_RUN_FAILED);
        assertThat(signal.projectId()).isEqualTo("proj-1");
        assertThat(signal.ref()).isEqualTo("run-1");
        assertThat(signal.payload())
                .containsEntry("runId", "run-1")
                .containsEntry("workflowId", "wf-1")
                .containsEntry("workflowName", "Nightly Sync")
                .containsEntry("runUrl", FRONTEND_URL + "/app/projects/proj-1/workflows/wf-1/runs/run-1")
                .doesNotContainKey("jobId")
                .doesNotContainKey("stepId")
                .doesNotContainKey("errorReason");
    }

    @Test
    void notifyFailed_attachesFailingStepJobIdStepIdAndErrorReason() {
        WorkflowRun run = run(WorkflowRunStatus.FAILED);

        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setId("jobrun-1");
        jobRun.setJobId("deploy");
        jobRun.setStatus(WorkflowJobStatus.FAILED);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        WorkflowStepRun step = new WorkflowStepRun();
        step.setId("step-1");
        step.setStepId("push_image");
        step.setStatus(WorkflowStepStatus.FAILED);
        step.setErrorReason("CLAUDE_TIMEOUT");
        when(stepRunRepository.findByJobRunId("jobrun-1")).thenReturn(List.of(step));

        notifier.notifyFailed(run);

        ArgumentCaptor<Signal> captor = ArgumentCaptor.forClass(Signal.class);
        verify(signalBus).publish(captor.capture());
        assertThat(captor.getValue().payload())
                .containsEntry("jobId", "deploy")
                .containsEntry("stepId", "push_image")
                .containsEntry("errorReason", "CLAUDE_TIMEOUT")
                .containsEntry("summary", "The step exceeded its timeout_minutes.")
                .containsEntry("remediation",
                        "Increase timeout_minutes, or reduce the amount of work the step does per run.");
    }

    @Test
    void notifyFailed_unrecognizedErrorReason_omitsSummaryAndRemediationButKeepsErrorReason() {
        WorkflowRun run = run(WorkflowRunStatus.FAILED);

        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setId("jobrun-1");
        jobRun.setJobId("deploy");
        jobRun.setStatus(WorkflowJobStatus.FAILED);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        WorkflowStepRun step = new WorkflowStepRun();
        step.setId("step-1");
        step.setStatus(WorkflowStepStatus.FAILED);
        step.setErrorReason("SOME_UNKNOWN_CODE: extra detail");
        when(stepRunRepository.findByJobRunId("jobrun-1")).thenReturn(List.of(step));

        notifier.notifyFailed(run);

        ArgumentCaptor<Signal> captor = ArgumentCaptor.forClass(Signal.class);
        verify(signalBus).publish(captor.capture());
        assertThat(captor.getValue().payload())
                .containsEntry("errorReason", "SOME_UNKNOWN_CODE: extra detail")
                .doesNotContainKey("summary")
                .doesNotContainKey("remediation")
                .doesNotContainKey("stepId");
    }

    @Test
    void notifyFailed_loopExhaustedJobIsAlsoConsideredFailing() {
        WorkflowRun run = run(WorkflowRunStatus.FAILED);

        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setId("jobrun-1");
        jobRun.setJobId("retry_loop");
        jobRun.setStatus(WorkflowJobStatus.LOOP_EXHAUSTED);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        WorkflowStepRun step = new WorkflowStepRun();
        step.setId("step-1");
        step.setStatus(WorkflowStepStatus.FAILED);
        step.setErrorReason("CLAUDE_AGENT_ERROR");
        when(stepRunRepository.findByJobRunId("jobrun-1")).thenReturn(List.of(step));

        notifier.notifyFailed(run);

        ArgumentCaptor<Signal> captor = ArgumentCaptor.forClass(Signal.class);
        verify(signalBus).publish(captor.capture());
        assertThat(captor.getValue().payload()).containsEntry("jobId", "retry_loop");
    }

    @Test
    void notifyFailed_swallowsSignalBusFailure() {
        WorkflowRun run = run(WorkflowRunStatus.FAILED);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of());
        doThrow(new RuntimeException("Discord webhook unreachable")).when(signalBus).publish(any());

        assertThatNoException().isThrownBy(() -> notifier.notifyFailed(run));
    }
}
