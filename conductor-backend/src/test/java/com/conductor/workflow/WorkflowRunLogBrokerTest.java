package com.conductor.workflow;

import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.entity.WorkflowStepStatus;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (no Spring context) for {@link WorkflowRunLogBroker}'s worker-callback recording
 * methods. Phase 1 pre-creates one {@code WorkflowStepRun} per self-hosted step with a real
 * {@code workerJobId} ({@code jobRunId + ":" + stepIndex}), which is what lets {@code recordOutputs} /
 * {@code recordJobFailed} / {@code recordStepCompleted} actually match a row instead of silently
 * finding nothing.
 */
class WorkflowRunLogBrokerTest {

    private final WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
    private final WorkflowJobRunRepository jobRunRepository = mock(WorkflowJobRunRepository.class);
    private final WorkflowStepRunRepository stepRunRepository = mock(WorkflowStepRunRepository.class);

    private WorkflowRunLogBroker broker;

    @BeforeEach
    void setUp() {
        broker = new WorkflowRunLogBroker(runRepository, jobRunRepository, stepRunRepository, new ObjectMapper());
    }

    private WorkflowJobRun jobRunWithStep(WorkflowStepRun step) {
        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setId("jobrun-1");
        when(stepRunRepository.findByJobRunId("jobrun-1")).thenReturn(List.of(step));
        return jobRun;
    }

    @Test
    void recordStepCompleted_setsStatusOutputsErrorReasonAndCompletedAt_forMatchingWorkerJobId() {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setId("step-1");
        step.setWorkerJobId("jobrun-1:0");
        step.setStatus(WorkflowStepStatus.PENDING);
        WorkflowJobRun jobRun = jobRunWithStep(step);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        broker.recordStepCompleted("run-1", "jobrun-1:0", WorkflowStepStatus.SUCCESS, 0, null,
                Map.of("summary", "done"));

        assertThat(step.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        assertThat(step.getErrorReason()).isNull();
        assertThat(step.getOutputJson()).contains("summary");
        assertThat(step.getStartedAt()).isNotNull();
        assertThat(step.getCompletedAt()).isNotNull();
        verify(stepRunRepository).save(step);
    }

    @Test
    void recordStepCompleted_setsErrorReason_andDoesNotOverwriteExistingStartedAt() {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setId("step-1");
        step.setWorkerJobId("jobrun-1:0");
        java.time.OffsetDateTime startedAt = java.time.OffsetDateTime.now().minusMinutes(5);
        step.setStartedAt(startedAt);
        WorkflowJobRun jobRun = jobRunWithStep(step);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        broker.recordStepCompleted("run-1", "jobrun-1:0", WorkflowStepStatus.FAILED, 1,
                "CLAUDE_AGENT_ERROR", null);

        assertThat(step.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(step.getErrorReason()).isEqualTo("CLAUDE_AGENT_ERROR");
        assertThat(step.getStartedAt()).isEqualTo(startedAt);
    }

    @Test
    void recordStepCompleted_unknownWorkerJobId_isNoOp() {
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of());

        broker.recordStepCompleted("run-1", "does-not-exist", WorkflowStepStatus.SUCCESS, 0, null, Map.of());

        verify(stepRunRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordOutputs_matchesPreCreatedRowByWorkerJobId() {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setId("step-1");
        step.setWorkerJobId("jobrun-1:0");
        WorkflowJobRun jobRun = jobRunWithStep(step);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        broker.recordOutputs("run-1", "jobrun-1:0", Map.of("data", "hello"));

        assertThat(step.getOutputJson()).contains("hello");
        verify(stepRunRepository).save(step);
    }

    @Test
    void recordJobFailed_matchesPreCreatedRowByWorkerJobId_andRollsUpJobStatus() {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setId("step-1");
        step.setWorkerJobId("jobrun-1:0");
        WorkflowJobRun jobRun = jobRunWithStep(step);
        jobRun.setStatus(WorkflowJobStatus.RUNNING);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));
        when(runRepository.findByIdWithWorkflow("run-1")).thenReturn(java.util.Optional.empty());

        broker.recordJobFailed("run-1", "jobrun-1:0", "Container exited with code 1");

        assertThat(step.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(step.getErrorReason()).isEqualTo("Container exited with code 1");
        assertThat(jobRun.getStatus()).isEqualTo(WorkflowJobStatus.FAILED);
        verify(stepRunRepository).save(step);
        verify(jobRunRepository).save(jobRun);
    }
}
