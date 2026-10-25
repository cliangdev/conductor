package com.conductor.workflow;

import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.entity.WorkflowStepStatus;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntFunction;

/**
 * The Cloud Run step mechanics shared by every runner that executes a step as a Cloud Run Job
 * execution and learns the result from the {@link WorkflowStepRun} row the container self-reports into
 * through the {@code /internal/v1} step-complete callback: step-run row creation (with its
 * {@code workerJobId}), launch, persisting the operation and execution names, launch reconciliation,
 * polling (including {@code CANCELLING}), resume after a backend restart, timeout plus cancel, and the
 * terminal result. Extracted from {@link ClaudeCodeContainerRunner} so {@link RenderTargetStepRunner}
 * reuses the exact same behaviour; callers vary only the target, image, env, timeout and the error
 * reasons ({@link Reasons}).
 *
 * <h2>Crash recovery</h2>
 * The Cloud Run RunJob operation name is persisted onto the step-run row as soon as
 * {@link CloudRunJobLauncher#startExecution} returns — even before the execution name itself is
 * confirmed. The execution name is persisted as soon as it resolves. Either way, a backend restart
 * re-attaches via whichever of the two is known rather than relaunching a duplicate execution — Cloud
 * Run's RunJobRequest has no idempotency key, so a blind retry risks starting a second, real container.
 */
@Component
public class CloudRunStepRunner {

    private static final Logger log = LoggerFactory.getLogger(CloudRunStepRunner.class);

    private static final int POLL_INTERVAL_SECONDS = 10;
    /** How long to keep looking for an execution belonging to a launch Cloud Run never acknowledged.
     *  Sized well past the 41s lag observed in production between giving up and the execution appearing. */
    private static final int RECONCILE_WINDOW_SECONDS = 180;
    private static final int RECONCILE_POLL_SECONDS = 15;

    private final CloudRunJobLauncher launcher;
    private final WorkflowStepRunRepository stepRunRepository;
    private final WorkflowRunRepository runRepository;
    private final WorkflowRunLogBroker logBroker;
    private final ObjectMapper objectMapper;

    public CloudRunStepRunner(CloudRunJobLauncher launcher,
                              WorkflowStepRunRepository stepRunRepository,
                              WorkflowRunRepository runRepository,
                              WorkflowRunLogBroker logBroker,
                              ObjectMapper objectMapper) {
        this.launcher = launcher;
        this.stepRunRepository = stepRunRepository;
        this.runRepository = runRepository;
        this.logBroker = logBroker;
        this.objectMapper = objectMapper;
    }

    /**
     * The error reasons a runner reports.
     *
     * @param launchError   the launch failed, or Cloud Run never reported an exit code
     * @param timeout       the step ran past its budget
     * @param configError   the env factory threw {@link CredentialResolutionException}
     * @param exitCode      container exit code to errorReason, used only when the container never
     *                      self-reported a reason of its own
     * @param failureMessage optional: Cloud Run's failure text (launch error or execution failure) to
     *                      errorReason; returns null when the text is not recognised. Consulted before
     *                      {@code exitCode}
     * @param silentExit    optional: errorReason (or null for "no opinion") for a non-zero exit where the
     *                      container neither self-reported nor streamed a single log line, i.e. the
     *                      entrypoint evidently never ran. Consulted after {@code failureMessage}
     */
    public record Reasons(String launchError, String timeout, String configError, IntFunction<String> exitCode,
                          Function<String, String> failureMessage, IntFunction<String> silentExit) {
        public Reasons(String launchError, String timeout, String configError, IntFunction<String> exitCode) {
            this(launchError, timeout, configError, exitCode, null, null);
        }

        public Reasons(String launchError, String timeout, String configError, IntFunction<String> exitCode,
                       Function<String, String> failureMessage) {
            this(launchError, timeout, configError, exitCode, failureMessage, null);
        }

        /** The errorReason for Cloud Run's own failure text, or null when it is not one the runner recognises. */
        String reasonForMessage(Optional<String> message) {
            return failureMessage != null && message != null && message.isPresent()
                    ? failureMessage.apply(message.get()) : null;
        }
    }

