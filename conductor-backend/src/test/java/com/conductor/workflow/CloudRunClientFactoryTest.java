package com.conductor.workflow;

import com.conductor.entity.Connection;
import com.conductor.exception.BusinessException;
import com.conductor.integration.ConnectionContext;
import com.conductor.service.ConnectionService;
import com.google.cloud.run.v2.ExecutionsClient;
import com.google.cloud.run.v2.JobsClient;
import com.google.cloud.run.v2.TasksClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CloudRunClientFactoryTest {

    @Mock private JobsClient defaultJobsClient;
    @Mock private ExecutionsClient defaultExecutionsClient;
    @Mock private TasksClient defaultTasksClient;
    @Mock private ConnectionService connectionService;

    private static ConnectionContext ctx(String key) {
        return new ConnectionContext("proj", "gcp", "conn-1", key, null, null, Map.of(), null);
    }

    private static Connection connection(String id) {
        Connection c = new Connection();
        c.setId(id);
        c.setConnectorId("gcp");
        return c;
    }

    @Test
    void forTarget_builtinConnectionId_returnsInjectedDefaultClients() {
        CloudRunClientFactory factory = new CloudRunClientFactory(
                defaultJobsClient, defaultExecutionsClient, defaultTasksClient, connectionService,
                key -> { throw new AssertionError("should not build per-connection clients for builtin"); });

        CloudRunTarget builtin = new CloudRunTarget("gcp-proj", "us-central1", "conductor-claude-code", null);
        CloudRunClientFactory.Clients clients = factory.forTarget(builtin);

        assertThat(clients.jobs()).isSameAs(defaultJobsClient);
        assertThat(clients.executions()).isSameAs(defaultExecutionsClient);
        assertThat(clients.tasks()).isSameAs(defaultTasksClient);
    }

    @Test
    void forTarget_customerConnection_buildsAndCachesPerConnection() throws Exception {
        JobsClient customJobs = mock(JobsClient.class);
        ExecutionsClient customExecutions = mock(ExecutionsClient.class);
        TasksClient customTasks = mock(TasksClient.class);
        CloudRunClientFactory.ClientsBuilder builder = mock(CloudRunClientFactory.ClientsBuilder.class);
        when(builder.build(anyString()))
                .thenReturn(new CloudRunClientFactory.Clients(customJobs, customExecutions, customTasks));
        CloudRunClientFactory factory = new CloudRunClientFactory(
                defaultJobsClient, defaultExecutionsClient, defaultTasksClient, connectionService, builder);

        Connection connection = connection("conn-1");
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(connection));
        when(connectionService.toContext(connection)).thenReturn(ctx("fake-service-account-key"));

        CloudRunTarget target = new CloudRunTarget("customer-proj", "us-east1", "conductor-t", "conn-1");
        CloudRunClientFactory.Clients first = factory.forTarget(target);
        CloudRunClientFactory.Clients second = factory.forTarget(target);

        assertThat(first.jobs()).isSameAs(customJobs);
        assertThat(second).isSameAs(first);
        verify(connectionService, times(1)).getById("conn-1");
        verify(builder, times(1)).build("fake-service-account-key");
    }

    @Test
    void forTarget_missingConnection_throwsIllegalState() {
        CloudRunClientFactory factory = new CloudRunClientFactory(
                defaultJobsClient, defaultExecutionsClient, defaultTasksClient, connectionService,
                key -> { throw new AssertionError("unreachable"); });
        when(connectionService.getById("missing")).thenReturn(Optional.empty());

        CloudRunTarget target = new CloudRunTarget("p", "r", "j", "missing");
        assertThatThrownBy(() -> factory.forTarget(target)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void forTarget_toContextThrows_wrapsMessageWithConnectionId() {
        CloudRunClientFactory factory = new CloudRunClientFactory(
                defaultJobsClient, defaultExecutionsClient, defaultTasksClient, connectionService,
                key -> { throw new AssertionError("unreachable"); });
        Connection connection = connection("conn-3");
        when(connectionService.getById("conn-3")).thenReturn(Optional.of(connection));
        when(connectionService.toContext(connection)).thenThrow(
                new com.conductor.exception.CredentialEncryptionException("Failed to decrypt credentials", null));

        CloudRunTarget target = new CloudRunTarget("p", "r", "j", "conn-3");
        assertThatThrownBy(() -> factory.forTarget(target))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conn-3")
                .hasMessageContaining("Failed to decrypt credentials");
    }

    @Test
    void forTarget_connectionWithNoAccessToken_throwsBusinessException() {
        CloudRunClientFactory factory = new CloudRunClientFactory(
                defaultJobsClient, defaultExecutionsClient, defaultTasksClient, connectionService,
                key -> { throw new AssertionError("unreachable"); });
        Connection connection = connection("conn-2");
        when(connectionService.getById("conn-2")).thenReturn(Optional.of(connection));
        when(connectionService.toContext(connection)).thenReturn(ctx(null));

        CloudRunTarget target = new CloudRunTarget("p", "r", "j", "conn-2");
        assertThatThrownBy(() -> factory.forTarget(target)).isInstanceOf(BusinessException.class);
    }

    @Test
    void evict_closesAndRemovesCachedClients_forcesRebuildOnNextUse() throws Exception {
        JobsClient customJobs = mock(JobsClient.class);
        ExecutionsClient customExecutions = mock(ExecutionsClient.class);
        TasksClient customTasks = mock(TasksClient.class);
        CloudRunClientFactory.ClientsBuilder builder = mock(CloudRunClientFactory.ClientsBuilder.class);
        when(builder.build(anyString()))
                .thenReturn(new CloudRunClientFactory.Clients(customJobs, customExecutions, customTasks));
        CloudRunClientFactory factory = new CloudRunClientFactory(
                defaultJobsClient, defaultExecutionsClient, defaultTasksClient, connectionService, builder);

        Connection connection = connection("conn-1");
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(connection));
        when(connectionService.toContext(connection)).thenReturn(ctx("fake-service-account-key"));

        CloudRunTarget target = new CloudRunTarget("p", "r", "j", "conn-1");
        factory.forTarget(target);
        factory.evict("conn-1");

        verify(customJobs).close();
        verify(customExecutions).close();
        verify(customTasks).close();

        factory.forTarget(target);
        verify(builder, times(2)).build(anyString());
    }

    @Test
    void evict_unknownConnectionId_isANoop() {
        CloudRunClientFactory factory = new CloudRunClientFactory(
                defaultJobsClient, defaultExecutionsClient, defaultTasksClient, connectionService,
                key -> { throw new AssertionError("unreachable"); });

        factory.evict("never-cached");
        factory.evict(null);
        // No exception — that's the assertion.
    }
}
