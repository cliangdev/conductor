package com.conductor.workflow;

import com.google.api.core.ApiFuture;
import com.google.api.gax.longrunning.OperationFuture;
import com.google.api.gax.longrunning.OperationSnapshot;
import com.google.cloud.run.v2.Execution;
import com.google.cloud.run.v2.JobsClient;
import com.google.cloud.run.v2.RunJobRequest;
import com.google.longrunning.Operation;
import com.google.longrunning.OperationsClient;
import com.google.protobuf.Any;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the initial-future / metadata-future split in {@link GcpCloudRunJobLauncher#startExecution} —
 * the fix for the launch race where a single 30s bound on the RunJob LRO's metadata could throw even
 * though Cloud Run had already accepted the request and started a real container.
 */
@ExtendWith(MockitoExtension.class)
class GcpCloudRunJobLauncherTest {

    @Mock private CloudRunClientFactory clientFactory;
    @Mock private JobsClient jobsClient;
    @Mock private OperationFuture<Execution, Execution> operationFuture;
    @Mock private ApiFuture<OperationSnapshot> initialFuture;
    @Mock private OperationSnapshot snapshot;
    @Mock private ApiFuture<Execution> metadataFuture;

    private static final CloudRunTarget TARGET = new CloudRunTarget("gcp-proj", "us-central1", "job-1", null);
    private static final ContainerTask TASK = new ContainerTask("image:1", java.util.List.of("cmd"), Map.of(), 30);

    private GcpCloudRunJobLauncher launcher;

    @BeforeEach
    void setUp() {
        launcher = new GcpCloudRunJobLauncher(clientFactory);
    }

    /** Stubs the RunJob call chain — only needed by the {@code startExecution} tests. */
    private void stubRunJobAsync() {
        when(clientFactory.forTarget(TARGET)).thenReturn(new CloudRunClientFactory.Clients(jobsClient, null, null));
        when(jobsClient.runJobAsync(any(RunJobRequest.class))).thenReturn(operationFuture);
        when(operationFuture.getInitialFuture()).thenReturn(initialFuture);
    }

    @Test
    void initialFutureTimesOutAfterRetries_throwsLaunchUnconfirmedWithoutEverWaitingOnMetadata() throws Exception {
        stubRunJobAsync();
        when(initialFuture.get(20L, TimeUnit.SECONDS)).thenThrow(new TimeoutException("no ack"));

        assertThatThrownBy(() -> launcher.startExecution(TARGET, TASK))
                .isInstanceOf(CloudRunJobLauncher.LaunchUnconfirmedException.class)
                .hasMessageContaining("did not acknowledge the RunJob request");

        // 3 attempts at 20s each before giving up — a single timeout does not confirm nothing was created.
        verify(initialFuture, times(3)).get(20L, TimeUnit.SECONDS);
        verify(operationFuture, org.mockito.Mockito.never()).getMetadata();
    }

    @Test
    void initialFutureResolvesOnRetry_proceedsNormally() throws Exception {
        stubRunJobAsync();
        when(initialFuture.get(20L, TimeUnit.SECONDS))
                .thenThrow(new TimeoutException("no ack"))
                .thenReturn(snapshot);
        when(snapshot.getName()).thenReturn("op-1");
        when(operationFuture.getMetadata()).thenReturn(metadataFuture);
        Execution execution = Execution.newBuilder().setName("exec-1").build();
        when(metadataFuture.get(30L, TimeUnit.SECONDS)).thenReturn(execution);

        CloudRunJobLauncher.LaunchResult result = launcher.startExecution(TARGET, TASK);

        assertThat(result.operationName()).isEqualTo("op-1");
        assertThat(result.executionName()).contains("exec-1");
        verify(initialFuture, times(2)).get(20L, TimeUnit.SECONDS);
    }

    @Test
    void initialFutureReportsGenuineFailure_throwsImmediatelyWithoutRetrying() throws Exception {
        stubRunJobAsync();
        when(initialFuture.get(20L, TimeUnit.SECONDS))
                .thenThrow(new ExecutionException("Cloud Run rejected the request", new RuntimeException()));

        assertThatThrownBy(() -> launcher.startExecution(TARGET, TASK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to start Cloud Run Job execution");

        verify(initialFuture, times(1)).get(20L, TimeUnit.SECONDS);
        verify(operationFuture, org.mockito.Mockito.never()).getMetadata();
    }

    @Test
    void metadataResolvesPromptly_returnsConfirmedLaunchResult() throws Exception {
        stubRunJobAsync();
        when(initialFuture.get(20L, TimeUnit.SECONDS)).thenReturn(snapshot);
        when(snapshot.getName()).thenReturn("projects/gcp-proj/locations/us-central1/operations/op-1");
        when(operationFuture.getMetadata()).thenReturn(metadataFuture);
        Execution execution = Execution.newBuilder().setName("exec-1").build();
        when(metadataFuture.get(30L, TimeUnit.SECONDS)).thenReturn(execution);

        CloudRunJobLauncher.LaunchResult result = launcher.startExecution(TARGET, TASK);

        assertThat(result.operationName()).isEqualTo("projects/gcp-proj/locations/us-central1/operations/op-1");
        assertThat(result.executionName()).contains("exec-1");
        verify(metadataFuture, times(1)).get(30L, TimeUnit.SECONDS);
    }

    @Test
    void metadataTimesOutAfterRetries_returnsUnconfirmedRatherThanThrowing() throws Exception {
        stubRunJobAsync();
        when(initialFuture.get(20L, TimeUnit.SECONDS)).thenReturn(snapshot);
        when(snapshot.getName()).thenReturn("op-1");
        when(operationFuture.getMetadata()).thenReturn(metadataFuture);
        when(metadataFuture.get(30L, TimeUnit.SECONDS)).thenThrow(new TimeoutException("still creating"));

        CloudRunJobLauncher.LaunchResult result = launcher.startExecution(TARGET, TASK);

        assertThat(result.operationName()).isEqualTo("op-1");
        assertThat(result.executionName()).isEmpty();
        // 3 attempts at 30s each before giving up on a prompt answer.
        verify(metadataFuture, times(3)).get(30L, TimeUnit.SECONDS);
    }

    @Test
    void metadataReportsGenuineFailure_throwsRatherThanRetrying() throws Exception {
        stubRunJobAsync();
        when(initialFuture.get(20L, TimeUnit.SECONDS)).thenReturn(snapshot);
        when(snapshot.getName()).thenReturn("op-1");
        when(operationFuture.getMetadata()).thenReturn(metadataFuture);
        when(metadataFuture.get(30L, TimeUnit.SECONDS))
                .thenThrow(new ExecutionException("Cloud Run rejected the execution", new RuntimeException()));

        assertThatThrownBy(() -> launcher.startExecution(TARGET, TASK))
                .isInstanceOf(IllegalStateException.class);

        verify(metadataFuture, times(1)).get(30L, TimeUnit.SECONDS);
    }

    @Test
    void tryResolveExecutionName_unpacksExecutionFromOperationMetadata() {
        when(clientFactory.forTarget(TARGET)).thenReturn(new CloudRunClientFactory.Clients(jobsClient, null, null));
        OperationsClient operationsClient = mock(OperationsClient.class);
        when(jobsClient.getOperationsClient()).thenReturn(operationsClient);
        Execution execution = Execution.newBuilder().setName("exec-resolved").build();
        Operation operation = Operation.newBuilder().setMetadata(Any.pack(execution)).build();
        when(operationsClient.getOperation("op-1")).thenReturn(operation);

        Optional<String> resolved = launcher.tryResolveExecutionName(TARGET, "op-1");

        assertThat(resolved).contains("exec-resolved");
    }

    @Test
    void tryResolveExecutionName_metadataNotYetSet_returnsEmptyWithoutThrowing() {
        when(clientFactory.forTarget(TARGET)).thenReturn(new CloudRunClientFactory.Clients(jobsClient, null, null));
        OperationsClient operationsClient = mock(OperationsClient.class);
        when(jobsClient.getOperationsClient()).thenReturn(operationsClient);
        when(operationsClient.getOperation("op-1")).thenReturn(Operation.newBuilder().build());

        Optional<String> resolved = launcher.tryResolveExecutionName(TARGET, "op-1");

        assertThat(resolved).isEmpty();
    }

    @Test
    void tryResolveExecutionName_clientThrows_returnsEmptyWithoutThrowing() {
        when(clientFactory.forTarget(TARGET)).thenReturn(new CloudRunClientFactory.Clients(jobsClient, null, null));
        OperationsClient operationsClient = mock(OperationsClient.class);
        when(jobsClient.getOperationsClient()).thenReturn(operationsClient);
        when(operationsClient.getOperation(eq("op-1"))).thenThrow(new RuntimeException("transient"));

        Optional<String> resolved = launcher.tryResolveExecutionName(TARGET, "op-1");

        assertThat(resolved).isEmpty();
    }
}
