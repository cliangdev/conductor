package com.conductor.workflow;

import java.util.Optional;

/**
 * Launches and polls a single-container Cloud Run Job execution against a resolved {@link CloudRunTarget}.
 * The Job resource itself (image, command) is pre-created out-of-band (see class javadoc on
 * {@link ClaudeCodeStepExecutor}); this abstraction only starts per-execution env overrides and reports
 * back terminal state.
 */
public interface CloudRunJobLauncher {

    /**
     * Starts a new execution of the target Cloud Run Job with the given container task's env overrides.
     *
     * @param target the Cloud Run Job to launch against (project, region, job name, owning connection)
     * @param task   the container task for this execution — {@code image}/{@code command} are ignored
     *               (see {@link ContainerTask} javadoc); only {@code env} and {@code timeoutMinutes} apply
     * @return the execution resource name (e.g. {@code projects/P/locations/L/jobs/J/executions/E}),
     *         used to poll and, if needed, cancel
     */
    String startExecution(CloudRunTarget target, ContainerTask task);

    /** Polls the current state of a previously started execution on the given target. */
    ExecutionState pollExecution(CloudRunTarget target, String executionName);

    /** Requests cancellation of a running execution on the given target (best-effort; does not block for completion). */
    void cancelExecution(CloudRunTarget target, String executionName);

    enum Status { RUNNING, SUCCEEDED, FAILED, CANCELLED }

    /**
     * @param exitCode the sole task's container exit code, when Cloud Run's Task/TaskAttemptResult
     *                 data exposes one. Populated on a best-effort basis — the container's own
     *                 self-reported {@code errorReason} (via the step-complete callback) is the
     *                 primary signal; this is only a fallback for cases where the container never
     *                 got to report (OOM kill, image pull failure, task killed by Cloud Run).
     */
    record ExecutionState(Status status, Optional<Integer> exitCode) {
        public static ExecutionState running() {
            return new ExecutionState(Status.RUNNING, Optional.empty());
        }
    }
}
