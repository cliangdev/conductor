package com.conductor.workflow;

import java.util.Map;
import java.util.Optional;

/**
 * Launches and polls a single-container Cloud Run Job execution. The Job resource itself (image,
 * command) is pre-created out-of-band (see class javadoc on {@link ClaudeCodeStepExecutor}); this
 * abstraction only starts per-execution env overrides and reports back terminal state.
 */
public interface CloudRunJobLauncher {

    /**
     * Starts a new execution of the configured Cloud Run Job with the given container env overrides.
     *
     * @param env            environment variables to set on the (sole) container for this execution
     * @param timeoutMinutes hard wall-clock timeout for the execution, enforced by Cloud Run itself
     * @return the execution resource name (e.g. {@code projects/P/locations/L/jobs/J/executions/E}),
     *         used to poll and, if needed, cancel
     */
    String startExecution(Map<String, String> env, int timeoutMinutes);

    /** Polls the current state of a previously started execution. */
    ExecutionState pollExecution(String executionName);

    /** Requests cancellation of a running execution (best-effort; does not block for completion). */
    void cancelExecution(String executionName);

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
