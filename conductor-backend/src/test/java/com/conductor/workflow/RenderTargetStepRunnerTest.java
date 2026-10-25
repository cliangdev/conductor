package com.conductor.workflow;

import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.entity.WorkflowStepStatus;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The render step on a BYO Cloud Run target; the launch/resume/cancel mechanics are CloudRunStepRunner's. */
@ExtendWith(MockitoExtension.class)
class RenderTargetStepRunnerTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String JOB_RUN_ID = "jobrun-1";
    private static final CloudRunTarget TARGET =
            new CloudRunTarget("customer-proj", "us-east1", "conductor-acme-render", "conn-1");

    @Mock private CloudRunJobLauncher launcher;
    @Mock private RuntimeTargetResolver resolver;
    @Mock private RunTokenService runTokenService;
    @Mock private ProjectSettingsRepository projectSettingsRepository;
    @Mock private WorkflowStepRunRepository stepRunRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowRunLogBroker logBroker;

    private RenderTargetStepRunner runner;

    @BeforeEach
    void setUp() {
        CloudRunStepRunner shared = new CloudRunStepRunner(launcher, stepRunRepository, runRepository, logBroker,
                new ObjectMapper()) {
            @Override
            protected void sleepSeconds(int seconds) {
                // no-op for fast tests
            }
        };
        runner = new RenderTargetStepRunner(shared, resolver, runTokenService, projectSettingsRepository,
                stepRunRepository, new WorkflowInterpolator(), "https://api.example.test");
    }

    private void targetReady() {
        when(resolver.resolveRenderTarget(PROJECT_ID)).thenReturn(new RuntimeTargetResolver.RenderTargetResolution(
                TARGET, "us-east1-docker.pkg.dev/customer-proj/repo/render@sha256:abc", "acme-run", null));
    }

    private void resumeTargetReady() {
        when(resolver.resolveRenderTargetForResume(PROJECT_ID, "acme-run")).thenReturn(
                new RuntimeTargetResolver.RenderTargetResolution(TARGET, "img", "acme-run", null));
    }

    private WorkflowStepRun inFlightRow() {
        WorkflowStepRun inFlight = new WorkflowStepRun();
        inFlight.setStatus(WorkflowStepStatus.RUNNING);
        inFlight.setWorkerJobId("worker-inflight");
        inFlight.setExecutionName("exec-inflight");
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.of(inFlight));
        org.mockito.Mockito.lenient().when(stepRunRepository.findByJobRunIdAndWorkerJobId(JOB_RUN_ID, "worker-inflight"))
                .thenReturn(Optional.of(inFlight));
        return inFlight;
    }

    private void tokensAndSteprow() {
        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("run-token");
        when(runTokenService.generateMcpToken(anyString(), anyString(), anyInt())).thenReturn("mcp-key");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
    }

    private Map<String, Object> stepDef() {
        Map<String, Object> stepDef = new LinkedHashMap<>();
        stepDef.put("id", "render");
        stepDef.put("run", "conductor creative render \"$CREATIVE_ID\" --workflow-run-id \"$CONDUCTOR_WORKFLOW_RUN_ID\"");
        stepDef.put("env", new LinkedHashMap<>(Map.of("CREATIVE_ID", "${{ event.creativeId }}")));
        stepDef.put("with", new LinkedHashMap<>(Map.of("conductor_mcp", true)));
        return stepDef;
    }

    private StepExecutionContext context(Map<String, Object> stepDef) {
        WorkflowRun run = new WorkflowRun();
        run.setId("run-123");
        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setId(JOB_RUN_ID);
        jobRun.setJobId("render-target");
        RuntimeContext ctx = new RuntimeContext(
                Map.of("creativeId", "cr-7", "runtime", "target", "targetName", "acme-run"), Map.of(), Map.of(), Map.of());
        return new StepExecutionContext(run, jobRun, stepDef, ctx, PROJECT_ID, "cloud-run");
    }

    private void launchesAndFinishesWith(CloudRunJobLauncher.ExecutionState state) {
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class)))
                .thenReturn(CloudRunJobLauncher.LaunchResult.confirmed("op-1", "exec-1"));
        when(launcher.pollExecution(any(CloudRunTarget.class), eq("exec-1"))).thenReturn(state);
    }

    // ---- target resolution ----

    @Test
    void targetUnavailable_failsWithRenderTargetUnavailableNamingTheTarget_noLaunch_noBuiltinFallback() {
        when(resolver.resolveRenderTarget(PROJECT_ID)).thenReturn(new RuntimeTargetResolver.RenderTargetResolution(
                null, null, "acme-run", "runtime target 'acme-run' is not set up for rendering: image not found"));

        StepResult result = runner.run(context(stepDef()));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).startsWith("RENDER_TARGET_UNAVAILABLE").contains("acme-run");
        verifyNoInteractions(launcher);
        verify(resolver, never()).resolve(anyString(), anyString());
    }

    @Test
    void targetDeleted_afterDispatch_stillNamesTheTargetFromTheDispatchPayload() {
        when(resolver.resolveRenderTarget(PROJECT_ID)).thenReturn(new RuntimeTargetResolver.RenderTargetResolution(
                null, null, null, "its designated runtime target no longer exists"));

        StepResult result = runner.run(context(stepDef()));

        assertThat(result.getErrorReason()).startsWith("RENDER_TARGET_UNAVAILABLE")
                .contains("acme-run").contains("no longer exists");
        verifyNoInteractions(launcher);
    }

    // ---- launch ----

    @Test
    void launchesTheTargetsRenderJobWithTheProtocolEnvAndTheStepScript() {
        targetReady();
        tokensAndSteprow();
        launchesAndFinishesWith(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.SUCCEEDED, Optional.empty()));

        StepResult result = runner.run(context(stepDef()));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        ArgumentCaptor<ContainerTask> task = ArgumentCaptor.forClass(ContainerTask.class);
        ArgumentCaptor<CloudRunTarget> target = ArgumentCaptor.forClass(CloudRunTarget.class);
        verify(launcher).startExecution(target.capture(), task.capture());
        assertThat(target.getValue()).isEqualTo(TARGET);
        assertThat(task.getValue().command()).containsExactly("conductor-step-entrypoint");
        // The Cloud Run execution timeout is the step budget + 3 minutes of grace; the entrypoint's own
        // timer (CONDUCTOR_TIMEOUT_MINUTES) stays at the step budget so it fires first and self-reports.
        assertThat(task.getValue().timeoutMinutes()).isEqualTo(33);
        Map<String, String> env = task.getValue().env();
        assertThat(env).containsEntry("CONDUCTOR_API_URL", "https://api.example.test")
                .containsEntry("CONDUCTOR_PROJECT_ID", PROJECT_ID)
                .containsEntry("CONDUCTOR_WORKFLOW_RUN_ID", "run-123")
                .containsEntry("CONDUCTOR_JOB_ID", "render-target")
                .containsEntry("CONDUCTOR_RUN_TOKEN", "run-token")
                .containsEntry("CONDUCTOR_API_KEY", "mcp-key")
                .containsEntry("CONDUCTOR_TIMEOUT_MINUTES", "30")
                .containsEntry("CONDUCTOR_MCP_ENABLED", "true")
                .containsEntry("CREATIVE_ID", "cr-7")
                .containsEntry("CONDUCTOR_LOG_CHUNK_URL",
                        "https://api.example.test/internal/v1/workflow-runs/run-123/log-chunk");
        assertThat(env.get("CONDUCTOR_WORKER_JOB_ID")).isNotBlank();
        assertThat(env.get("CONDUCTOR_STEP_COMPLETE_URL")).isEqualTo("https://api.example.test/internal/v1/workflow-runs/run-123/steps/"
                + env.get("CONDUCTOR_WORKER_JOB_ID") + "/complete");
        assertThat(env.get("CONDUCTOR_STEP_RUN")).startsWith("conductor creative render");
        verify(runTokenService).generateMcpToken("run-123", PROJECT_ID, 24);
    }

    @Test
    void stepEnvCollidingWithAReservedKey_isRejectedBeforeLaunch() {
        targetReady();
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("run-token");
        org.mockito.Mockito.lenient().when(runTokenService.generateMcpToken(anyString(), anyString(), anyInt())).thenReturn("mcp-key");
        Map<String, Object> stepDef = stepDef();
        stepDef.put("env", Map.of("CONDUCTOR_RUN_TOKEN", "evil"));

        StepResult result = runner.run(context(stepDef));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).startsWith("STEP_CONFIG_ERROR");
        verify(launcher, never()).startExecution(any(), any());
    }

    @Test
    void stepTimeoutIsCappedAtTheJobsTaskTimeout() {
        targetReady();
        tokensAndSteprow();
        launchesAndFinishesWith(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.SUCCEEDED, Optional.empty()));
        Map<String, Object> stepDef = stepDef();
        stepDef.put("timeout_minutes", 120);

        runner.run(context(stepDef));

        ArgumentCaptor<ContainerTask> task = ArgumentCaptor.forClass(ContainerTask.class);
        verify(launcher).startExecution(any(CloudRunTarget.class), task.capture());
        assertThat(task.getValue().timeoutMinutes()).isEqualTo(38);
        assertThat(task.getValue().env()).containsEntry("CONDUCTOR_TIMEOUT_MINUTES", "35");
    }

    // ---- exit-code mapping (container never self-reported) ----

    private String failureReasonForExit(int exitCode) {
        targetReady();
        tokensAndSteprow();
        launchesAndFinishesWith(new CloudRunJobLauncher.ExecutionState(
                CloudRunJobLauncher.Status.FAILED, Optional.of(exitCode)));
        StepResult result = runner.run(context(stepDef()));
        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        return result.getErrorReason();
    }

    @Test
    void exit20_mapsToStepConfigError() {
        assertThat(failureReasonForExit(20)).isEqualTo("STEP_CONFIG_ERROR");
    }

    @Test
    void exit124_mapsToStepTimeout() {
        assertThat(failureReasonForExit(124)).isEqualTo("STEP_TIMEOUT");
    }

    @Test
    void exit127_isTheScriptsOwnCommandNotFound_notAnOutdatedImage() {
        assertThat(failureReasonForExit(127)).startsWith("STEP_FAILED (exit 127)")
                .contains("a command in the render step was not found")
                .doesNotContain("RENDER_IMAGE_OUTDATED");
    }

    @Test
    void containerFailedToStart_executableNotFound_mapsToRenderImageOutdated_withTheMirrorHint() {
        targetReady();
        tokensAndSteprow();
        launchesAndFinishesWith(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.FAILED,
                Optional.of(127), Optional.of("Container failed to start: exec: \"conductor-step-entrypoint\": "
                        + "executable file not found in $PATH")));

        StepResult result = runner.run(context(stepDef()));

        assertThat(result.getErrorReason()).startsWith("RENDER_IMAGE_OUTDATED")
                .contains("conductor-step-entrypoint").contains("mirror the current conductor-render image")
                .contains("re-sync the target");
    }

    @Test
    void launchRejectedWithExecutableNotFound_mapsToRenderImageOutdated() {
        targetReady();
        tokensAndSteprow();
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class)))
                .thenThrow(new IllegalStateException("exec: \"conductor-step-entrypoint\": executable file not found"));

        assertThat(runner.run(context(stepDef())).getErrorReason()).startsWith("RENDER_IMAGE_OUTDATED");
    }

    @Test
    void unrelatedExecutionFailureMessage_fallsBackToTheExitCodeMapping() {
        targetReady();
        tokensAndSteprow();
        launchesAndFinishesWith(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.FAILED,
                Optional.of(137), Optional.of("Memory limit of 4096 MiB exceeded")));

        assertThat(runner.run(context(stepDef())).getErrorReason()).isEqualTo("STEP_FAILED (exit 137)");
    }

    // ---- conductor_mcp ----

    @Test
    void conductorMcpFalse_mintsNoApiKey_andDisablesMcp() {
        targetReady();
        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("run-token");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
        launchesAndFinishesWith(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.SUCCEEDED, Optional.empty()));
        Map<String, Object> stepDef = stepDef();
        stepDef.put("with", Map.of("conductor_mcp", false));

        runner.run(context(stepDef));

        ArgumentCaptor<ContainerTask> task = ArgumentCaptor.forClass(ContainerTask.class);
        verify(launcher).startExecution(any(CloudRunTarget.class), task.capture());
        assertThat(task.getValue().env()).containsEntry("CONDUCTOR_MCP_ENABLED", "false")
                .doesNotContainKey("CONDUCTOR_API_KEY");
        verify(runTokenService, never()).generateMcpToken(anyString(), anyString(), anyInt());
    }

    @Test
    void conductorMcpAbsent_mintsNoApiKey() {
        targetReady();
        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("run-token");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
        launchesAndFinishesWith(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.SUCCEEDED, Optional.empty()));
        Map<String, Object> stepDef = stepDef();
        stepDef.remove("with");

        runner.run(context(stepDef));

        ArgumentCaptor<ContainerTask> task = ArgumentCaptor.forClass(ContainerTask.class);
        verify(launcher).startExecution(any(CloudRunTarget.class), task.capture());
        assertThat(task.getValue().env()).containsEntry("CONDUCTOR_MCP_ENABLED", "false")
                .doesNotContainKey("CONDUCTOR_API_KEY");
    }

    @Test
    void otherExit_mapsToStepFailedWithTheCode() {
        assertThat(failureReasonForExit(3)).isEqualTo("STEP_FAILED (exit 3)");
    }

    @Test
    void exitZeroWithNoSelfReport_isSuccess() {
        targetReady();
        tokensAndSteprow();
        launchesAndFinishesWith(new CloudRunJobLauncher.ExecutionState(
                CloudRunJobLauncher.Status.SUCCEEDED, Optional.of(0)));

        assertThat(runner.run(context(stepDef())).getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
    }

    @Test
    void containerSelfReportedReason_winsOverTheExitCodeMapping() {
        targetReady();
        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("run-token");
        when(runTokenService.generateMcpToken(anyString(), anyString(), anyInt())).thenReturn("mcp-key");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
        launchesAndFinishesWith(new CloudRunJobLauncher.ExecutionState(
                CloudRunJobLauncher.Status.FAILED, Optional.of(3)));
        WorkflowStepRun reported = new WorkflowStepRun();
        reported.setWorkerJobId("w");
        reported.setStatus(WorkflowStepStatus.FAILED);
        reported.setErrorReason("STEP_FAILED");
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(eq(JOB_RUN_ID), anyString()))
                .thenReturn(Optional.empty(), Optional.of(reported));

        StepResult result = runner.run(context(stepDef()));

        assertThat(result.getErrorReason()).isEqualTo("STEP_FAILED");
    }

    // ---- launch failure, reconciliation, resume, cancel, timeout (the shared mechanics) ----

    @Test
    void launchRejected_failsWithRenderLaunchError() {
        targetReady();
        tokensAndSteprow();
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class)))
                .thenThrow(new IllegalStateException("Cloud Run rejected the request"));

        StepResult result = runner.run(context(stepDef()));

        assertThat(result.getErrorReason()).isEqualTo("RENDER_LAUNCH_ERROR");
    }

    @Test
    void launchUnconfirmed_executionFoundLater_recoversAndCompletes() {
        targetReady();
        tokensAndSteprow();
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class)))
                .thenThrow(new CloudRunJobLauncher.LaunchUnconfirmedException("no ack"));
        when(launcher.findExecutionByWorkerJobId(any(CloudRunTarget.class), anyString()))
                .thenReturn(CloudRunJobLauncher.ExecutionSearch.notFound(),
                        CloudRunJobLauncher.ExecutionSearch.found("exec-late"));
        when(launcher.pollExecution(any(CloudRunTarget.class), eq("exec-late")))
                .thenReturn(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.SUCCEEDED, Optional.empty()));

        StepResult result = runner.run(context(stepDef()));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        verify(launcher, times(2)).findExecutionByWorkerJobId(any(CloudRunTarget.class), anyString());
    }

    @Test
    void launchUnconfirmed_neverFound_failsWithUnconfirmed() {
        targetReady();
        tokensAndSteprow();
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class)))
                .thenThrow(new CloudRunJobLauncher.LaunchUnconfirmedException("no ack"));
        when(launcher.findExecutionByWorkerJobId(any(CloudRunTarget.class), anyString()))
                .thenReturn(CloudRunJobLauncher.ExecutionSearch.notFound());

        StepResult result = runner.run(context(stepDef()));

        assertThat(result.getErrorReason()).isEqualTo("CLOUD_RUN_LAUNCH_UNCONFIRMED");
        verify(launcher, times(12)).findExecutionByWorkerJobId(any(CloudRunTarget.class), anyString());
    }

    @Test
    void backendRestart_resumesPollingTheStoredExecution_withoutRelaunching() {
        resumeTargetReady();
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        WorkflowStepRun inFlight = new WorkflowStepRun();
        inFlight.setStatus(WorkflowStepStatus.RUNNING);
        inFlight.setWorkerJobId("worker-inflight");
        inFlight.setExecutionName("exec-inflight");
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.of(inFlight));
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(JOB_RUN_ID, "worker-inflight")).thenReturn(Optional.of(inFlight));
        when(launcher.pollExecution(any(CloudRunTarget.class), eq("exec-inflight")))
                .thenReturn(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.SUCCEEDED, Optional.empty()));

        StepResult result = runner.run(context(stepDef()));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        assertThat(result.getWorkerJobId()).isEqualTo("worker-inflight");
        verify(launcher, never()).startExecution(any(), any());
    }

    @Test
    void backendRestart_withOnlyAnOperationName_resolvesTheExecutionWithoutRelaunching() {
        resumeTargetReady();
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        WorkflowStepRun inFlight = new WorkflowStepRun();
        inFlight.setStatus(WorkflowStepStatus.RUNNING);
        inFlight.setWorkerJobId("worker-op");
        inFlight.setOperationName("op-inflight");
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.of(inFlight));
        when(stepRunRepository.findByJobRunIdAndWorkerJobId(JOB_RUN_ID, "worker-op")).thenReturn(Optional.of(inFlight));
        when(launcher.tryResolveExecutionName(any(CloudRunTarget.class), eq("op-inflight")))
                .thenReturn(Optional.of("exec-resolved"));
        when(launcher.pollExecution(any(CloudRunTarget.class), eq("exec-resolved")))
                .thenReturn(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.SUCCEEDED, Optional.empty()));

        StepResult result = runner.run(context(stepDef()));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        verify(launcher, never()).startExecution(any(), any());
    }

    @Test
    void runCancelling_cancelsTheExecution() {
        targetReady();
        tokensAndSteprow();
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class)))
                .thenReturn(CloudRunJobLauncher.LaunchResult.confirmed("op-1", "exec-1"));
        when(runRepository.findStatusById("run-123")).thenReturn(Optional.of(WorkflowRunStatus.CANCELLING));

        StepResult result = runner.run(context(stepDef()));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.CANCELLED);
        verify(launcher).cancelExecution(any(CloudRunTarget.class), eq("exec-1"));
    }

    @Test
    void stepOutlastingItsTimeout_isCancelledAndFailsWithStepTimeout() {
        targetReady();
        tokensAndSteprow();
        when(launcher.startExecution(any(CloudRunTarget.class), any(ContainerTask.class)))
                .thenReturn(CloudRunJobLauncher.LaunchResult.confirmed("op-1", "exec-1"));
        when(launcher.pollExecution(any(CloudRunTarget.class), eq("exec-1")))
                .thenReturn(CloudRunJobLauncher.ExecutionState.running());
        Map<String, Object> stepDef = stepDef();
        stepDef.put("timeout_minutes", 1);

        StepResult result = runner.run(context(stepDef));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).isEqualTo("STEP_TIMEOUT");
        verify(launcher).cancelExecution(any(CloudRunTarget.class), eq("exec-1"));
        verify(launcher, times(24)).pollExecution(any(CloudRunTarget.class), eq("exec-1"));
    }

    // ---- resume ignores the "available for rendering" gate ----

    @Test
    void resume_whileTargetIsNotReadyForRendering_stillReattachesToTheExecution() {
        // The resolver's resume lookup does not apply status / render readiness / designation; the gate
        // (resolveRenderTarget) must not be consulted at all.
        resumeTargetReady();
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        inFlightRow();
        when(launcher.pollExecution(any(CloudRunTarget.class), eq("exec-inflight")))
                .thenReturn(new CloudRunJobLauncher.ExecutionState(CloudRunJobLauncher.Status.SUCCEEDED, Optional.empty()));

        StepResult result = runner.run(context(stepDef()));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        verify(resolver, never()).resolveRenderTarget(anyString());
        verify(launcher, never()).startExecution(any(), any());
    }

    @Test
    void resume_targetDeleted_failsClearly_namingTheUntrackedExecution_withoutClaimingItStopped() {
        when(resolver.resolveRenderTargetForResume(PROJECT_ID, "acme-run")).thenReturn(
                new RuntimeTargetResolver.RenderTargetResolution(null, null, "acme-run",
                        "the runtime target no longer exists"));
        inFlightRow();

        StepResult result = runner.run(context(stepDef()));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).startsWith("RENDER_TARGET_UNAVAILABLE")
                .contains("exec-inflight").contains("could not be tracked").doesNotContain("stopped");
        verifyNoInteractions(launcher);
    }

    @Test
    void firstRun_stillRequiresTheTargetToBeAvailableForRendering() {
        when(stepRunRepository.findByJobRunIdAndStepId(eq(JOB_RUN_ID), anyString())).thenReturn(Optional.empty());
        when(resolver.resolveRenderTarget(PROJECT_ID)).thenReturn(new RuntimeTargetResolver.RenderTargetResolution(
                null, null, "acme-run", "runtime target 'acme-run' is not set up for rendering"));

        assertThat(runner.run(context(stepDef())).getErrorReason()).startsWith("RENDER_TARGET_UNAVAILABLE");
        verify(resolver, never()).resolveRenderTargetForResume(anyString(), any());
    }
}