    /**
     * @param envFactory builds the container env from the step's {@code workerJobId}; may throw
     *                   {@link CredentialResolutionException}. Not called when the step resumes.
     * @param graceMinutes extra minutes added to {@code timeoutMinutes} for the Cloud Run execution
     *                   timeout and the poll budget, so a container that enforces {@code timeoutMinutes}
     *                   itself has time to kill its child and report before Cloud Run does
     */
    public record Spec(CloudRunTarget target, String image, List<String> command, int timeoutMinutes,
                       String stepType, Function<String, Map<String, String>> envFactory, Reasons reasons,
                       int graceMinutes) {
        public Spec(CloudRunTarget target, String image, List<String> command, int timeoutMinutes,
                    String stepType, Function<String, Map<String, String>> envFactory, Reasons reasons) {
            this(target, image, command, timeoutMinutes, stepType, envFactory, reasons, 0);
        }
    }

    public StepResult run(StepExecutionContext context, Spec spec) {
        CloudRunTarget target = spec.target();
        Map<String, Object> stepDef = context.getStepDefinition();
        String projectId = context.getProjectId();
        String runId = context.getRun().getId();
        WorkflowJobRun jobRun = context.getJobRun();
        String stepId = (String) stepDef.get("id");
        String label = spec.stepType() + " step";
        int timeoutMinutes = spec.timeoutMinutes() + spec.graceMinutes();
        Reasons reasons = spec.reasons();

        WorkflowStepRun stepRun = resolveOrCreateStepRun(jobRun, stepId, stepDef, spec.stepType());
        String workerJobId = stepRun.getWorkerJobId();

        Optional<WorkflowStepRun> priorRow = stepRunRepository.findByJobRunIdAndWorkerJobId(jobRun.getId(), workerJobId);
        if (priorRow.isPresent() && isTerminal(priorRow.get().getStatus())) {
            log.info("{} {} already terminal on resume (workerJobId={}), skipping relaunch",
                    label, stepId, workerJobId);
            return resultFromRow(priorRow.get(), stepDef, Optional.empty(), "Resumed from prior run\n", reasons);
        }

        StringBuilder logBuilder = new StringBuilder();

        if (priorRow.isPresent() && priorRow.get().getExecutionName() != null) {
            WorkflowStepRun inFlightRow = priorRow.get();
            String executionName = inFlightRow.getExecutionName();
            int remainingMinutes = remainingTimeoutMinutes(inFlightRow, timeoutMinutes);
            log.info("{} {} resuming poll on prior Cloud Run execution {} (workerJobId={}, "
                    + "{}m remaining of {}m budget), skipping relaunch",
                    label, stepId, executionName, workerJobId, remainingMinutes, timeoutMinutes);
            appendLauncherLine(inFlightRow, projectId, logBuilder,
                    "→ Resuming poll on prior Cloud Run execution: " + executionName);
            return pollUntilTerminal(target, executionName, null, runId, jobRun.getId(), workerJobId, stepDef,
                    remainingMinutes, logBuilder, inFlightRow, projectId, reasons);
        }

        // The launch was previously accepted by Cloud Run (operationName persisted) but never resolved
        // to a confirmed executionName before this row was last touched — e.g. a backend restart mid-
        // launch. Resume checking rather than relaunching: RunJobRequest has no idempotency key, so a
        // blind retry here risks starting a second, duplicate execution.
        if (priorRow.isPresent() && priorRow.get().getOperationName() != null) {
            WorkflowStepRun inFlightRow = priorRow.get();
            String operationName = inFlightRow.getOperationName();
            int remainingMinutes = remainingTimeoutMinutes(inFlightRow, timeoutMinutes);
            log.info("{} {} resuming unconfirmed Cloud Run launch (operation {}, workerJobId={}, "
                    + "{}m remaining of {}m budget), skipping relaunch",
                    label, stepId, operationName, workerJobId, remainingMinutes, timeoutMinutes);
            appendLauncherLine(inFlightRow, projectId, logBuilder,
                    "→ Resuming check on unconfirmed Cloud Run launch: " + operationName);
            return pollUntilTerminal(target, null, operationName, runId, jobRun.getId(), workerJobId, stepDef,
                    remainingMinutes, logBuilder, inFlightRow, projectId, reasons);
        }

        Map<String, String> env;
        try {
            env = spec.envFactory().apply(workerJobId);
        } catch (CredentialResolutionException e) {
            log.warn("{} {} env resolution failed: {}", label, stepId, e.getMessage());
            return StepResult.failed("", reasons.configError() + ": " + e.getMessage()).withWorkerJobId(workerJobId);
        }
        ContainerTask task = new ContainerTask(spec.image(), spec.command(), env, timeoutMinutes);

        appendLauncherLine(stepRun, projectId, logBuilder,
                "→ Launching Cloud Run execution (image=" + spec.image() + ", timeout=" + timeoutMinutes + "m)");

        CloudRunJobLauncher.LaunchResult launch;
        try {
            launch = launcher.startExecution(target, task);
        } catch (CloudRunJobLauncher.LaunchUnconfirmedException e) {
            // Genuinely inconclusive, not a confirmed failure (see the exception's javadoc): no
            // operationName was ever obtained, so there is nothing to poll/resume by. But the request may
            // still land after we stop waiting — observed 41s late in production — and the execution it
            // creates carries this step's workerJobId in its env, so search for it before concluding
            // anything. Finding it converts a guess into a fact and recovers the step outright.
            log.warn("Cloud Run did not acknowledge the RunJob request for {} {}: {}", label, stepId, e.getMessage());
            appendLauncherLine(stepRun, projectId, logBuilder, "⚠ " + e.getMessage());
            appendLauncherLine(stepRun, projectId, logBuilder,
                    "→ Searching Cloud Run for an execution matching this step before giving up");
            LateExecutionSearch reconciled = findLateExecution(target, workerJobId, stepId, label);
            if (reconciled.executionName().isEmpty()) {
                // A container that self-reported while we were searching has already answered the
                // question the search was asking — don't overwrite its verdict with our ignorance.
                Optional<WorkflowStepRun> selfReported = stepRunRepository.findById(stepRun.getId())
                        .filter(row -> isTerminal(row.getStatus()));
                if (selfReported.isPresent()) {
                    appendLauncherLine(stepRun, projectId, logBuilder,
                            "← Container self-reported while searching; adopting its result");
                    return resultFromRow(selfReported.get(), stepDef, Optional.empty(), logBuilder.toString(), reasons);
                }
                appendLauncherLine(stepRun, projectId, logBuilder, reconciled.reachedApi()
                        ? "✗ Cloud Run reported no execution matching this step within "
                                + RECONCILE_WINDOW_SECONDS + "s — evidence the launch never happened"
                        : "✗ Could not reach Cloud Run to search for a late execution within "
                                + RECONCILE_WINDOW_SECONDS + "s — the launch is unverified, and a container "
                                + "may be running unobserved. Check the Cloud Run console for this step's "
                                + "start time.");
                return StepResult.failed(logBuilder.toString(), "CLOUD_RUN_LAUNCH_UNCONFIRMED").withWorkerJobId(workerJobId);
            }
            String executionName = reconciled.executionName().get();
            appendLauncherLine(stepRun, projectId, logBuilder,
                    "← Recovered unacknowledged launch: " + executionName);
            // Re-read before writing: `stepRun` has been detached for the launch wait plus the search
            // above, and the container may have self-reported its status and outputs onto the row in
            // that window. Merging the stale snapshot would silently revert them — same reason
            // WorkflowRunLogBroker.appendToStepLog re-reads.
            WorkflowStepRun fresh = stepRunRepository.findById(stepRun.getId()).orElse(stepRun);
            fresh.setExecutionName(executionName);
            stepRunRepository.save(fresh);
            stepRun.setExecutionName(executionName);
            // The launch wait and the search that followed it can together burn four minutes, and the
            // container has been running for most of that. Poll on what's left of the step's budget, as
            // the resume branches above do — not a fresh copy of it.
            return pollUntilTerminal(target, executionName, null, runId, jobRun.getId(), workerJobId, stepDef,
                    remainingTimeoutMinutes(stepRun, timeoutMinutes), logBuilder, stepRun, projectId, reasons);
        } catch (Exception e) {
            log.warn("Failed to start Cloud Run execution for {} {}: {}", label, stepId, e.getMessage());
            appendLauncherLine(stepRun, projectId, logBuilder, "✗ " + e.getMessage());
            String launchReason = reasons.reasonForMessage(Optional.ofNullable(e.getMessage()));
            return StepResult.failed(logBuilder.toString(), launchReason != null ? launchReason : reasons.launchError())
                    .withWorkerJobId(workerJobId);
        }

        // operationName is persisted unconditionally, before executionName is even known — the
        // durability point that lets a backend restart mid-launch resume checking instead of
        // relaunching a duplicate execution (see the operationName-only resume branch above).
        stepRun.setOperationName(launch.operationName());
        if (launch.executionName().isPresent()) {
            String executionName = launch.executionName().get();
            appendLauncherLine(stepRun, projectId, logBuilder, "← execution: " + executionName);
            stepRun.setExecutionName(executionName);
        } else {
            appendLauncherLine(stepRun, projectId, logBuilder,
                    "⚠ Cloud Run accepted the launch but hasn't confirmed the execution yet — will keep checking");
        }
        stepRunRepository.save(stepRun);

        return pollUntilTerminal(target, launch.executionName().orElse(null), launch.operationName(),
                runId, jobRun.getId(), workerJobId, stepDef, timeoutMinutes, logBuilder, stepRun, projectId, reasons);
    }

