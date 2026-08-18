package com.conductor.workflow;

import com.google.cloud.tasks.v2.CloudTasksClient;
import com.google.cloud.tasks.v2.HttpRequest;
import com.google.cloud.tasks.v2.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CloudTasksJobDispatcher} is a no-op unless dispatch-mode=cloud-tasks (the poller stays the
 * sole dispatch path otherwise), and — when enabled — must defer task creation to after the caller's
 * transaction commits, since {@code enqueueJob}'s workflow_job_queue insert has to be durable before
 * the dispatched request can claim it.
 */
@ExtendWith(MockitoExtension.class)
class CloudTasksJobDispatcherTest {

    @Mock CloudTasksClient tasksClient;
    @Mock ObjectProvider<CloudTasksClient> tasksClientProvider;
    @Mock RunTokenService runTokenService;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private CloudTasksJobDispatcher dispatcher(String dispatchMode, String baseUrl) {
        return new CloudTasksJobDispatcher(tasksClientProvider, runTokenService, dispatchMode,
                "test-project", "us-central1", "workflow-jobs", baseUrl);
    }

    @Test
    void dispatchAfterCommit_isNoOp_whenDispatchModeIsPoll() {
        CloudTasksJobDispatcher dispatcher = dispatcher("poll", "https://backend.example.run.app");

        dispatcher.dispatchAfterCommit("run-1", "job-1");

        verify(tasksClient, never()).createTask(anyString(), any(Task.class));
    }

    @Test
    void dispatchAfterCommit_createsTaskImmediately_whenNoActiveTransaction() {
        when(tasksClientProvider.getIfAvailable()).thenReturn(tasksClient);
        when(runTokenService.generateRunToken("run-1", 2)).thenReturn("token-abc");
        CloudTasksJobDispatcher dispatcher = dispatcher("cloud-tasks", "https://backend.example.run.app");

        dispatcher.dispatchAfterCommit("run-1", "job-1");

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(tasksClient).createTask(anyString(), captor.capture());
        HttpRequest request = captor.getValue().getHttpRequest();
        assertThat(request.getUrl()).isEqualTo(
                "https://backend.example.run.app/internal/v1/workflow-runs/run-1/jobs/job-1/dispatch");
        assertThat(request.getHeadersMap()).containsEntry("Authorization", "Bearer token-abc");
    }

    @Test
    void dispatchAfterCommit_defersTaskCreation_untilTransactionCommits() {
        when(tasksClientProvider.getIfAvailable()).thenReturn(tasksClient);
        when(runTokenService.generateRunToken("run-1", 2)).thenReturn("token-abc");
        CloudTasksJobDispatcher dispatcher = dispatcher("cloud-tasks", "https://backend.example.run.app");

        TransactionSynchronizationManager.initSynchronization();
        dispatcher.dispatchAfterCommit("run-1", "job-1");
        verify(tasksClient, never()).createTask(anyString(), any(Task.class));

        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());

        verify(tasksClient).createTask(anyString(), any(Task.class));
    }

    @Test
    void dispatchAfterCommit_doesNotCreateTask_whenDispatchBaseUrlUnset() {
        CloudTasksJobDispatcher dispatcher = dispatcher("cloud-tasks", "");

        dispatcher.dispatchAfterCommit("run-1", "job-1");

        verify(tasksClient, never()).createTask(anyString(), any(Task.class));
    }

    @Test
    void dispatchAfterCommit_swallowsClientException_soCallerIsNotAffected() {
        when(tasksClientProvider.getIfAvailable()).thenReturn(tasksClient);
        when(runTokenService.generateRunToken("run-1", 2)).thenReturn("token-abc");
        when(tasksClient.createTask(anyString(), any(Task.class))).thenThrow(new RuntimeException("boom"));
        CloudTasksJobDispatcher dispatcher = dispatcher("cloud-tasks", "https://backend.example.run.app");

        assertThatNoException().isThrownBy(() -> dispatcher.dispatchAfterCommit("run-1", "job-1"));
    }

    @Test
    void dispatchAfterCommit_doesNotCreateTask_whenNoClientBeanAvailable() {
        // e.g. the local profile, where CloudTasksConfig's @Profile("!local") bean doesn't exist —
        // this must degrade to "fallback poller picks it up," not a NullPointerException.
        when(tasksClientProvider.getIfAvailable()).thenReturn(null);
        CloudTasksJobDispatcher dispatcher = dispatcher("cloud-tasks", "https://backend.example.run.app");

        assertThatNoException().isThrownBy(() -> dispatcher.dispatchAfterCommit("run-1", "job-1"));
    }
}
