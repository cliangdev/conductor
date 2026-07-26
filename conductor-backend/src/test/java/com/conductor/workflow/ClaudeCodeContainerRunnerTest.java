package com.conductor.workflow;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.entity.Connection;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.entity.WorkflowStepStatus;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.CredentialConnector;
import com.conductor.integration.CredentialRequest;
import com.conductor.integration.RuntimeCredential;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.service.ActiveConnectionResolver;
import com.conductor.service.RuntimeTargetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code credentials:}/{@code env:} resolution and injection in {@link ClaudeCodeContainerRunner#buildEnv}
 * (Phase B of the connector-issued runtime credential feature) — shared by both claude-code callers
 * ({@link ClaudeCodeStepExecutor}, {@link ClaudeCodeAgentStepRuntime}). Exercises the runner directly,
 * mirroring {@link ClaudeCodeStepExecutorTest}'s setup.
 */
@ExtendWith(MockitoExtension.class)
class ClaudeCodeContainerRunnerTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String JOB_RUN_ID = "jobrun-1";

    @Mock private CloudRunJobLauncher launcher;
    @Mock private ProviderCredentialService credentialService;
    @Mock private WorkflowStepRunRepository stepRunRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private RunTokenService runTokenService;
    @Mock private ProjectSettingsRepository projectSettingsRepository;
    @Mock private RuntimeTargetService runtimeTargetService;
    @Mock private WorkflowRunLogBroker logBroker;
    @Mock private ActiveConnectionResolver activeConnectionResolver;
    @Mock private ConnectorRegistry connectorRegistry;
    @Mock private CredentialConnector githubCredentialConnector;

    private ClaudeCodeContainerRunner runner;

    @BeforeEach
    void setUp() {
        RuntimeTargetResolver runtimeTargetResolver = new RuntimeTargetResolver("gcp-proj", "us-central1",
                "conductor-claude-code", runtimeTargetService, projectSettingsRepository);
        runner = new ClaudeCodeContainerRunner(launcher, runtimeTargetResolver, credentialService,
                stepRunRepository, runRepository, runTokenService, projectSettingsRepository, new WorkflowInterpolator(),
                new ObjectMapper(), logBroker, activeConnectionResolver, connectorRegistry, "http://localhost:8080") {
            @Override
            protected void sleepSeconds(int seconds) {
                // no-op for fast tests
            }
        };
    }

    private Map<String, Object> stepDef() {
        Map<String, Object> stepDef = new LinkedHashMap<>();
        stepDef.put("id", "review");
        return stepDef;
    }

    private StepExecutionContext context(Map<String, Object> eventPayload) {
        WorkflowRun run = new WorkflowRun();
        run.setId("run-123");
        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setId(JOB_RUN_ID);
        jobRun.setJobId("analyze");
        RuntimeContext ctx = new RuntimeContext(eventPayload, Map.of(), Map.of(), Map.of());
        return new StepExecutionContext(run, jobRun, stepDef(), ctx, PROJECT_ID, "cloud-run");
    }

    private void stubHappyCredentialsAndLaunch() {
        when(credentialService.resolveApiKey(PROJECT_ID, "claude-code")).thenReturn(Optional.of("cc-oauth-xyz"));
        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("run-token");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class)))
                .thenReturn(CloudRunJobLauncher.LaunchResult.confirmed("op-1", "exec-1"));
        when(launcher.pollExecution(any(CloudRunTarget.class), eq("exec-1")))
                .thenReturn(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.SUCCEEDED, Optional.empty()));
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
    }

    private ClaudeCodeContainerRunner.ClaudeCodeInvocation invocation(List<Map<String, Object>> credentials,
                                                                       Map<String, String> extraEnv) {
        return new ClaudeCodeContainerRunner.ClaudeCodeInvocation(
                "Review this PR", null, null, null, false, null, "claude-code", credentials, extraEnv);
    }

    @Test
    void credentialResolves_andIsInjectedIntoContainerEnv() {
        stubHappyCredentialsAndLaunch();
        Connection conn = new Connection();
        conn.setId("conn-gh");
        when(activeConnectionResolver.resolve(PROJECT_ID, "github")).thenReturn(Optional.of(conn));
        when(connectorRegistry.findCredential("github")).thenReturn(Optional.of(githubCredentialConnector));
        when(githubCredentialConnector.issueRuntimeCredential(eq(conn), any(CredentialRequest.class)))
                .thenReturn(new RuntimeCredential("GH_TOKEN", "ghs_tok123", Instant.now().plusSeconds(3600)));

        List<Map<String, Object>> credentials = List.of(Map.of("connector", "github", "as", "GH_TOKEN"));
        StepResult result = runner.run(context(Map.of("repoFullName", "Rexworks-LLC/nexus-backend")),
                invocation(credentials, Map.of()));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        ArgumentCaptor<ContainerTask> taskCaptor = ArgumentCaptor.forClass(ContainerTask.class);
        verify(launcher).startExecution(any(CloudRunTarget.class), taskCaptor.capture());
        assertThat(taskCaptor.getValue().env()).containsEntry("GH_TOKEN", "ghs_tok123");

        // Best-effort repo scope hint threaded from the event payload.
        ArgumentCaptor<CredentialRequest> requestCaptor = ArgumentCaptor.forClass(CredentialRequest.class);
        verify(githubCredentialConnector).issueRuntimeCredential(eq(conn), requestCaptor.capture());
        assertThat(requestCaptor.getValue().repoFullName()).isEqualTo("Rexworks-LLC/nexus-backend");
    }

    @Test
    void cancellingRun_cancelsCloudRunExecutionAndReturnsCancelled() {
        when(credentialService.resolveApiKey(PROJECT_ID, "claude-code")).thenReturn(Optional.of("cc-oauth-xyz"));
        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("run-token");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class)))
                .thenReturn(CloudRunJobLauncher.LaunchResult.confirmed("op-1", "exec-1"));
        when(runRepository.findStatusById("run-123")).thenReturn(Optional.of(WorkflowRunStatus.CANCELLING));

        StepResult result = runner.run(context(Map.of()), invocation(List.of(), Map.of()));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.CANCELLED);
        verify(launcher).cancelExecution(any(CloudRunTarget.class), eq("exec-1"));
        verify(launcher, never()).pollExecution(any(CloudRunTarget.class), anyString());
    }

    @Test
    void cancellingRun_beforeExecutionNameResolves_stillCancelsWithoutCallingCloudRun() {
        when(credentialService.resolveApiKey(PROJECT_ID, "claude-code")).thenReturn(Optional.of("cc-oauth-xyz"));
        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("run-token");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class)))
                .thenReturn(CloudRunJobLauncher.LaunchResult.unconfirmed("op-1"));
        when(runRepository.findStatusById("run-123")).thenReturn(Optional.of(WorkflowRunStatus.CANCELLING));

        StepResult result = runner.run(context(Map.of()), invocation(List.of(), Map.of()));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.CANCELLED);
        verify(launcher, never()).cancelExecution(any(CloudRunTarget.class), anyString());
        verify(launcher, never()).tryResolveExecutionName(any(CloudRunTarget.class), anyString());
    }

    @Test
    void extraEnv_isMergedIntoContainerEnv() {
        stubHappyCredentialsAndLaunch();

        StepResult result = runner.run(context(Map.of()), invocation(List.of(), Map.of("MY_VAR", "hello")));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        ArgumentCaptor<ContainerTask> taskCaptor = ArgumentCaptor.forClass(ContainerTask.class);
        verify(launcher).startExecution(any(CloudRunTarget.class), taskCaptor.capture());
        assertThat(taskCaptor.getValue().env()).containsEntry("MY_VAR", "hello");
    }

    @Test
    void missingActiveConnection_failsStepClearly() {
        when(credentialService.resolveApiKey(PROJECT_ID, "claude-code")).thenReturn(Optional.of("cc-oauth-xyz"));
        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("run-token");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
        when(activeConnectionResolver.resolve(PROJECT_ID, "github")).thenReturn(Optional.empty());

        List<Map<String, Object>> credentials = List.of(Map.of("connector", "github", "as", "GH_TOKEN"));
        StepResult result = runner.run(context(Map.of()), invocation(credentials, Map.of()));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).contains("CLAUDE_CREDENTIAL_ERROR").contains("No active connection found for connector: github");
        verifyNoInteractions(launcher);
    }

    @Test
    void connectorNotCredentialCapable_failsStepClearly() {
        when(credentialService.resolveApiKey(PROJECT_ID, "claude-code")).thenReturn(Optional.of("cc-oauth-xyz"));
        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("run-token");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
        Connection conn = new Connection();
        conn.setId("conn-discord");
        when(activeConnectionResolver.resolve(PROJECT_ID, "discord")).thenReturn(Optional.of(conn));
        when(connectorRegistry.findCredential("discord")).thenReturn(Optional.empty());

        List<Map<String, Object>> credentials = List.of(Map.of("connector", "discord", "as", "DISCORD_TOKEN"));
        StepResult result = runner.run(context(Map.of()), invocation(credentials, Map.of()));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).contains("does not support issuing runtime credentials");
        verifyNoInteractions(launcher);
    }

    @Test
    void reservedKeyCollisionInCredentials_failsStepClearly_withoutResolvingConnection() {
        when(credentialService.resolveApiKey(PROJECT_ID, "claude-code")).thenReturn(Optional.of("cc-oauth-xyz"));
        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("run-token");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());

        List<Map<String, Object>> credentials = List.of(Map.of("connector", "github", "as", "CONDUCTOR_API_KEY"));
        StepResult result = runner.run(context(Map.of()), invocation(credentials, Map.of()));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).contains("reserved env key");
        verifyNoInteractions(activeConnectionResolver, connectorRegistry, launcher);
    }

    @Test
    void reservedKeyCollisionInExtraEnv_failsStepClearly() {
        when(credentialService.resolveApiKey(PROJECT_ID, "claude-code")).thenReturn(Optional.of("cc-oauth-xyz"));
        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("run-token");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());

        StepResult result = runner.run(context(Map.of()), invocation(List.of(), Map.of("CLAUDE_CODE_OAUTH_TOKEN", "sneaky")));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).contains("reserved env key");
        verifyNoInteractions(launcher);
    }

    @Test
    void resumedStepPastItsTimeoutBudget_failsImmediatelyAndCancelsExecution() {
        when(credentialService.resolveApiKey(PROJECT_ID, "claude-code")).thenReturn(Optional.of("cc-oauth-xyz"));
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());

        WorkflowStepRun priorRow = new WorkflowStepRun();
        priorRow.setStepId("review");
        priorRow.setWorkerJobId("worker-1");
        priorRow.setStatus(WorkflowStepStatus.RUNNING);
        priorRow.setExecutionName("exec-1");
        // Started well past the 30-minute budget below -- simulates a step resumed (e.g. after a
        // backend restart) whose elapsed wall-clock time already exceeds its declared timeout.
        priorRow.setStartedAt(OffsetDateTime.now().minusMinutes(45));
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), eq("review"))).thenReturn(Optional.of(priorRow));
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(eq(JOB_RUN_ID), eq("worker-1"))).thenReturn(Optional.of(priorRow));

        ClaudeCodeContainerRunner.ClaudeCodeInvocation inv = new ClaudeCodeContainerRunner.ClaudeCodeInvocation(
                "Review this PR", null, null, 30, false, null, "claude-code", List.of(), Map.of());

        StepResult result = runner.run(context(Map.of()), inv);

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).isEqualTo("CLAUDE_TIMEOUT");
        verify(launcher).cancelExecution(any(CloudRunTarget.class), eq("exec-1"));
        verify(launcher, never()).pollExecution(any(CloudRunTarget.class), anyString());
    }

    @Test
    void noCredentialsOrExtraEnv_behavesLikeBeforePhaseB() {
        stubHappyCredentialsAndLaunch();

        StepResult result = runner.run(context(Map.of()), invocation(List.of(), Map.of()));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        verifyNoInteractions(activeConnectionResolver, connectorRegistry);
    }
}