    /**
     * Polls Cloud Run for an execution carrying this step's {@code workerJobId}, over a window sized to
     * outlast the observed lag between giving up on the RunJob acknowledgement and the execution
     * actually appearing (41s in the production case this was written for). Returns empty when the
     * launch really never happened — the only case in which failing the step is honest.
     *
     * <p>Bounded by iteration count rather than wall clock, and sleeping via {@link #sleepSeconds}, so
     * the not-found path is unit-testable with that hook overridden.
     */
    private LateExecutionSearch findLateExecution(CloudRunTarget target, String workerJobId, String stepId,
                                                  String label) {
        int attempts = Math.max(1, RECONCILE_WINDOW_SECONDS / RECONCILE_POLL_SECONDS);
        boolean everReachedApi = false;
        for (int i = 0; i < attempts; i++) {
            CloudRunJobLauncher.ExecutionSearch search = launcher.findExecutionByWorkerJobId(target, workerJobId);
            everReachedApi |= search.reachedApi();
            if (search.executionName().isPresent()) {
                log.info("Recovered unacknowledged Cloud Run launch for {} {}: execution {} "
                        + "matched workerJobId {}", label, stepId, search.executionName().get(), workerJobId);
                return new LateExecutionSearch(true, search.executionName());
            }
            // No sleep after the final check — it would add a pointless RECONCILE_POLL_SECONDS to a
            // failure path that already holds a pooled job thread for minutes.
            if (i < attempts - 1) {
                sleepSeconds(RECONCILE_POLL_SECONDS);
            }
        }
        return new LateExecutionSearch(everReachedApi, Optional.empty());
    }

