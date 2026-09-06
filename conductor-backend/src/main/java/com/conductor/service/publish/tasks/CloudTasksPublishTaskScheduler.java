package com.conductor.service.publish.tasks;

import com.conductor.workflow.RunTokenService;
import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.HttpRequest;
import com.google.cloud.tasks.v2.QueueName;
import com.google.cloud.tasks.v2.Task;
import com.google.protobuf.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * {@link PublishTaskScheduler} backed by Cloud Tasks: one HTTP task per {@link PublishTask}, delivered at
 * its {@code notBefore} to {@code POST /internal/v1/publish-targets/{id}/{dispatch|handoff|confirm}} with
 * a target-bound bearer token ({@link RunTokenService#generatePublishTaskToken}). Mirrors
 * {@code CloudTasksJobDispatcher}; see {@link PublishTaskScheduler} for why the request shape matters.
 *
 * <p>Its own queue ({@code gcp.tasks.publish-queue-name}, default {@code publish-tasks}) rather than
 * {@code workflow-jobs}: a publish that fails with a 5xx should be retried on a cadence measured in
 * seconds to minutes, not the job queue's hundred attempts backing off to an hour.
 *
 * <p>Cloud Tasks accepts a {@code scheduleTime} at most 30 days out. A task further out than
 * {@link #MAX_SCHEDULE_AHEAD} is created at the cap instead; the handler sees it arrive before the row's
 * fire time and re-arms it, so a Post scheduled months ahead still fires on the minute.
 *
 * <p>Never throws: the row is already durable and the pollers still sweep, so a failure to create the
 * task costs latency, not the publish. It is logged at ERROR because on production that latency is
 * unbounded (see {@link PublishTaskScheduler}).
 */
@Component
@Profile("!local")
public class CloudTasksPublishTaskScheduler implements PublishTaskScheduler {

    private static final Logger log = LoggerFactory.getLogger(CloudTasksPublishTaskScheduler.class);

    /** Under Cloud Tasks' 30-day ceiling, with a day to spare for clock skew and retries. */
    static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(29);
    /** How long past delivery the bearer token stays valid, covering the queue's retry window. */
    static final Duration TOKEN_GRACE = Duration.ofDays(3);

    private final CloudTasksClient tasksClient;
    private final RunTokenService runTokenService;
    private final String queuePath;
    private final String dispatchBaseUrl;

    public CloudTasksPublishTaskScheduler(CloudTasksClient tasksClient,
                                          RunTokenService runTokenService,
                                          // The project this backend runs in, where the queue lives — same
                                          // choice (and same reasoning) as CloudTasksJobDispatcher.
                                          @Value("${GCP_PROJECT_ID:}") String projectId,
                                          @Value("${gcp.tasks.location:us-central1}") String location,
                                          @Value("${gcp.tasks.publish-queue-name:publish-tasks}") String queueName,
                                          @Value("${gcp.tasks.dispatch-base-url:}") String dispatchBaseUrl) {
        this.tasksClient = tasksClient;
        this.runTokenService = runTokenService;
        this.queuePath = QueueName.of(projectId, location, queueName).toString();
        this.dispatchBaseUrl = dispatchBaseUrl;
    }

    @Override
    public void schedule(PublishTask task) {
        if (dispatchBaseUrl.isBlank()) {
            log.error("gcp.tasks.dispatch-base-url is unset — {} for publish target {} has no timed trigger; "
                    + "only the poll sweep can reach it, and on Cloud Run that sweep runs only while requests "
                    + "are being served", task.kind(), task.targetId());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime deliverAt = clamp(task.notBefore(), now);
        String token = runTokenService.generatePublishTaskToken(task.targetId(),
                deliverAt.plus(TOKEN_GRACE).toInstant());
        HttpRequest request = HttpRequest.newBuilder()
                .setUrl(url(task))
                .setHttpMethod(HttpMethod.POST)
                .putHeaders("Authorization", "Bearer " + token)
                .build();
        Task cloudTask = Task.newBuilder()
                .setHttpRequest(request)
                .setScheduleTime(Timestamp.newBuilder().setSeconds(deliverAt.toEpochSecond()).build())
                .build();
        try {
            tasksClient.createTask(queuePath, cloudTask);
            log.info("Armed {} for publish target {} at {}{}", task.kind(), task.targetId(), deliverAt,
                    deliverAt.isBefore(task.notBefore()) ? " (capped; re-armed on arrival)" : "");
        } catch (Exception e) {
            log.error("Failed to create Cloud Task ({} for publish target {} at {}): {}", task.kind(),
                    task.targetId(), deliverAt, e.getMessage(), e);
        }
    }

    static OffsetDateTime clamp(OffsetDateTime notBefore, OffsetDateTime now) {
        OffsetDateTime cap = now.plus(MAX_SCHEDULE_AHEAD);
        if (notBefore.isAfter(cap)) {
            return cap;
        }
        return notBefore.isBefore(now) ? now : notBefore;
    }

    String url(PublishTask task) {
        StringBuilder url = new StringBuilder(dispatchBaseUrl)
                .append("/internal/v1/publish-targets/").append(task.targetId())
                .append('/').append(task.kind().pathSegment())
                .append("?fireTime=").append(task.fireTimeEpochSecond());
        if (task.kind() == PublishTaskKind.CONFIRM) {
            url.append("&attempt=").append(task.attempt());
        }
        return url.toString();
    }
}
