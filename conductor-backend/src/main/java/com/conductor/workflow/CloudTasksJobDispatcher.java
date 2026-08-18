package com.conductor.workflow;

import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.HttpRequest;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Pushes a workflow job dispatch as a Cloud Task, so it arrives at
 * {@code POST /internal/v1/workflow-runs/{runId}/jobs/{jobId}/dispatch} as a genuine inbound HTTP
 * request. That's the point: under request-based Cloud Run billing, CPU is only allocated while a
 * request is being served, so a job that only ever ran on an internal {@code @Scheduled} background
 * thread (see {@link WorkflowExecutionEngine#pollQueue}) would starve under {@code cpu-throttling}
 * whenever no unrelated request happened to be in flight. Routing dispatch itself through an HTTP
 * request fixes that at the source, instead of working around it.
 *
 * <p>A no-op (see {@link #dispatchAfterCommit}) unless {@code conductor.workflow.job-executor.dispatch-mode}
 * is {@code cloud-tasks} — the poller remains the sole dispatch path otherwise.
 */
@Component
public class CloudTasksJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CloudTasksJobDispatcher.class);
    private static final int RUN_TOKEN_TTL_HOURS = 2;

    // Resolved lazily, not injected directly: CloudTasksClient only exists under @Profile("!local")
    // (see CloudTasksConfig), but this dispatcher is a plain @Component needed in every profile —
    // WorkflowExecutionEngine depends on it unconditionally. Eagerly requiring the client would break
    // context startup wherever the client bean isn't registered, even though dispatch-mode=poll (the
    // default, and every local/test profile) never actually needs it.
    private final ObjectProvider<CloudTasksClient> tasksClientProvider;
    private final RunTokenService runTokenService;
    private final boolean enabled;
    private final String queuePath;
    private final String dispatchBaseUrl;

    public CloudTasksJobDispatcher(ObjectProvider<CloudTasksClient> tasksClientProvider,
                                   RunTokenService runTokenService,
                                   @Value("${conductor.workflow.job-executor.dispatch-mode:poll}") String dispatchMode,
                                   @Value("${gcp.cloudrun.project-id:}") String projectId,
                                   @Value("${gcp.tasks.location:us-central1}") String location,
                                   @Value("${gcp.tasks.queue-name:workflow-jobs}") String queueName,
                                   @Value("${gcp.tasks.dispatch-base-url:}") String dispatchBaseUrl) {
        this.tasksClientProvider = tasksClientProvider;
        this.runTokenService = runTokenService;
        this.enabled = "cloud-tasks".equals(dispatchMode);
        this.queuePath = QueueName.of(projectId, location, queueName).toString();
        this.dispatchBaseUrl = dispatchBaseUrl;
    }

    /**
     * Called from {@link WorkflowExecutionEngine#enqueueJob}, itself always {@code @Transactional}.
     * Deferring to {@code afterCommit} avoids a race where the Cloud Task fires and the dispatch
     * endpoint claims the {@code workflow_job_queue} row before the enqueueing transaction has
     * actually committed it — which would silently drop the dispatch (the row wouldn't be visible
     * yet) and leave the job to the fallback poller instead of the fast path.
     */
    public void dispatchAfterCommit(String runId, String jobId) {
        if (!enabled) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(runId, jobId);
                }
            });
        } else {
            dispatch(runId, jobId);
        }
    }

    private void dispatch(String runId, String jobId) {
        if (dispatchBaseUrl.isBlank()) {
            log.error("dispatch-mode=cloud-tasks but gcp.tasks.dispatch-base-url is unset — "
                    + "job {} for run {} will only be picked up by the fallback poller", jobId, runId);
            return;
        }
        CloudTasksClient tasksClient = tasksClientProvider.getIfAvailable();
        if (tasksClient == null) {
            log.error("dispatch-mode=cloud-tasks but no CloudTasksClient bean is available — "
                    + "job {} for run {} will only be picked up by the fallback poller", jobId, runId);
            return;
        }
        String token = runTokenService.generateRunToken(runId, RUN_TOKEN_TTL_HOURS);
        String url = dispatchBaseUrl + "/internal/v1/workflow-runs/" + runId + "/jobs/" + jobId + "/dispatch";
        HttpRequest request = HttpRequest.newBuilder()
                .setUrl(url)
                .setHttpMethod(HttpMethod.POST)
                .putHeaders("Authorization", "Bearer " + token)
                .build();
        try {
            tasksClient.createTask(queuePath, Task.newBuilder().setHttpRequest(request).build());
        } catch (Exception e) {
            // Not fatal: the workflow_job_queue row this Cloud Task would have driven is already
            // persisted, so the fallback poller (WorkflowExecutionEngine#pollQueue) will still pick
            // it up — just later than the fast path.
            log.error("Failed to create Cloud Task for run {} job {} — falling back to poll-based "
                    + "pickup: {}", runId, jobId, e.getMessage(), e);
        }
    }
}
