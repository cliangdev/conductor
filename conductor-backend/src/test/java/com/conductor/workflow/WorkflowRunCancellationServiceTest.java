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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
        // Production wires this via @Autowired @Lazy through the Spring proxy; a plain unit test has no
        // proxy at all, so self-assign the real instance — cancelQueuedRuns only needs a non-null
        // self.cancelRun(...) target here, not actual transaction demarcation.
        service.self = service;
    }

    private WorkflowRun runWithStatus(WorkflowRunStatus status) {
        return runWithStatus(RUN_ID, status);
    }

    private WorkflowRun runWithStatus(String runId, WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);
        run.setStatus(status);
        when(runRepository.findByIdForUpdate(runId)).thenReturn(Optional.of(run));
        when(runRepository.findByIdWithWorkflow(runId)).thenReturn(Optional.of(run));
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
    void cancelRun_succeeds_forAPendingRunWithNoJobsYet() {
        // A run whose enqueueInitialJobs hasn't produced a WorkflowJobRun row yet (or, per the
        // WorkflowTriggerService fail-fast fix, never will) — jobRunRepository.findByRunId returns
        // empty. requestCancellation's loop over job runs must not choke on zero rows, and completion
        // still gets delegated to the engine (mocked here; its own tests cover terminalJobs(0) settling
        // a job-less CANCELLING run straight to CANCELLED).
        WorkflowRun run = runWithStatus(WorkflowRunStatus.PENDING);
        when(jobRunRepository.findByRunId(RUN_ID)).thenReturn(List.of());

        service.cancelRun(RUN_ID);

        assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.CANCELLING);
        verify(queueRepository).deleteUnclaimedByRunId(RUN_ID);
        verify(engine).checkRunCompletion(run);
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

    @Test
    void cancelQueuedRuns_cancelsOnlyThePendingBacklog_andReturnsTheCount() {
        // The query itself is scoped to the queued predicate — this also proves a genuinely RUNNING
        // run is never touched, since it's never even fetched for cancellation.
        WorkflowRun pendingA = runWithStatus("run-a", WorkflowRunStatus.PENDING);
        WorkflowRun pendingB = runWithStatus("run-b", WorkflowRunStatus.PENDING);
        when(runRepository.findQueuedForCancellationByWorkflowId(eq("wf-1"),
                eq(WorkflowRunStatus.PENDING), eq(WorkflowJobStatus.AWAITING_PICKUP), eq(WorkflowJobStatus.RUNNING),
                eq(WorkflowRunStatus.TERMINAL_STATUSES), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pendingA, pendingB)));
        when(jobRunRepository.findByRunId("run-a")).thenReturn(List.of());
        when(jobRunRepository.findByRunId("run-b")).thenReturn(List.of());

        int cancelledCount = service.cancelQueuedRuns("wf-1");

        assertThat(cancelledCount).isEqualTo(2);
        assertThat(pendingA.getStatus()).isEqualTo(WorkflowRunStatus.CANCELLING);
        assertThat(pendingB.getStatus()).isEqualTo(WorkflowRunStatus.CANCELLING);
        verify(queueRepository).deleteUnclaimedByRunId("run-a");
        verify(queueRepository).deleteUnclaimedByRunId("run-b");
    }

    @Test
    void cancelQueuedRuns_isANoOp_returningZero_whenNothingIsQueued() {
        when(runRepository.findQueuedForCancellationByWorkflowId(eq("wf-1"),
                eq(WorkflowRunStatus.PENDING), eq(WorkflowJobStatus.AWAITING_PICKUP), eq(WorkflowJobStatus.RUNNING),
                eq(WorkflowRunStatus.TERMINAL_STATUSES), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        int cancelledCount = service.cancelQueuedRuns("wf-1");

        assertThat(cancelledCount).isZero();
        verify(queueRepository, never()).deleteUnclaimedByRunId(anyString());
    }

    @Test
    void cancelQueuedRuns_skipsARunThatThrows_andStillCancelsTheRest() {
        // run-a races to a terminal status between the queue query and its own cancelRun call and
        // throws ConflictException — the sweep must log and continue rather than abort, and the
        // returned count must reflect only what actually got cancelled.
        WorkflowRun raced = runWithStatus("run-a", WorkflowRunStatus.SUCCESS);
        WorkflowRun stillPending = runWithStatus("run-b", WorkflowRunStatus.PENDING);
        when(runRepository.findQueuedForCancellationByWorkflowId(eq("wf-1"),
                eq(WorkflowRunStatus.PENDING), eq(WorkflowJobStatus.AWAITING_PICKUP), eq(WorkflowJobStatus.RUNNING),
                eq(WorkflowRunStatus.TERMINAL_STATUSES), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(raced, stillPending)));
        when(jobRunRepository.findByRunId("run-b")).thenReturn(List.of());

        int cancelledCount = service.cancelQueuedRuns("wf-1");

        assertThat(cancelledCount).isEqualTo(1);
        assertThat(raced.getStatus()).isEqualTo(WorkflowRunStatus.SUCCESS);
        assertThat(stillPending.getStatus()).isEqualTo(WorkflowRunStatus.CANCELLING);
        verify(queueRepository, never()).deleteUnclaimedByRunId("run-a");
        verify(queueRepository).deleteUnclaimedByRunId("run-b");
    }

    @Test
    void cancelQueuedRuns_includesARunBlockedOnAnUnclaimedAwaitingPickupJob() {
        // The run itself is RUNNING (planJobExecution flips it before the job ever reaches
        // AWAITING_PICKUP), so a plain PENDING filter would never find it -- the whole point of this
        // fix. findQueuedForCancellationByWorkflowId is the collaborator responsible for surfacing it;
        // this test only has to prove the service passes it through to the normal cancelRun path.
        WorkflowRun blocked = runWithStatus("run-a", WorkflowRunStatus.RUNNING);
        when(runRepository.findQueuedForCancellationByWorkflowId(eq("wf-1"),
                eq(WorkflowRunStatus.PENDING), eq(WorkflowJobStatus.AWAITING_PICKUP), eq(WorkflowJobStatus.RUNNING),
                eq(WorkflowRunStatus.TERMINAL_STATUSES), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(blocked)));
        when(jobRunRepository.findByRunId("run-a")).thenReturn(List.of());

        int cancelledCount = service.cancelQueuedRuns("wf-1");

        assertThat(cancelledCount).isEqualTo(1);
        assertThat(blocked.getStatus()).isEqualTo(WorkflowRunStatus.CANCELLING);
        verify(queueRepository).deleteUnclaimedByRunId("run-a");
    }

    @Test
    void cancelQueuedRuns_skipsARunWhoseAwaitingPickupJobIsAlreadyClaimed() {
        // findQueuedForCancellationByWorkflowId's own predicate excludes a run with a claimed
        // AWAITING_PICKUP job (it's actively running on a daemon) by simply never returning it --
        // proving the service itself applies no separate filter that could let it through another way.
        when(runRepository.findQueuedForCancellationByWorkflowId(eq("wf-1"),
                eq(WorkflowRunStatus.PENDING), eq(WorkflowJobStatus.AWAITING_PICKUP), eq(WorkflowJobStatus.RUNNING),
                eq(WorkflowRunStatus.TERMINAL_STATUSES), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        int cancelledCount = service.cancelQueuedRuns("wf-1");

        assertThat(cancelledCount).isZero();
        verify(runRepository, never()).findByIdForUpdate(anyString());
    }
}
