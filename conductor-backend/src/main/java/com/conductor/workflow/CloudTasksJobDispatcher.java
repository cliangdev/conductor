package com.conductor.workflow;

import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.HttpRequest;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Pushes a workflow job dispatch as a Cloud Task, so it arrives at
 * {@code POST /internal/v1/workflow-runs/{runId}/jobs/{jobId}/dispatch} as a genuine inbound HTTP
 * request. That's the point: under request-based Cloud Run billing, CPU is only allocated while a
 * request is being served — dispatching this way (rather than an internal {@code @Scheduled}
 * background poller) is what lets the service run at {@code min-instances=0}/{@code cpu-throttling}
 * without starving job execution.
 *
 * <p>{@code @Profile("!local")}: there's no real Cloud Tasks queue in local/self-hosted dev, so that
 * profile gets {@link LocalWorkflowJobDispatcher} instead — see {@link WorkflowJobDispatcher}.
 *
 * <p>The crash-recovery paths ({@link WorkflowExecutionEngine#recoverStuckJobs}, {@link
 * WorkflowExecutionEngine#recoverOrphanedClaims}) still exist independently of this — both funnel
 * back through {@link WorkflowExecutionEngine#enqueueJob}, i.e. back through here — and Cloud Tasks'
 * own retry policy redelivers a dispatch that didn't get a 2xx response.
 */
@Component
@Profile("!local")
public class CloudTasksJobDispatcher implements WorkflowJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CloudTasksJobDispatcher.class);
    private static final int RUN_TOKEN_TTL_HOURS = 2;

    private final CloudTasksClient tasksClient;
    private final RunTokenService runTokenService;
    private final String queuePath;
    private final String dispatchBaseUrl;

    public CloudTasksJobDispatcher(CloudTasksClient tasksClient,
                                   RunTokenService runTokenService,
                                   // Deliberately NOT gcp.cloudrun.project-id: that's the (optional, often
                                   // blank) project hosting the builtin Claude runtime target, a different
                                   // GCP project from the one this backend itself is deployed in. The Cloud
                                   // Tasks queue lives alongside the backend, so it needs the latter — the
                                   // same GCP_PROJECT_ID already used by GcpKmsCredentialService.
                                   @Value("${GCP_PROJECT_ID:}") String projectId,
                                   @Value("${gcp.tasks.location:us-central1}") String location,
                                   @Value("${gcp.tasks.queue-name:workflow-jobs}") String queueName,
                                   @Value("${gcp.tasks.dispatch-base-url:}") String dispatchBaseUrl) {
        this.tasksClient = tasksClient;
        this.runTokenService = runTokenService;
        this.queuePath = QueueName.of(projectId, location, queueName).toString();
        this.dispatchBaseUrl = dispatchBaseUrl;
    }

    @Override
    public void dispatchAfterCommit(String runId, String jobId) {
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
            log.error("gcp.tasks.dispatch-base-url is unset — job {} for run {} has no dispatch trigger "
                    + "and will sit in the queue until the 24h stuck-run sweep fails its run", jobId, runId);
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
            // The workflow_job_queue row this Cloud Task would have driven is already persisted, but
            // with no task ever created there's nothing left to redeliver it — the run sits PENDING
            // until the 24h stuck-run sweep (WorkflowExecutionEngine#cleanupStuckRuns) fails it. There
            // is no poller to fall back on; a transient failure here needs an operator or a retry of
            // the run itself.
            log.error("Failed to create Cloud Task for run {} job {}: {}", runId, jobId, e.getMessage(), e);
        }
    }
}
