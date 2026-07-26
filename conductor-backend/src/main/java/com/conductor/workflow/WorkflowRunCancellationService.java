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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;

/**
 * Stops a workflow run on request. Cancellation is a two-stage affair: this service performs the
 * synchronous half — the run is flagged {@link WorkflowRunStatus#CANCELLING}, its undispatched queue
 * rows are dropped, and every job that hadn't started yet is terminalized — while work already in
 * flight settles asynchronously, as each executor notices the flag and stops.
 * {@link WorkflowExecutionEngine#checkRunCompletion} flips CANCELLING to CANCELLED once nothing is
 * left running, which for a run with no in-flight job is already true by the time this returns.
 */
@Service
public class WorkflowRunCancellationService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunCancellationService.class);

    private final WorkflowRunRepository runRepository;
    private final WorkflowJobRunRepository jobRunRepository;
    private final WorkflowStepRunRepository stepRunRepository;
    private final WorkflowJobQueueRepository queueRepository;
    private final WorkflowExecutionEngine engine;

    /** Self-reference so {@link #cancelRun} runs through the Spring proxy (and its own transaction)
     *  even when called from {@link #cancelQueuedRuns} on this same instance — a plain {@code this}
     *  call would bypass {@code @Transactional} entirely (no AOP interception on self-invocation),
     *  and each run needs its own transaction so one throwing doesn't roll back the others. */
    @Autowired
    @Lazy
    WorkflowRunCancellationService self;

    public WorkflowRunCancellationService(WorkflowRunRepository runRepository,
                                          WorkflowJobRunRepository jobRunRepository,
                                          WorkflowStepRunRepository stepRunRepository,
                                          WorkflowJobQueueRepository queueRepository,
                                          WorkflowExecutionEngine engine) {
        this.runRepository = runRepository;
        this.jobRunRepository = jobRunRepository;
        this.stepRunRepository = stepRunRepository;
        this.queueRepository = queueRepository;
        this.engine = engine;
    }

    @Transactional
    public WorkflowRun cancelRun(String runId) {
        // Same row lock the completion signals take, so a cancellation can't interleave with a job
        // completing and leave the run's status recomputed from a half-applied cancellation.
        WorkflowRun run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new EntityNotFoundException("Run not found: " + runId));

        if (run.getStatus().isTerminal()) {
            throw new ConflictException("Run " + runId + " has already finished ("
                    + run.getStatus() + ") and cannot be cancelled");
        }
        // Re-requesting a cancellation already under way is a no-op, not an error — a double-clicked
        // Cancel button shouldn't surface a conflict.
        if (run.getStatus() != WorkflowRunStatus.CANCELLING) {
            requestCancellation(run);
        }

        // Re-read with the workflow join-fetched: open-in-view is off, so the caller maps this to a
        // DTO after the transaction — and its lazy proxies — are gone.
        return runRepository.findByIdWithWorkflow(runId).orElseThrow();
    }

    /**
     * Drains a workflow's queued backlog: cancels every PENDING run, one {@link #cancelRun(String)}
     * call per run — reusing the same path a single-run cancel takes (row lock, queue-row deletion,
     * job/step terminalization, completion settle) rather than a second cancellation code path.
     * RUNNING runs are never touched. Each run's own transaction is independent, so one run racing to
     * a terminal status and throwing doesn't abort the rest of the sweep — it's logged and skipped.
     */
    public int cancelQueuedRuns(String workflowId) {
        List<WorkflowRun> queued = runRepository.findByWorkflowIdAndStatusIn(workflowId,
                Set.of(WorkflowRunStatus.PENDING));
        int cancelledCount = 0;
        for (WorkflowRun run : queued) {
            try {
                self.cancelRun(run.getId());
                cancelledCount++;
            } catch (RuntimeException e) {
                log.warn("Skipping run {} while draining queued backlog for workflow {}: {}",
                        run.getId(), workflowId, e.getMessage());
            }
        }
        return cancelledCount;
    }

    private void requestCancellation(WorkflowRun run) {
        String runId = run.getId();
        log.info("Cancelling run {} (was {})", runId, run.getStatus());
        run.setStatus(WorkflowRunStatus.CANCELLING);
        runRepository.save(run);

        queueRepository.deleteUnclaimedByRunId(runId);

        OffsetDateTime now = OffsetDateTime.now();
        for (WorkflowJobRun jobRun : jobRunRepository.findByRunId(runId)) {
            // RUNNING jobs are left alone: their executor owns the teardown and terminalizes them
            // itself once it notices the run is cancelling.
            if (jobRun.getStatus() != WorkflowJobStatus.PENDING
                    && jobRun.getStatus() != WorkflowJobStatus.AWAITING_PICKUP) {
                continue;
            }
            for (WorkflowStepRun stepRun : stepRunRepository.findByJobRunId(jobRun.getId())) {
                if (stepRun.getStatus() == WorkflowStepStatus.PENDING
                        || stepRun.getStatus() == WorkflowStepStatus.RUNNING) {
                    stepRun.setStatus(WorkflowStepStatus.CANCELLED);
                    stepRun.setCompletedAt(now);
                    stepRunRepository.save(stepRun);
                }
            }
            jobRun.setStatus(WorkflowJobStatus.CANCELLED);
            jobRun.setCompletedAt(now);
            jobRunRepository.save(jobRun);
        }

        // Settles the run immediately when nothing was actually in flight.
        engine.checkRunCompletion(run);
    }
}
