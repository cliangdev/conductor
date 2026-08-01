package com.conductor.workflow;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalBus;
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Auto-disables a workflow once its runs fail too many times in a row, so a broken workflow that's
 * fired on a schedule (cron, or a programmatic dispatcher like the Knowledge Center's
 * {@code LibrarianDispatchService}) doesn't keep failing indefinitely while someone debugs it — every
 * trigger path that checks {@link WorkflowDefinition#isEnabled()} before creating a run (see
 * {@code WorkflowScheduler}, {@code LibrarianDispatchService}, {@code WorkflowController#dispatchWorkflow})
 * stops on its own once this trips.
 *
 * <p>Reuses the existing {@code enabled} flag rather than adding a second, parallel on/off state --
 * "auto-paused" and "manually disabled" both mean the same thing operationally (stop running this),
 * so {@link WorkflowDefinition#getAutoPausedAt()}/{@link WorkflowDefinition#getAutoPauseReason()} exist
 * only to let the UI explain *why* {@code enabled} became false and link to the run that tripped it.
 * {@code WorkflowService#setEnabled} clears them on re-enable, giving a human a clean slate to retry.
 *
 * <p>{@link #recordOutcome} is a best-effort read-modify-write, not optimistic-locked ({@link
 * WorkflowDefinition} has no {@code @Version} column): two runs of the same workflow completing in
 * the same instant can each read {@code consecutiveFailures} before either commits, under-counting by
 * one. Acceptable given the threshold is already an approximate "too many" rather than an exact
 * contract — revisit with a {@code @Version} column if that precision ever matters.
 */
@Component
public class WorkflowFailureCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(WorkflowFailureCircuitBreaker.class);

    /** Consecutive FAILED completions before a workflow is auto-disabled. */
    public static final int TRIP_THRESHOLD = 5;

    public static final String REASON_CONSECUTIVE_FAILURES = "CONSECUTIVE_FAILURES";

    private final WorkflowDefinitionRepository workflowRepository;
    private final SignalBus signalBus;

    public WorkflowFailureCircuitBreaker(WorkflowDefinitionRepository workflowRepository,
                                          SignalBus signalBus) {
        this.workflowRepository = workflowRepository;
        this.signalBus = signalBus;
    }

    /**
     * Call once a run reaches a terminal status. A no-op for anything other than SUCCESS/FAILED
     * (e.g. CANCELLED shouldn't count as evidence the workflow is broken).
     */
    public void recordOutcome(WorkflowRun run) {
        WorkflowRunStatus status = run.getStatus();
        WorkflowDefinition workflow = run.getWorkflow();

        if (status == WorkflowRunStatus.SUCCESS) {
            if (workflow.getConsecutiveFailures() != 0 || workflow.getAutoPausedAt() != null) {
                workflow.setConsecutiveFailures(0);
                clearPauseMarkers(workflow);
                workflowRepository.save(workflow);
            }
            return;
        }
        if (status != WorkflowRunStatus.FAILED) {
            return;
        }

        int failures = workflow.getConsecutiveFailures() + 1;
        workflow.setConsecutiveFailures(failures);

        // Guard on isEnabled() so a burst of already-in-flight runs completing after the trip doesn't
        // re-stamp autoPausedAt (and re-fire the notification) for every one of them.
        if (failures >= TRIP_THRESHOLD && workflow.isEnabled()) {
            workflow.setEnabled(false);
            workflow.setAutoPausedAt(OffsetDateTime.now());
            workflow.setAutoPauseReason(REASON_CONSECUTIVE_FAILURES);
            workflow.setAutoPausedRunId(run.getId());
            log.warn("Auto-pausing workflow {} ('{}') after {} consecutive failed runs -- tripped by run {}",
                    workflow.getId(), workflow.getName(), failures, run.getId());
            // Deferred/guarded rather than a bare signalBus.publish() -- this fires from inside the same
            // @Transactional run-settlement method as WorkflowRunFailureNotifier, and now that
            // WORKFLOW_AUTO_PAUSED sits in a ChannelGroup (deliverable, not a silent no-op), a
            // notification-delivery failure here must not roll back the FAILED write either. See
            // SafeSignalPublish's javadoc.
            SafeSignalPublish.afterCommit(signalBus, Signal.of(SignalTypes.CONDUCTOR_WORKFLOW_AUTO_PAUSED,
                    workflow.getProject().getId(), workflow.getId(), Instant.now(),
                    Map.of(
                            "workflowId", workflow.getId(),
                            "workflowName", workflow.getName(),
                            "consecutiveFailures", String.valueOf(failures),
                            "runId", run.getId()),
                    new SignalOrigin("workflow", workflow.getId())), log);
        }
        workflowRepository.save(workflow);
    }

    private void clearPauseMarkers(WorkflowDefinition workflow) {
        workflow.setAutoPausedAt(null);
        workflow.setAutoPauseReason(null);
        workflow.setAutoPausedRunId(null);
    }
}