    /**
     * @param reachedApi whether any attempt actually got an answer out of Cloud Run. When false the
     *                   search establishes nothing, and the step log must say so rather than implying
     *                   we looked and found nothing.
     */
    private record LateExecutionSearch(boolean reachedApi, Optional<String> executionName) {
    }

    /**
     * Appends one launcher-side status line both to the in-memory buffer that becomes
     * {@link StepResult#getLog()} and to the step row's persisted log (via
     * {@link WorkflowRunLogBroker#appendToStepLog}), so the run-detail UI reflects launcher progress
     * without waiting for the step to reach a terminal state. {@code stepRun} must be the same row
     * {@link WorkflowJobOrchestrator#persistStepRun} will later match by workerJobId — its log-clobber
     * guard relies on this row already carrying content by the time the terminal {@link StepResult} is
     * persisted, so every line added to {@code logBuilder} in this class must also flow through here.
     */
    private void appendLauncherLine(WorkflowStepRun stepRun, String projectId, StringBuilder logBuilder, String line) {
        logBuilder.append(line).append("\n");
        logBroker.appendToStepLog(stepRun, List.of(line), projectId);
    }

    /**
     * Looks up the pre-created row for this step by {@code (jobRunId, stepId)}. Reuses it (whatever
     * its status or stored execution name — resume path) if found; otherwise creates a new RUNNING
     * row with a fresh {@code workerJobId}, recording {@code stepType} as given by the caller.
     */
    private WorkflowStepRun resolveOrCreateStepRun(WorkflowJobRun jobRun, String stepId, Map<String, Object> stepDef,
                                                     String stepType) {
        Optional<WorkflowStepRun> existing = stepRunRepository.findByJobRunIdAndStepId(jobRun.getId(), stepId);
        if (existing.isPresent() && existing.get().getWorkerJobId() != null) {
            return existing.get();
        }

        WorkflowStepRun stepRun = existing.orElseGet(WorkflowStepRun::new);
        stepRun.setJobRun(jobRun);
        stepRun.setStepId(stepId);
        stepRun.setStepName((String) stepDef.getOrDefault("name", "unnamed"));
        stepRun.setStepType(stepType);
        stepRun.setStatus(WorkflowStepStatus.RUNNING);
        stepRun.setWorkerJobId(UUID.randomUUID().toString());
        stepRun.setStartedAt(OffsetDateTime.now());
        stepRunRepository.save(stepRun);
        return stepRun;
    }

