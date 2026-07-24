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
     * <p>Cloud Run's RunJob call is itself a long-running operation with two separate points of
     * completion: the operation being <i>created</i> (fast — a single RPC round trip, confirming Cloud
     * Run accepted the request) and the operation's <i>metadata</i> resolving to the actual
     * {@code Execution} (slower, genuinely async under cold-start/control-plane load). A timeout on the
     * former means nothing was created — safe to treat as a definitive failure. A timeout on the latter
     * does NOT mean the launch failed: the request was already accepted and a real container may already
     * be running. {@link LaunchResult#executionName()} is empty in that case rather than this method
     * throwing — see {@link LaunchResult} javadoc for how a caller should proceed.
     *
     * @param target the Cloud Run Job to launch against (project, region, job name, owning connection)
     * @param task   the container task for this execution — {@code image}/{@code command} are ignored
     *               (see {@link ContainerTask} javadoc); only {@code env} and {@code timeoutMinutes} apply
     * @return the operation name, and the execution resource name if it resolved promptly
     * @throws RuntimeException if Cloud Run never even accepted/created the request, or reported a
     *         genuine failure creating it — nothing was started in either case
     */
    LaunchResult startExecution(CloudRunTarget target, ContainerTask task);

    /** Polls the current state of a previously started execution on the given target. */
    ExecutionState pollExecution(CloudRunTarget target, String executionName);

    /** Requests cancellation of a running execution on the given target (best-effort; does not block for completion). */
    void cancelExecution(CloudRunTarget target, String executionName);

    /**
     * Best-effort attempt to resolve the execution name of a RunJob operation whose metadata didn't
     * resolve promptly in {@link #startExecution} (i.e. {@link LaunchResult#executionName()} was empty).
     * Never throws — returns {@link Optional#empty()} on any error, including the metadata genuinely not
     * being ready yet.
     */
    Optional<String> tryResolveExecutionName(CloudRunTarget target, String operationName);

    enum Status { RUNNING, SUCCEEDED, FAILED, CANCELLED }

    /**
     * @param operationName  the RunJob long-running operation's resource name — always present once
     *                       {@link #startExecution} returns without throwing. Persisted immediately so a
     *                       backend restart before {@code executionName} resolves can recover via
     *                       {@link #tryResolveExecutionName} instead of relaunching a duplicate execution
     *                       (there is no idempotency key on the underlying RunJobRequest).
     * @param executionName  the execution resource name, if the operation's metadata resolved before
     *                       {@link #startExecution} gave up waiting. Empty means Cloud Run accepted the
     *                       launch but hasn't confirmed the execution yet — the caller must not treat
     *                       this as a failure; poll {@link #tryResolveExecutionName} for it separately
     *                       while relying on the container's own self-report as the primary completion
     *                       signal in the meantime.
     */
    record LaunchResult(String operationName, Optional<String> executionName) {
        public static LaunchResult confirmed(String operationName, String executionName) {
            return new LaunchResult(operationName, Optional.of(executionName));
        }

        public static LaunchResult unconfirmed(String operationName) {
            return new LaunchResult(operationName, Optional.empty());
        }
    }

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
