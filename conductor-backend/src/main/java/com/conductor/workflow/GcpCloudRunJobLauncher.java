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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link CloudRunJobLauncher} backed by the real Cloud Run Jobs API (v2). Targets the single
 * pre-created Job resource named by {@code gcp.cloudrun.claude-job-name} — the container image and
 * command are pinned on that Job (see {@link ClaudeCodeStepExecutor} javadoc for the one-time
 * {@code gcloud run jobs create} setup); per-execution overrides here only carry env vars and the
 * timeout, since Cloud Run Job execution overrides cannot change the image.
 *
 * <p>Assumes the Job has exactly one container — {@link RunJobRequest.Overrides.ContainerOverride}
 * is built without a {@code name}, which Cloud Run applies to the job's sole container.
 */
@Component
@Profile("!local")
public class GcpCloudRunJobLauncher implements CloudRunJobLauncher {

    private static final Logger log = LoggerFactory.getLogger(GcpCloudRunJobLauncher.class);

    private final JobsClient jobsClient;
    private final ExecutionsClient executionsClient;
    private final TasksClient tasksClient;
    private final String projectId;
    private final String region;
    private final String jobName;

    public GcpCloudRunJobLauncher(JobsClient jobsClient,
                                   ExecutionsClient executionsClient,
                                   TasksClient tasksClient,
                                   @Value("${gcp.cloudrun.project-id:}") String projectId,
                                   @Value("${gcp.cloudrun.region:us-central1}") String region,
                                   @Value("${gcp.cloudrun.claude-job-name:conductor-claude-code}") String jobName) {
        this.jobsClient = jobsClient;
        this.executionsClient = executionsClient;
        this.tasksClient = tasksClient;
        this.projectId = projectId;
        this.region = region;
        this.jobName = jobName;
    }

    @Override
    public String startExecution(Map<String, String> env, int timeoutMinutes) {
        RunJobRequest.Overrides.ContainerOverride.Builder containerOverride =
                RunJobRequest.Overrides.ContainerOverride.newBuilder();
        env.forEach((k, v) -> containerOverride.addEnv(EnvVar.newBuilder().setName(k).setValue(v)));

        RunJobRequest request = RunJobRequest.newBuilder()
                .setName(JobName.of(projectId, region, jobName).toString())
                .setOverrides(RunJobRequest.Overrides.newBuilder()
                        .addContainerOverrides(containerOverride)
                        .setTaskCount(1)
                        .setTimeout(Duration.newBuilder().setSeconds(timeoutMinutes * 60L)))
                .build();

        try {
            // The RunJob LRO's initial metadata carries the freshly created Execution — waiting for
            // metadata (not the full operation) returns as soon as the execution exists, without
            // blocking for it to finish. We drive our own poll/timeout loop from there.
            Execution initial = jobsClient.runJobAsync(request).getMetadata()
                    .get(30, TimeUnit.SECONDS);
            log.info("Started Cloud Run execution {} for job {}", initial.getName(), jobName);
            return initial.getName();
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to start Cloud Run Job execution: " + e.getMessage(), e);
        }
    }

    @Override
    public ExecutionState pollExecution(String executionName) {
        Execution execution = executionsClient.getExecution(executionName);

        if (!execution.hasCompletionTime()) {
            return ExecutionState.running();
        }
        if (execution.getCancelledCount() > 0) {
            return new ExecutionState(Status.CANCELLED, exitCodeFor(executionName));
        }
        if (execution.getFailedCount() > 0) {
            return new ExecutionState(Status.FAILED, exitCodeFor(executionName));
        }
        if (execution.getSucceededCount() > 0) {
            return new ExecutionState(Status.SUCCEEDED, exitCodeFor(executionName));
        }
        // Completion time set but no terminal task counts yet — treat as still running rather than
        // guessing; the next poll tick will resolve once counts land.
        return ExecutionState.running();
    }

    @Override
    public void cancelExecution(String executionName) {
        try {
            executionsClient.cancelExecutionAsync(ExecutionName.parse(executionName));
        } catch (Exception e) {
            log.warn("Failed to cancel Cloud Run execution {}: {}", executionName, e.getMessage());
        }
    }

    /**
     * Best-effort exit code lookup via the Tasks API (single-task jobs only). Never throws — this
     * is a fallback signal only; the container's own self-reported errorReason is authoritative.
     */
    private Optional<Integer> exitCodeFor(String executionName) {
        try {
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