    /**
     * Bounded-iteration poll loop (not a wall-clock deadline) — matches {@link DockerStepExecutor}'s
     * shape so the timeout path is fast to unit test with {@link #sleepSeconds} overridden to a no-op.
     *
     * <p>{@code executionName} may be {@code null} on entry — meaning Cloud Run accepted the launch
     * ({@code operationName} known) but hasn't confirmed the execution yet (see
     * {@link CloudRunJobLauncher#startExecution}'s {@code LaunchResult}). In that phase, each tick both
     * checks whether the container already self-reported completion (the primary signal, keyed on
     * {@code workerJobId} alone — it doesn't need {@code executionName}) and tries
     * {@link CloudRunJobLauncher#tryResolveExecutionName} to resolve it; once resolved, the same loop
     * continues on into the normal Cloud-Run-execution-status polling below, sharing the remainder of
     * this one bounded budget rather than getting a fresh timeout window of its own.
     */
    private StepResult pollUntilTerminal(CloudRunTarget target, String executionName, String operationName,
                                          String runId, String jobRunId, String workerJobId,
                                          Map<String, Object> stepDef, int timeoutMinutes, StringBuilder logBuilder,
                                          WorkflowStepRun stepRun, String projectId, Reasons reasons) {
        // timeoutMinutes may already be <= 0 here (a resumed step whose budget elapsed while the
        // backend was down) -- 0 iterations falls straight through to the timeout tail below rather
        // than looping at least once, so a step that resumes past its deadline fails immediately
        // instead of getting a bonus poll.
        int maxIterations = timeoutMinutes <= 0 ? 0 : Math.max(1, (timeoutMinutes * 60) / POLL_INTERVAL_SECONDS);
        for (int i = 0; i < maxIterations; i++) {
            sleepSeconds(POLL_INTERVAL_SECONDS);

            if (runRepository.findStatusById(runId).orElse(null) == WorkflowRunStatus.CANCELLING) {
                if (executionName != null) {
                    launcher.cancelExecution(target, executionName);
                } else {
                    // Cloud Run accepted the launch but hasn't named the execution yet, so there's
                    // nothing to cancel by name. The execution it eventually starts is orphaned and
                    // runs to completion; Conductor still treats the step as cancelled.
                    log.warn("Run {} cancelled before its Cloud Run execution name resolved (operation {}) — "
                            + "the execution may run to completion orphaned", runId, operationName);
                }
                appendLauncherLine(stepRun, projectId, logBuilder, "✗ Cancelled");
                return StepResult.cancelled(logBuilder.toString()).withWorkerJobId(workerJobId);
            }

            if (executionName == null) {
                Optional<WorkflowStepRun> current = stepRunRepository.findByJobRunIdAndWorkerJobId(jobRunId, workerJobId);
                if (current.isPresent() && isTerminal(current.get().getStatus())) {
                    log.info("Cloud Run step (workerJobId={}) self-reported terminal before its Cloud Run "
                            + "execution name resolved", workerJobId);
                    return resultFromRow(current.get(), stepDef, Optional.empty(), logBuilder.toString(), reasons);
                }

                Optional<String> resolved;
                try {
                    resolved = launcher.tryResolveExecutionName(target, operationName);
                } catch (Exception e) {
                    log.warn("Error resolving Cloud Run execution name for operation {}: {}", operationName, e.getMessage());
                    continue;
                }
                if (resolved.isEmpty()) {
                    continue;
                }
                executionName = resolved.get();
                appendLauncherLine(stepRun, projectId, logBuilder, "← execution confirmed: " + executionName);
                stepRun.setExecutionName(executionName);
                stepRunRepository.save(stepRun);
                continue;
            }

            CloudRunJobLauncher.ExecutionState state;
            try {
                state = launcher.pollExecution(target, executionName);
            } catch (Exception e) {
                log.warn("Poll error for Cloud Run execution {}: {}", executionName, e.getMessage());
                continue;
            }

            if (state.status() != CloudRunJobLauncher.Status.RUNNING) {
                appendLauncherLine(stepRun, projectId, logBuilder, "← execution finished: " + state.status());
                return terminalResult(jobRunId, workerJobId, stepDef, state, logBuilder.toString(), reasons);
            }
        }

        appendLauncherLine(stepRun, projectId, logBuilder, "✗ Timed out after " + timeoutMinutes + " minutes");
        if (executionName != null) {
            launcher.cancelExecution(target, executionName);
        }
        return StepResult.failed(logBuilder.toString(), reasons.timeout()).withWorkerJobId(workerJobId);
    }

