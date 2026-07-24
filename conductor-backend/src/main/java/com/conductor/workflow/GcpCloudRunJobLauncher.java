package com.conductor.workflow;

import com.google.api.gax.longrunning.OperationFuture;
import com.google.api.gax.longrunning.OperationSnapshot;
import com.google.cloud.run.v2.EnvVar;
import com.google.cloud.run.v2.Execution;
import com.google.cloud.run.v2.ExecutionName;
import com.google.cloud.run.v2.ExecutionsClient;
import com.google.cloud.run.v2.JobName;
import com.google.cloud.run.v2.JobsClient;
import com.google.cloud.run.v2.RunJobRequest;
import com.google.cloud.run.v2.Task;
import com.google.cloud.run.v2.TasksClient;
import com.google.longrunning.Operation;
import com.google.protobuf.Any;
import com.google.protobuf.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link CloudRunJobLauncher} backed by the real Cloud Run Jobs API (v2). Targets whichever Job
 * resource the caller's {@link CloudRunTarget} names — the container image and command are pinned on
 * that Job (see {@link ClaudeCodeStepExecutor} javadoc for the one-time {@code gcloud run jobs create}
 * setup); per-execution overrides here only carry env vars and the timeout, since Cloud Run Job
 * execution overrides cannot change the image.
 *
 * <p>Every method resolves its clients via {@link CloudRunClientFactory#forTarget} — the
 * builtin target uses the operator-configured default clients, a customer target
 * ({@code connectionId != null}) gets clients built from that connection's own credentials.
 * Poll/cancel must go through the factory too, not default clients: a customer target's executions
 * live in the customer's own GCP project and are invisible to Conductor's credentials.
 *
 * <p>Assumes the Job has exactly one container — {@link RunJobRequest.Overrides.ContainerOverride}
 * is built without a {@code name}, which Cloud Run applies to the job's sole container.
 */
@Component
@Profile("!local")
public class GcpCloudRunJobLauncher implements CloudRunJobLauncher {

    private static final Logger log = LoggerFactory.getLogger(GcpCloudRunJobLauncher.class);

    /** Bound on the RunJob operation's *creation* (a single fast RPC round trip) — a timeout here means
     *  Cloud Run never even accepted the request, so nothing was started. */
    private static final int INITIAL_FUTURE_TIMEOUT_SECONDS = 20;
    /** Per-attempt bound on waiting for the operation's metadata (the actual {@link Execution}) to
     *  materialize — genuinely async, can be slow under cold-start/control-plane load. */
    private static final int METADATA_ATTEMPT_TIMEOUT_SECONDS = 30;
    /** Total metadata wait budget ({@link #METADATA_ATTEMPT_TIMEOUT_SECONDS} * this) before falling back
     *  to the unconfirmed-launch path rather than giving up outright. */
    private static final int METADATA_MAX_ATTEMPTS = 3;

    private final CloudRunClientFactory clientFactory;

    public GcpCloudRunJobLauncher(CloudRunClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public LaunchResult startExecution(CloudRunTarget target, ContainerTask task) {
        RunJobRequest.Overrides.ContainerOverride.Builder containerOverride =
                RunJobRequest.Overrides.ContainerOverride.newBuilder();
        task.env().forEach((k, v) -> containerOverride.addEnv(EnvVar.newBuilder().setName(k).setValue(v)));

        JobsClient jobsClient = clientFactory.forTarget(target).jobs();
        RunJobRequest request = RunJobRequest.newBuilder()
                .setName(JobName.of(target.gcpProjectId(), target.region(), target.jobName()).toString())
                .setOverrides(RunJobRequest.Overrides.newBuilder()
                        .addContainerOverrides(containerOverride)
                        .setTaskCount(1)
                        .setTimeout(Duration.newBuilder().setSeconds(task.timeoutMinutes() * 60L)))
                .build();

        OperationFuture<Execution, Execution> operation = jobsClient.runJobAsync(request);

        OperationSnapshot initial;
        try {
            // Resolves as soon as Cloud Run has acknowledged and is tracking the RunJob request — a
            // fast, single RPC round trip. A timeout here means nothing was created: safe to treat as a
            // definitive launch failure.
            initial = operation.getInitialFuture().get(INITIAL_FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to start Cloud Run Job execution: " + e.getMessage(), e);
        }
        String operationName = initial.getName();
        log.info("Cloud Run accepted RunJob request (operation {}) for job {}", operationName, target.jobName());

        Optional<String> executionName = awaitExecutionMetadata(operation, target.jobName());
        return executionName
                .map(name -> LaunchResult.confirmed(operationName, name))
                .orElseGet(() -> LaunchResult.unconfirmed(operationName));
    }

    /**
     * The operation's metadata (the actual {@link Execution}) materializes once Cloud Run has fully
     * created it — genuinely async, can be slow under cold-start/control-plane load. A
     * {@link TimeoutException} here does NOT mean the launch failed (the initial future already
     * confirmed Cloud Run accepted the request) — retried a few times before giving up on a prompt
     * answer; the caller falls back to polling {@link #tryResolveExecutionName} separately rather than
     * treating this as a hard failure. An {@link ExecutionException}, by contrast, means Cloud Run itself
     * reported the create-execution operation failed — a genuine, definitive failure.
     */
    private Optional<String> awaitExecutionMetadata(OperationFuture<Execution, Execution> operation, String jobName) {
        for (int attempt = 1; attempt <= METADATA_MAX_ATTEMPTS; attempt++) {
            try {
                Execution execution = operation.getMetadata().get(METADATA_ATTEMPT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                log.info("Started Cloud Run execution {} for job {}", execution.getName(), jobName);
                return Optional.of(execution.getName());
            } catch (TimeoutException e) {
                log.warn("Timed out waiting for Cloud Run execution metadata (attempt {}/{}) for job {}",
                        attempt, METADATA_MAX_ATTEMPTS, jobName);
            } catch (ExecutionException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IllegalStateException("Failed to start Cloud Run Job execution: " + e.getMessage(), e);
            }
        }
        log.warn("Cloud Run execution metadata still unresolved after {} attempts for job {} — proceeding "
                        + "without a confirmed execution name; the container may already be running",
                METADATA_MAX_ATTEMPTS, jobName);
        return Optional.empty();
    }

    @Override
    public Optional<String> tryResolveExecutionName(CloudRunTarget target, String operationName) {
        try {
            JobsClient jobsClient = clientFactory.forTarget(target).jobs();
            Operation operation = jobsClient.getOperationsClient().getOperation(operationName);
            Any metadata = operation.getMetadata();
            if (metadata.is(Execution.class)) {
                String executionName = metadata.unpack(Execution.class).getName();
                if (!executionName.isEmpty()) {
                    return Optional.of(executionName);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve Cloud Run execution name for operation {}: {}", operationName, e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public ExecutionState pollExecution(CloudRunTarget target, String executionName) {
        ExecutionsClient executionsClient = clientFactory.forTarget(target).executions();
        Execution execution = executionsClient.getExecution(executionName);

        if (!execution.hasCompletionTime()) {
            return ExecutionState.running();
        }
        if (execution.getCancelledCount() > 0) {
            return new ExecutionState(Status.CANCELLED, exitCodeFor(target, executionName));
        }
        if (execution.getFailedCount() > 0) {
            return new ExecutionState(Status.FAILED, exitCodeFor(target, executionName));
        }
        if (execution.getSucceededCount() > 0) {
            return new ExecutionState(Status.SUCCEEDED, exitCodeFor(target, executionName));
        }
        // Completion time set but no terminal task counts yet — treat as still running rather than
        // guessing; the next poll tick will resolve once counts land.
        return ExecutionState.running();
    }

    @Override
    public void cancelExecution(CloudRunTarget target, String executionName) {
        try {
            clientFactory.forTarget(target).executions().cancelExecutionAsync(ExecutionName.parse(executionName));
        } catch (Exception e) {
            log.warn("Failed to cancel Cloud Run execution {}: {}", executionName, e.getMessage());
        }
    }

    /**
     * Best-effort exit code lookup via the Tasks API (single-task jobs only). Never throws — this
     * is a fallback signal only; the container's own self-reported errorReason is authoritative.
     */
    private Optional<Integer> exitCodeFor(CloudRunTarget target, String executionName) {
        try {
            TasksClient tasksClient = clientFactory.forTarget(target).tasks();
            for (Task task : tasksClient.listTasks(executionName).iterateAll()) {
                if (task.hasLastAttemptResult()) {
                    return Optional.of(task.getLastAttemptResult().getExitCode());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to fetch task exit code for execution {}: {}", executionName, e.getMessage());
        }
        return Optional.empty();
    }
}
