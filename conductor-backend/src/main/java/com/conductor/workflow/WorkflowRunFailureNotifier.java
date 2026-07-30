package com.conductor.workflow;

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
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The single seam every run-settlement path funnels through to notify on a FAILED run. Four independent
 * places terminalize a {@code WorkflowRun} to FAILED today -- {@code
 * WorkflowExecutionEngine#checkRunCompletion} (the normal all-jobs-terminal path), {@code
 * WorkflowExecutionEngine#cleanupStuckRuns} (the daily 24h sweep), {@code
 * WorkflowRunLogBroker#checkAndCompleteRun} (the self-hosted daemon's per-job failure callback), {@code
 * WorkflowTriggerService#createRun}'s zero-jobs-enqueued fail-fast, and {@code
 * WorkflowController#updateWorkflowRunStatus} (the legacy whole-run daemon report) -- and each is
 * expected to call {@link #notifyFailed} immediately after persisting the FAILED status. Routing all of
 * them through one method means a future sixth completion path can't silently forget to notify.
 *
 * <h2>Call once, right after persisting FAILED</h2>
 * Every existing call site already guards the FAILED transition itself (an {@code isTerminal()} /
 * already-FAILED early-return, or a run created and failed exactly once), so "once per run" falls out of
 * those guards rather than needing a second one here -- see each call site's own comment for its guard.
 * {@link #notifyFailed} is additionally defensive: it no-ops unless {@code run.getStatus()} is FAILED, so
 * a misplaced call (e.g. for a CANCELLED run) can't accidentally notify.
 *
 * <h2>Publish-after-commit</h2>
 * See {@link SafeSignalPublish} for why the actual publish is deferred to {@code afterCommit} and
 * swallows its own failures -- a Discord outage must never roll back the transaction that just recorded
 * the run as FAILED.
 */
@Component
public class WorkflowRunFailureNotifier {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunFailureNotifier.class);

    private final SignalBus signalBus;
    private final WorkflowJobRunRepository jobRunRepository;
    private final WorkflowStepRunRepository stepRunRepository;
    private final String frontendUrl;

    public WorkflowRunFailureNotifier(SignalBus signalBus,
                                       WorkflowJobRunRepository jobRunRepository,
                                       WorkflowStepRunRepository stepRunRepository,
                                       @Value("${frontend.url:http://localhost:3000}") String frontendUrl) {
        this.signalBus = signalBus;
        this.jobRunRepository = jobRunRepository;
        this.stepRunRepository = stepRunRepository;
        this.frontendUrl = frontendUrl;
    }

    /**
     * Publishes {@link SignalTypes#CONDUCTOR_WORKFLOW_RUN_FAILED} for {@code run}. A no-op unless {@code
     * run.getStatus()} is {@link WorkflowRunStatus#FAILED} -- in particular, never for CANCELLED.
     */
    public void notifyFailed(WorkflowRun run) {
        if (run.getStatus() != WorkflowRunStatus.FAILED) {
            return;
        }
        SafeSignalPublish.afterCommit(signalBus, buildSignal(run), log);
    }

    private Signal buildSignal(WorkflowRun run) {
        WorkflowDefinition workflow = run.getWorkflow();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("runId", run.getId());
        metadata.put("workflowId", workflow.getId());
        metadata.put("workflowName", workflow.getName());
        metadata.put("runUrl", frontendUrl + "/app/projects/" + workflow.getProject().getId()
                + "/workflows/" + workflow.getId() + "/runs/" + run.getId());

        findFailingStep(run.getId()).ifPresent(found -> {
            metadata.put("jobId", found.job().getJobId());
            if (found.step().getStepId() != null && !found.step().getStepId().isBlank()) {
                metadata.put("stepId", found.step().getStepId());
            }
            String errorReason = found.step().getErrorReason();
            if (errorReason != null && !errorReason.isBlank()) {
                metadata.put("errorReason", errorReason);
                StepFailureExplanations.explain(errorReason).ifPresent(explanation -> {
                    metadata.put("summary", explanation.summary());
                    metadata.put("remediation", explanation.remediation());
                });
            }
        });

        return Signal.of(SignalTypes.CONDUCTOR_WORKFLOW_RUN_FAILED, workflow.getProject().getId(),
                run.getId(), Instant.now(), metadata, new SignalOrigin("workflow_run", run.getId()));
    }

    private record FailingStep(WorkflowJobRun job, WorkflowStepRun step) {
    }

    /**
     * The first FAILED/LOOP_EXHAUSTED job's first FAILED step, if any. Several call sites (the 24h
     * stuck-run sweep, a zero-jobs-enqueued run) have no single failing step to point at -- this is
     * best-effort, and every metadata field it feeds is documented as optional in {@code
     * EventType#WORKFLOW_RUN_FAILED}.
     */
    private Optional<FailingStep> findFailingStep(String runId) {
        for (WorkflowJobRun jobRun : jobRunRepository.findByRunId(runId)) {
            if (jobRun.getStatus() != WorkflowJobStatus.FAILED && jobRun.getStatus() != WorkflowJobStatus.LOOP_EXHAUSTED) {
                continue;
            }
            for (WorkflowStepRun step : stepRunRepository.findByJobRunId(jobRun.getId())) {
                if (step.getStatus() == WorkflowStepStatus.FAILED) {
                    return Optional.of(new FailingStep(jobRun, step));
                }
            }
        }
        return Optional.empty();
    }
}
