package com.conductor.workflow;

import com.google.cloud.run.v2.EnvVar;
import com.google.cloud.run.v2.Execution;
import com.google.cloud.run.v2.ExecutionName;
import com.google.cloud.run.v2.ExecutionsClient;
import com.google.cloud.run.v2.JobName;
import com.google.cloud.run.v2.JobsClient;
import com.google.cloud.run.v2.RunJobRequest;
import com.google.cloud.run.v2.Task;
import com.google.cloud.run.v2.TasksClient;
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
 * <p>All three methods resolve their clients via {@link CloudRunClientFactory#forTarget} — the
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

    private final CloudRunClientFactory clientFactory;

    public GcpCloudRunJobLauncher(CloudRunClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public String startExecution(CloudRunTarget target, ContainerTask task) {
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

        try {
            // The RunJob LRO's initial metadata carries the freshly created Execution — waiting for
            // metadata (not the full operation) returns as soon as the execution exists, without
            // blocking for it to finish. We drive our own poll/timeout loop from there.
            Execution initial = jobsClient.runJobAsync(request).getMetadata()
                    .get(30, TimeUnit.SECONDS);
            log.info("Started Cloud Run execution {} for job {}", initial.getName(), target.jobName());
            return initial.getName();
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to start Cloud Run Job execution: " + e.getMessage(), e);
        }
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
