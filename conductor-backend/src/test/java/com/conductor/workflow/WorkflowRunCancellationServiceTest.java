package com.conductor.workflow;

import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.entity.WorkflowStepStatus;
import com.conductor.exception.ConflictException;
import com.conductor.repository.WorkflowJobQueueRepository;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowRunCancellationServiceTest {

    private static final String RUN_ID = "run-1";

    @Mock WorkflowRunRepository runRepository;
    @Mock WorkflowJobRunRepository jobRunRepository;
    @Mock WorkflowStepRunRepository stepRunRepository;
    @Mock WorkflowJobQueueRepository queueRepository;
    @Mock WorkflowExecutionEngine engine;

    WorkflowRunCancellationService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowRunCancellationService(runRepository, jobRunRepository,
                stepRunRepository, queueRepository, engine);
    }

    private WorkflowRun runWithStatus(WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setId(RUN_ID);
        run.setStatus(status);
        when(runRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));
        when(runRepository.findByIdWithWorkflow(RUN_ID)).thenReturn(Optional.of(run));
        return run;
    }

    private WorkflowJobRun jobRun(String id, WorkflowJobStatus status) {
        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setId(id);
        jobRun.setStatus(status);
        return jobRun;
    }

    @Test
    void cancelRun_flagsCancelling_purgesUnclaimedQueue_andTerminalizesUndispatchedJobs() {
        WorkflowRun run = runWithStatus(WorkflowRunStatus.RUNNING);
        WorkflowJobRun pending = jobRun("jr-pending", WorkflowJobStatus.PENDING);
        WorkflowJobRun awaiting = jobRun("jr-awaiting", WorkflowJobStatus.AWAITING_PICKUP);
        when(jobRunRepository.findByRunId(RUN_ID)).thenReturn(List.of(pending, awaiting));

        service.cancelRun(RUN_ID);

        assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.CANCELLING);
        verify(queueRepository).deleteUnclaimedByRunId(RUN_ID);
        assertThat(pending.getStatus()).isEqualTo(WorkflowJobStatus.CANCELLED);
        assertThat(pending.getCompletedAt()).isNotNull();
        assertThat(awaiting.getStatus()).isEqualTo(WorkflowJobStatus.CANCELLED);
        verify(engine).checkRunCompletion(run);
    }

    @Test
    void cancelRun_leavesRunningJobsToTheirExecutor() {
        runWithStatus(WorkflowRunStatus.RUNNING);
        WorkflowJobRun running = jobRun("jr-running", WorkflowJobStatus.RUNNING);
        when(jobRunRepository.findByRunId(RUN_ID)).thenReturn(List.of(running));

        service.cancelRun(RUN_ID);

        assertThat(running.getStatus()).isEqualTo(WorkflowJobStatus.RUNNING);
        verify(jobRunRepository, never()).save(running);
    }

    @Test
    void cancelRun_cancelsUnfinishedStepsOfAnUndispatchedJob() {
        runWithStatus(WorkflowRunStatus.RUNNING);
        WorkflowJobRun pending = jobRun("jr-pending", WorkflowJobStatus.PENDING);
        when(jobRunRepository.findByRunId(RUN_ID)).thenReturn(List.of(pending));

        WorkflowStepRun open = new WorkflowStepRun();
        open.setStatus(WorkflowStepStatus.RUNNING);
        WorkflowStepRun done = new WorkflowStepRun();
        done.setStatus(WorkflowStepStatus.SUCCESS);
        when(stepRunRepository.findByJobRunId("jr-pending")).thenReturn(List.of(open, done));

        service.cancelRun(RUN_ID);

        assertThat(open.getStatus()).isEqualTo(WorkflowStepStatus.CANCELLED);
        assertThat(open.getCompletedAt()).isNotNull();
        assertThat(done.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
    }

    @Test
    void cancelRun_isIdempotentWhileAlreadyCancelling() {
        WorkflowRun run = runWithStatus(WorkflowRunStatus.CANCELLING);

        assertThat(service.cancelRun(RUN_ID)).isSameAs(run);

        verify(queueRepository, never()).deleteUnclaimedByRunId(RUN_ID);
        verify(engine, never()).checkRunCompletion(run);
    }

    @Test
    void cancelRun_rejectsAnAlreadyFinishedRun() {
        runWithStatus(WorkflowRunStatus.SUCCESS);

        assertThatThrownBy(() -> service.cancelRun(RUN_ID))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("SUCCESS");
        verify(queueRepository, never()).deleteUnclaimedByRunId(RUN_ID);
    }

    @Test
    void cancelRun_rejectsAnUnknownRun() {
        when(runRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelRun(RUN_ID))
                .isInstanceOf(EntityNotFoundException.class);
    }
}
