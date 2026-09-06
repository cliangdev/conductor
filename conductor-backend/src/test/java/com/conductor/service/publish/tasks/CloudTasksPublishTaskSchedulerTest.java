package com.conductor.service.publish.tasks;

import com.conductor.workflow.RunTokenService;
import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpMethod;
import com.google.cloud.tasks.v2.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CloudTasksPublishTaskScheduler} has to put the task where and when the handler expects it: the
 * kind's path, the fire-time snapshot (and attempt) in the query, a target-bound bearer, and a
 * scheduleTime at notBefore — capped under Cloud Tasks' 30-day ceiling. And like the job dispatcher it
 * must defer to after commit and never throw.
 */
@ExtendWith(MockitoExtension.class)
class CloudTasksPublishTaskSchedulerTest {

    private static final OffsetDateTime FIRE = OffsetDateTime.of(2030, 1, 1, 9, 0, 0, 0, ZoneOffset.UTC);

    @Mock CloudTasksClient tasksClient;
    @Mock RunTokenService runTokenService;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private CloudTasksPublishTaskScheduler scheduler(String baseUrl) {
        return new CloudTasksPublishTaskScheduler(tasksClient, runTokenService,
                "test-project", "us-central1", "publish-tasks", baseUrl);
    }

    @Test
    void schedule_createsTaskAtNotBefore_withKindPathSnapshotAndBearer() {
        when(runTokenService.generatePublishTaskToken(eq("t-1"), any(Instant.class))).thenReturn("tok");
        OffsetDateTime notBefore = OffsetDateTime.now().plusHours(2).withNano(0);

        scheduler("https://backend.example.run.app").schedule(PublishTask.dispatch("t-1", FIRE, notBefore));

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(tasksClient).createTask(eq("projects/test-project/locations/us-central1/queues/publish-tasks"),
                captor.capture());
        Task task = captor.getValue();
        assertThat(task.getHttpRequest().getUrl()).isEqualTo(
                "https://backend.example.run.app/internal/v1/publish-targets/t-1/dispatch?fireTime="
                        + FIRE.toEpochSecond());
        assertThat(task.getHttpRequest().getHttpMethod()).isEqualTo(HttpMethod.POST);
        assertThat(task.getHttpRequest().getHeadersMap()).containsEntry("Authorization", "Bearer tok");
        assertThat(task.getScheduleTime().getSeconds()).isEqualTo(notBefore.toEpochSecond());
    }

    @Test
    void schedule_confirmCarriesAttempt_andHandoffUsesItsOwnPath() {
        CloudTasksPublishTaskScheduler scheduler = scheduler("https://b");

        assertThat(scheduler.url(PublishTask.confirm("t-2", FIRE, FIRE, 3)))
                .isEqualTo("https://b/internal/v1/publish-targets/t-2/confirm?fireTime=" + FIRE.toEpochSecond() + "&attempt=3");
        assertThat(scheduler.url(PublishTask.handoff("t-3", FIRE, FIRE)))
                .isEqualTo("https://b/internal/v1/publish-targets/t-3/handoff?fireTime=" + FIRE.toEpochSecond());
    }

    @Test
    void schedule_capsDeliveryUnderCloudTasksCeiling_whenNotBeforeIsMonthsOut() {
        when(runTokenService.generatePublishTaskToken(anyString(), any(Instant.class))).thenReturn("tok");
        OffsetDateTime before = OffsetDateTime.now();

        scheduler("https://b").schedule(PublishTask.dispatch("t-1", FIRE, before.plusDays(90)));

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(tasksClient).createTask(anyString(), captor.capture());
        long scheduled = captor.getValue().getScheduleTime().getSeconds();
        assertThat(scheduled)
                .isBetween(before.plus(CloudTasksPublishTaskScheduler.MAX_SCHEDULE_AHEAD).toEpochSecond(),
                        before.plus(CloudTasksPublishTaskScheduler.MAX_SCHEDULE_AHEAD).plusSeconds(5).toEpochSecond());
        // the snapshot in the URL is still the real fire time, so the handler can re-arm on arrival
        assertThat(captor.getValue().getHttpRequest().getUrl()).endsWith("fireTime=" + FIRE.toEpochSecond());
    }

    @Test
    void schedule_doesNotDeliverInThePast_whenNotBeforeAlreadyPassed() {
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(CloudTasksPublishTaskScheduler.clamp(now.minusMinutes(10), now)).isEqualTo(now);
        assertThat(CloudTasksPublishTaskScheduler.clamp(now.plusMinutes(10), now)).isEqualTo(now.plusMinutes(10));
    }

    @Test
    void schedule_tokenOutlivesDelivery_toCoverQueueRetries() {
        when(runTokenService.generatePublishTaskToken(anyString(), any(Instant.class))).thenReturn("tok");
        OffsetDateTime notBefore = OffsetDateTime.now().plusHours(1);

        scheduler("https://b").schedule(PublishTask.dispatch("t-1", FIRE, notBefore));

        ArgumentCaptor<Instant> exp = ArgumentCaptor.forClass(Instant.class);
        verify(runTokenService).generatePublishTaskToken(eq("t-1"), exp.capture());
        assertThat(exp.getValue()).isAfter(notBefore.plus(CloudTasksPublishTaskScheduler.TOKEN_GRACE).minusSeconds(5).toInstant());
    }

    @Test
    void scheduleAfterCommit_defersTaskCreation_untilTransactionCommits() {
        when(runTokenService.generatePublishTaskToken(anyString(), any(Instant.class))).thenReturn("tok");
        CloudTasksPublishTaskScheduler scheduler = scheduler("https://b");
        TransactionSynchronizationManager.initSynchronization();

        scheduler.scheduleAfterCommit(PublishTask.dispatch("t-1", FIRE, FIRE));
        verify(tasksClient, never()).createTask(anyString(), any(Task.class));

        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
        verify(tasksClient).createTask(anyString(), any(Task.class));
    }

    @Test
    void schedule_doesNothing_whenDispatchBaseUrlUnset() {
        scheduler("").schedule(PublishTask.dispatch("t-1", FIRE, FIRE));
        verify(tasksClient, never()).createTask(anyString(), any(Task.class));
    }

    @Test
    void schedule_swallowsClientException_soTheCallerIsNotAffected() {
        when(runTokenService.generatePublishTaskToken(anyString(), any(Instant.class))).thenReturn("tok");
        when(tasksClient.createTask(anyString(), any(Task.class))).thenThrow(new RuntimeException("boom"));
        assertThatNoException().isThrownBy(() -> scheduler("https://b").schedule(PublishTask.dispatch("t-1", FIRE, FIRE)));
    }
}
