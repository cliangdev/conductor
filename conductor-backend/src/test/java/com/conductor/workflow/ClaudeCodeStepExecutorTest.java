package com.conductor.workflow;

import com.conductor.agent.credential.ProviderCredentialService;
import com.conductor.entity.RuntimeTarget;
import com.conductor.entity.RuntimeTargetStatus;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.entity.WorkflowStepStatus;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.service.RuntimeTargetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClaudeCodeStepExecutorTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String JOB_RUN_ID = "jobrun-1";

    @Mock private CloudRunJobLauncher launcher;
    @Mock private ProviderCredentialService credentialService;
    @Mock private WorkflowStepRunRepository stepRunRepository;
    @Mock private RunTokenService runTokenService;
    @Mock private ProjectSettingsRepository projectSettingsRepository;
    @Mock private RuntimeTargetService runtimeTargetService;
    @Mock private WorkflowRunLogBroker logBroker;

    private RuntimeTargetResolver runtimeTargetResolver;
    private ClaudeCodeContainerRunner runner;

    private ClaudeCodeStepExecutor executor;

    @BeforeEach
    void setUp() {
        runtimeTargetResolver = new RuntimeTargetResolver("gcp-proj", "us-central1", "conductor-claude-code",
                runtimeTargetService);
        // sleepSeconds seam now lives on the extracted runner (ClaudeCodeStepExecutor delegates all
        // container-execution mechanics to it) — this test still exercises the executor end-to-end
        // through that runner, just with the poll sleep stubbed out for speed.
        runner = new ClaudeCodeContainerRunner(launcher, runtimeTargetResolver, credentialService,
                stepRunRepository, runTokenService, projectSettingsRepository,
                new WorkflowInterpolator(), new ObjectMapper(), logBroker, "http://localhost:8080") {
            @Override
            protected void sleepSeconds(int seconds) {
                // no-op for fast tests
            }
        };
        executor = new ClaudeCodeStepExecutor(runner, new WorkflowInterpolator());
    }

    private Map<String, Object> baseStepDef() {
        Map<String, Object> stepDef = new LinkedHashMap<>();
        stepDef.put("id", "seo");
        stepDef.put("uses", "claude-code");
        stepDef.put("prompt", "Analyze the data");
        return stepDef;
    }

    private StepExecutionContext context(Map<String, Object> stepDef, String runsOn) {
        WorkflowRun run = new WorkflowRun();
        run.setId("run-123");
        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setId(JOB_RUN_ID);
        jobRun.setJobId("analyze");
        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of());
        return new StepExecutionContext(run, jobRun, stepDef, ctx, PROJECT_ID, runsOn);
    }

    private void stubHappyCredentials() {
        when(credentialService.resolveApiKey(PROJECT_ID, "claude-code")).thenReturn(Optional.of("cc-oauth-xyz"));
        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("run-token");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
    }

    @Test
    void getStepType_returnsClaudeCode() {
        assertThat(executor.getStepType()).isEqualTo("claude-code");
    }

    @Test
    void execute_rejectsNonCloudRunRunsOn() {
        StepResult result = executor.execute(context(baseStepDef(), "conductor"));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).contains("CLAUDE_INVALID_RUNS_ON");
        verifyNoInteractions(launcher, stepRunRepository, credentialService);
    }

    @Test
    void execute_unknownRuntimeTargetReturnsRuntimeTargetNotFound() {
        when(runtimeTargetService.findByProjectIdAndName(PROJECT_ID, "my-target")).thenReturn(Optional.empty());

        StepResult result = executor.execute(context(baseStepDef(), "my-target"));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).contains("RUNTIME_TARGET_NOT_FOUND");
        verifyNoInteractions(launcher, stepRunRepository, credentialService);
    }

    @Test
    void execute_provisioningRuntimeTargetReturnsRuntimeTargetNotReady() {
        RuntimeTarget target = new RuntimeTarget();
        target.setName("my-target");
        target.setStatus(RuntimeTargetStatus.PROVISIONING);
        when(runtimeTargetService.findByProjectIdAndName(PROJECT_ID, "my-target")).thenReturn(Optional.of(target));

        StepResult result = executor.execute(context(baseStepDef(), "my-target"));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).contains("RUNTIME_TARGET_NOT_READY");
        verifyNoInteractions(launcher, stepRunRepository, credentialService);
    }

    @Test
    void execute_activeRuntimeTargetLaunchesAgainstResolvedCloudRunTarget() {
        RuntimeTarget target = new RuntimeTarget();
        target.setName("my-target");
        target.setStatus(RuntimeTargetStatus.ACTIVE);
        target.setConnectionId("conn-1");
        when(runtimeTargetService.findByProjectIdAndName(PROJECT_ID, "my-target")).thenReturn(Optional.of(target));
        when(runtimeTargetService.configOf(target)).thenReturn(new RuntimeTargetService.TargetRuntimeConfig(
                "customer-proj", "us-east1", "conductor-my-target",
                "us-east1-docker.pkg.dev/customer-proj/repo/image:1", List.of()));
        stubHappyCredentials();
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class))).thenReturn("exec-1");
        when(launcher.pollExecution(any(CloudRunTarget.class), eq("exec-1")))
                .thenReturn(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.SUCCEEDED, Optional.empty()));
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(eq(JOB_RUN_ID), anyString()))
                .thenReturn(Optional.empty());

        StepResult result = executor.execute(context(baseStepDef(), "my-target"));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        verify(launcher).startExecution(argThat(t ->
                "customer-proj".equals(t.gcpProjectId()) &&
                "us-east1".equals(t.region()) &&
                "conductor-my-target".equals(t.jobName()) &&
                "conn-1".equals(t.connectionId())
        ), argThat(task -> "us-east1-docker.pkg.dev/customer-proj/repo/image:1".equals(task.image())));
    }

    @Test
    void execute_missingCredentialReturnsSubscriptionNotConfigured() {
        when(credentialService.resolveApiKey(PROJECT_ID, "claude-code")).thenReturn(Optional.empty());

        StepResult result = executor.execute(context(baseStepDef(), "cloud-run"));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).contains("CLAUDE_SUBSCRIPTION_NOT_CONFIGURED");
        verifyNoInteractions(launcher, stepRunRepository);
    }

    @Test
    void execute_mintsRunScopedMcpTokenWhenConductorMcpEnabled() {
        stubHappyCredentials();
        when(runTokenService.generateMcpToken(eq("run-123"), eq(PROJECT_ID), anyInt())).thenReturn("mcp-token-xyz");
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class))).thenReturn("exec-1");
        when(launcher.pollExecution(any(CloudRunTarget.class), eq("exec-1")))
                .thenReturn(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.SUCCEEDED, Optional.empty()));
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(eq(JOB_RUN_ID), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> stepDef = baseStepDef();
        stepDef.put("conductor_mcp", true);
        StepResult result = executor.execute(context(stepDef, "cloud-run"));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        verify(launcher).startExecution(any(CloudRunTarget.class),
                argThat(task -> "mcp-token-xyz".equals(task.env().get("CONDUCTOR_API_KEY"))));
    }

    @Test
    void execute_happyPathAppliesDeclaredOutputsAndCarriesWorkerJobId() {
        stubHappyCredentials();
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class))).thenReturn("exec-1");
        when(launcher.pollExecution(any(CloudRunTarget.class), eq("exec-1")))
                .thenReturn(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.SUCCEEDED, Optional.empty()));

        WorkflowStepRun completedRow = new WorkflowStepRun();
        completedRow.setStatus(WorkflowStepStatus.SUCCESS);
        completedRow.setWorkerJobId("worker-123");
        completedRow.setOutputJson("{\"summary\":\"All good\",\"document_title\":\"Weekly Report\"}");
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(eq(JOB_RUN_ID), anyString()))
                .thenReturn(Optional.empty(), Optional.of(completedRow));

        Map<String, Object> stepDef = baseStepDef();
        stepDef.put("conductor_mcp", true);
        stepDef.put("outputs", Map.of("title", "body.document_title"));
        when(runTokenService.generateMcpToken(eq("run-123"), eq(PROJECT_ID), anyInt())).thenReturn("ck_abc123");

        StepResult result = executor.execute(context(stepDef, "cloud-run"));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        assertThat(result.getWorkerJobId()).isEqualTo("worker-123");
        assertThat(result.getOutputs().get("summary")).isEqualTo("All good");
        assertThat(result.getOutputs().get("title")).isEqualTo("Weekly Report");

        verify(launcher).startExecution(argThat(target ->
                "gcp-proj".equals(target.gcpProjectId()) &&
                "us-central1".equals(target.region()) &&
                "conductor-claude-code".equals(target.jobName()) &&
                target.connectionId() == null
        ), argThat(task ->
                "cc-oauth-xyz".equals(task.env().get("CLAUDE_CODE_OAUTH_TOKEN")) &&
                "ck_abc123".equals(task.env().get("CONDUCTOR_API_KEY")) &&
                "Analyze the data".equals(task.env().get("CONDUCTOR_STEP_PROMPT")) &&
                task.env().get("CONDUCTOR_STEP_COMPLETE_URL")
                        .equals("http://localhost:8080/internal/v1/workflow-runs/run-123/steps/"
                                + task.env().get("CONDUCTOR_WORKER_JOB_ID") + "/complete") &&
                task.timeoutMinutes() == 30 &&
                RunnerImage.DEFAULT.equals(task.image())
        ));
    }

    @Test
    void execute_neverSetsAnthropicApiKeyEnvVar() {
        stubHappyCredentials();
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class))).thenReturn("exec-1");
        when(launcher.pollExecution(any(CloudRunTarget.class), eq("exec-1")))
                .thenReturn(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.SUCCEEDED, Optional.empty()));
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(eq(JOB_RUN_ID), anyString()))
                .thenReturn(Optional.empty());

        executor.execute(context(baseStepDef(), "cloud-run"));

        verify(launcher).startExecution(any(CloudRunTarget.class),
                argThat(task -> !task.env().containsKey("ANTHROPIC_API_KEY")
                        && task.env().containsKey("CLAUDE_CODE_OAUTH_TOKEN")));
    }

    @Test
    void execute_exitCode10MapsToClaudeAgentError() {
        assertExitCodeMapping(10, "CLAUDE_AGENT_ERROR");
    }

    @Test
    void execute_exitCode11MapsToClaudeAuthError() {
        assertExitCodeMapping(11, "CLAUDE_AUTH_ERROR");
    }

    @Test
    void execute_exitCode12MapsToClaudeRateLimited() {
        assertExitCodeMapping(12, "CLAUDE_RATE_LIMITED");
    }

    @Test
    void execute_exitCode13MapsToClaudeTimeout() {
        assertExitCodeMapping(13, "CLAUDE_TIMEOUT");
    }

    @Test
    void execute_exitCode20MapsToClaudeConfigError() {
        assertExitCodeMapping(20, "CLAUDE_CONFIG_ERROR");
    }

    @Test
    void execute_unknownExitCodeMapsToClaudeLaunchError() {
        assertExitCodeMapping(77, "CLAUDE_LAUNCH_ERROR");
    }

    private void assertExitCodeMapping(int exitCode, String expectedReason) {
        stubHappyCredentials();
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class))).thenReturn("exec-1");
        when(launcher.pollExecution(any(CloudRunTarget.class), eq("exec-1")))
                .thenReturn(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.FAILED, Optional.of(exitCode)));
        // Container never got to self-report — no terminal row ever appears.
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(eq(JOB_RUN_ID), anyString()))
                .thenReturn(Optional.empty());

        StepResult result = executor.execute(context(baseStepDef(), "cloud-run"));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).isEqualTo(expectedReason);
    }

    @Test
    void execute_containerErrorReasonPreferredOverExitCodeMapping() {
        stubHappyCredentials();
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class))).thenReturn("exec-1");
        // Cloud Run itself reports exit code 77 (would map to CLAUDE_LAUNCH_ERROR)...
        when(launcher.pollExecution(any(CloudRunTarget.class), eq("exec-1")))
                .thenReturn(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.FAILED, Optional.of(77)));

        // ...but the container self-reported its own, more specific errorReason first.
        WorkflowStepRun completedRow = new WorkflowStepRun();
        completedRow.setStatus(WorkflowStepStatus.FAILED);
        completedRow.setWorkerJobId("worker-456");
        completedRow.setErrorReason("CLAUDE_AGENT_ERROR");
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(eq(JOB_RUN_ID), anyString()))
                .thenReturn(Optional.empty(), Optional.of(completedRow));

        StepResult result = executor.execute(context(baseStepDef(), "cloud-run"));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).isEqualTo("CLAUDE_AGENT_ERROR");
        assertThat(result.getWorkerJobId()).isEqualTo("worker-456");
    }

    @Test
    void execute_timesOutAndCancelsExecution() {
        stubHappyCredentials();
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class))).thenReturn("exec-timeout");
        when(launcher.pollExecution(any(CloudRunTarget.class), eq("exec-timeout")))
                .thenReturn(CloudRunJobLauncher.ExecutionState.running());
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(eq(JOB_RUN_ID), anyString()))
                .thenReturn(Optional.empty());

        Map<String, Object> stepDef = baseStepDef();
        stepDef.put("timeout_minutes", 1);

        StepResult result = executor.execute(context(stepDef, "cloud-run"));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).isEqualTo("CLAUDE_TIMEOUT");
        verify(launcher).cancelExecution(any(CloudRunTarget.class), eq("exec-timeout"));
        // 1 minute / 10s poll interval = 6 polls before giving up.
        verify(launcher, times(6)).pollExecution(any(CloudRunTarget.class), eq("exec-timeout"));
    }

    @Test
    void execute_resumesWithoutRelaunchingWhenRowAlreadyTerminal() {
        when(credentialService.resolveApiKey(PROJECT_ID, "claude-code")).thenReturn(Optional.of("cc-oauth-xyz"));

        WorkflowStepRun priorRow = new WorkflowStepRun();
        priorRow.setStatus(WorkflowStepStatus.SUCCESS);
        priorRow.setWorkerJobId("worker-existing");
        priorRow.setOutputJson("{\"summary\":\"done already\"}");
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString()))
                .thenReturn(Optional.of(priorRow));
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(JOB_RUN_ID, "worker-existing"))
                .thenReturn(Optional.of(priorRow));

        StepResult result = executor.execute(context(baseStepDef(), "cloud-run"));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        assertThat(result.getWorkerJobId()).isEqualTo("worker-existing");
        assertThat(result.getOutputs().get("summary")).isEqualTo("done already");
        verifyNoInteractions(launcher);
        verify(stepRunRepository, never()).save(any());
    }

    @Test
    void execute_persistsExecutionNameOnStepRunAfterLaunch() {
        stubHappyCredentials();
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class))).thenReturn("exec-777");
        when(launcher.pollExecution(any(CloudRunTarget.class), eq("exec-777")))
                .thenReturn(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.SUCCEEDED, Optional.empty()));
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(eq(JOB_RUN_ID), anyString()))
                .thenReturn(Optional.empty());

        executor.execute(context(baseStepDef(), "cloud-run"));

        ArgumentCaptor<WorkflowStepRun> captor = ArgumentCaptor.forClass(WorkflowStepRun.class);
        verify(stepRunRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues())
                .anySatisfy(row -> assertThat(row.getExecutionName()).isEqualTo("exec-777"));
    }

    @Test
    void execute_appendsLauncherLinesToStepRunLogBeforePollingBegins() {
        stubHappyCredentials();
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class))).thenReturn("exec-1");
        when(launcher.pollExecution(any(CloudRunTarget.class), eq("exec-1")))
                .thenReturn(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.SUCCEEDED, Optional.empty()));
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(eq(JOB_RUN_ID), anyString()))
                .thenReturn(Optional.empty());

        executor.execute(context(baseStepDef(), "cloud-run"));

        // The "Launching" line (and the post-launch "execution:" line) must reach the row via the
        // broker BEFORE any poll is attempted — proving the UI sees launcher progress without
        // waiting for the step to reach a terminal state, regardless of how long polling takes.
        InOrder inOrder = inOrder(logBroker, launcher);
        inOrder.verify(logBroker).appendToStepLog(any(WorkflowStepRun.class),
                argThat(lines -> lines.size() == 1 && lines.get(0).contains("Launching")), eq(PROJECT_ID));
        inOrder.verify(logBroker).appendToStepLog(any(WorkflowStepRun.class),
                argThat(lines -> lines.size() == 1 && lines.get(0).contains("execution: exec-1")), eq(PROJECT_ID));
        inOrder.verify(launcher).pollExecution(any(CloudRunTarget.class), eq("exec-1"));
    }

    @Test
    void execute_resumesPollingWithStoredExecutionNameWithoutRelaunching() {
        when(credentialService.resolveApiKey(PROJECT_ID, "claude-code")).thenReturn(Optional.of("cc-oauth-xyz"));

        WorkflowStepRun inFlightRow = new WorkflowStepRun();
        inFlightRow.setStatus(WorkflowStepStatus.RUNNING);
        inFlightRow.setWorkerJobId("worker-inflight");
        inFlightRow.setExecutionName("exec-inflight");
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString()))
                .thenReturn(Optional.of(inFlightRow));
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(JOB_RUN_ID, "worker-inflight"))
                .thenReturn(Optional.of(inFlightRow));
        when(launcher.pollExecution(any(CloudRunTarget.class), eq("exec-inflight")))
                .thenReturn(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.SUCCEEDED, Optional.empty()));

        StepResult result = executor.execute(context(baseStepDef(), "cloud-run"));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        assertThat(result.getWorkerJobId()).isEqualTo("worker-inflight");
        verify(launcher, never()).startExecution(any(), any());
        verify(launcher).pollExecution(any(CloudRunTarget.class), eq("exec-inflight"));
        verify(stepRunRepository, never()).save(any());
    }
}