    private StepResult terminalResult(String jobRunId, String workerJobId, Map<String, Object> stepDef,
                                       CloudRunJobLauncher.ExecutionState cloudRunState, String log, Reasons reasons) {
        Optional<WorkflowStepRun> rowOpt = stepRunRepository.findByJobRunIdAndWorkerJobId(jobRunId, workerJobId);
        if (rowOpt.isPresent() && isTerminal(rowOpt.get().getStatus())) {
            return resultFromRow(rowOpt.get(), stepDef, cloudRunState.exitCode(), log, reasons);
        }

        // The container never got to self-report (OOM, image pull failure, task killed by Cloud
        // Run) — fall back entirely to what Cloud Run itself observed.
        if (cloudRunState.status() == CloudRunJobLauncher.Status.SUCCEEDED) {
            return StepResult.success(log, Map.of()).withWorkerJobId(workerJobId);
        }
        String errorReason = reasons.reasonForMessage(cloudRunState.failureMessage());
        if (errorReason == null && reasons.silentExit() != null && cloudRunState.exitCode().isPresent()
                && cloudRunState.exitCode().get() != 0 && rowOpt.isPresent()
                && !hasContainerOutput(rowOpt.get().getLog(), log)) {
            errorReason = reasons.silentExit().apply(cloudRunState.exitCode().get());
        }
        if (errorReason == null) {
            errorReason = cloudRunState.exitCode().map(reasons.exitCode()::apply).orElse(reasons.launchError());
        }
        return StepResult.failed(log, errorReason).withWorkerJobId(workerJobId);
    }

    /**
     * True when the step row's log holds a line the launcher did not write itself, i.e. the container
     * streamed output. {@code launcherLog} is exactly the lines this runner appended to the row.
     */
    private static boolean hasContainerOutput(String rowLog, String launcherLog) {
        if (rowLog == null || rowLog.isBlank()) {
            return false;
        }
        java.util.Set<String> launcherLines = new java.util.HashSet<>(List.of(launcherLog.split("\n")));
        for (String line : rowLog.split("\n")) {
            if (!line.isBlank() && !launcherLines.contains(line)) {
                return true;
            }
        }
        return false;
    }

    private StepResult resultFromRow(WorkflowStepRun row, Map<String, Object> stepDef,
                                      Optional<Integer> exitCodeFallback, String log, Reasons reasons) {
        String workerJobId = row.getWorkerJobId();
        if (row.getStatus() == WorkflowStepStatus.SUCCESS) {
            Map<String, String> outputs = row.getOutputJson() != null
                    ? parseOutputs(row.getOutputJson())
                    : new HashMap<>();
            StepOutputMapper.applyDeclaredOutputs(stepDef,
                    StepOutputMapper.outputsTree(objectMapper, outputs), outputs);
            return StepResult.success(log, outputs).withWorkerJobId(workerJobId);
        }

        String errorReason = row.getErrorReason() != null
                ? row.getErrorReason()
                : exitCodeFallback.map(reasons.exitCode()::apply).orElse(reasons.launchError());
        return StepResult.failed(log, errorReason).withWorkerJobId(workerJobId);
    }

    private boolean isTerminal(WorkflowStepStatus status) {
        return status == WorkflowStepStatus.SUCCESS || status == WorkflowStepStatus.FAILED;
    }

    private Map<String, String> parseOutputs(String outputJson) {
        try {
            return objectMapper.readValue(outputJson, new TypeReference<Map<String, String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse step outputs JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Minutes left in {@code fullTimeoutMinutes}' budget, measured from {@code stepRun.getStartedAt()}
     * (set once, at first launch, and never touched again on resume) rather than from now. Without this,
     * a step resumed after a backend restart would get a fresh full budget every time, so a step that
     * resumes repeatedly could run for a multiple of its declared timeout before ever being cancelled.
     * A missing {@code startedAt} falls back to the full budget.
     */
    private int remainingTimeoutMinutes(WorkflowStepRun stepRun, int fullTimeoutMinutes) {
        if (stepRun.getStartedAt() == null) {
            return fullTimeoutMinutes;
        }
        long elapsedMinutes = Duration.between(stepRun.getStartedAt(), OffsetDateTime.now()).toMinutes();
        return (int) Math.max(0, fullTimeoutMinutes - elapsedMinutes);
    }

    protected void sleepSeconds(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
