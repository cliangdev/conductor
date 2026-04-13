package com.conductor.workflow;

import com.conductor.entity.*;
import com.conductor.repository.ProjectSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DockerStepExecutorTest {

    @Mock
    private WorkerVmClient workerVmClient;
    @Mock
    private RunTokenService runTokenService;
    @Mock
    private ProjectSettingsRepository projectSettingsRepository;

    private WorkflowInterpolator interpolator = new WorkflowInterpolator();

    private DockerStepExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new DockerStepExecutor(
                workerVmClient, runTokenService, projectSettingsRepository,
                interpolator, "http://localhost:8080") {
            @Override
            protected void sleepSeconds(int seconds) {
                // no-op for fast tests
            }
        };
    }

    private StepExecutionContext buildContext(Map<String, Object> stepDef, RuntimeContext ctx) {
        WorkflowRun run = new WorkflowRun();
        run.setId("run-123");

        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setJobId("job-1");

        return new StepExecutionContext(run, jobRun, stepDef, ctx, "project-abc");
    }

    @Test
    void execute_usesDockerImageFromUsesField() {
        Map<String, Object> stepDef = new HashMap<>();
        stepDef.put("uses", "docker://my-image:latest");

        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("token-xyz");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(workerVmClient.submitJob(any())).thenReturn("worker-job-1");
        when(workerVmClient.getJobStatus("worker-job-1"))
                .thenReturn(new WorkerVmClient.WorkerJobStatus("SUCCESS", 0));

        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of());
        executor.execute(buildContext(stepDef, ctx));

        verify(workerVmClient).submitJob(argThat(req ->
                "my-image:latest".equals(req.image())));
    }

    @Test
    void execute_usesDefaultImageWhenNoUsesField() {
        Map<String, Object> stepDef = new HashMap<>();

        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("token");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(workerVmClient.submitJob(any())).thenReturn("worker-job-2");
        when(workerVmClient.getJobStatus("worker-job-2"))
                .thenReturn(new WorkerVmClient.WorkerJobStatus("SUCCESS", 0));

        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of());
        executor.execute(buildContext(stepDef, ctx));

        verify(workerVmClient).submitJob(argThat(req ->
                "ghcr.io/cliangdev/conductor-runner:2".equals(req.image())));
    }

    @Test
    void execute_interpolatesEnvValues() {
        Map<String, Object> stepDef = new HashMap<>();
        stepDef.put("uses", "docker://ubuntu:22.04");
        stepDef.put("env", Map.of(
                "ISSUE_ID", "${{ event.issueId }}",
                "STATIC_VAL", "hello"
        ));

        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("token");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(workerVmClient.submitJob(any())).thenReturn("worker-job-3");
        when(workerVmClient.getJobStatus("worker-job-3"))
                .thenReturn(new WorkerVmClient.WorkerJobStatus("SUCCESS", 0));

        RuntimeContext ctx = new RuntimeContext(Map.of("issueId", "issue-999"), Map.of(), Map.of(), Map.of());
        executor.execute(buildContext(stepDef, ctx));

        verify(workerVmClient).submitJob(argThat(req ->
                "issue-999".equals(req.env().get("ISSUE_ID")) &&
                "hello".equals(req.env().get("STATIC_VAL"))));
    }

    @Test
    void execute_returnsSuccessWhenWorkerSucceeds() {
        Map<String, Object> stepDef = new HashMap<>();
        stepDef.put("uses", "docker://ubuntu:22.04");

        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("token");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(workerVmClient.submitJob(any())).thenReturn("worker-job-4");
        when(workerVmClient.getJobStatus("worker-job-4"))
                .thenReturn(new WorkerVmClient.WorkerJobStatus("SUCCESS", 0));

        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of());
        StepResult result = executor.execute(buildContext(stepDef, ctx));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        assertThat(result.getErrorReason()).isNull();
    }

    @Test
    void execute_returnsFailedWhenWorkerFails() {
        Map<String, Object> stepDef = new HashMap<>();
        stepDef.put("uses", "docker://ubuntu:22.04");

        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("token");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(workerVmClient.submitJob(any())).thenReturn("worker-job-5");
        when(workerVmClient.getJobStatus("worker-job-5"))
                .thenReturn(new WorkerVmClient.WorkerJobStatus("FAILED", 1));

        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of());
        StepResult result = executor.execute(buildContext(stepDef, ctx));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).contains("Exit code: 1");
    }

    @Test
    void execute_returnsFailedWhenWorkerUnavailable() {
        Map<String, Object> stepDef = new HashMap<>();
        stepDef.put("uses", "docker://ubuntu:22.04");

        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("token");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(workerVmClient.submitJob(any())).thenThrow(
                new WorkerVmClient.WorkerUnavailableException("unavailable", null));

        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of());
        StepResult result = executor.execute(buildContext(stepDef, ctx));

        assertThat(result.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.getErrorReason()).contains("Worker unavailable");
    }

    @Test
    void execute_usesProjectSettingsTtlHours() {
        ProjectSettings settings = new ProjectSettings();
        settings.setRunTokenTtlHours(6);

        Map<String, Object> stepDef = new HashMap<>();
        stepDef.put("uses", "docker://ubuntu:22.04");

        when(projectSettingsRepository.findByProjectId("project-abc")).thenReturn(Optional.of(settings));
        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("token");
        when(workerVmClient.submitJob(any())).thenReturn("worker-job-6");
        when(workerVmClient.getJobStatus("worker-job-6"))
                .thenReturn(new WorkerVmClient.WorkerJobStatus("SUCCESS", 0));

        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of());
        executor.execute(buildContext(stepDef, ctx));

        verify(runTokenService).generateRunToken(eq("run-123"), eq(6));
    }

    @Test
    void execute_buildsCallbackUrlsFromBackendBaseUrl() {
        Map<String, Object> stepDef = new HashMap<>();
        stepDef.put("uses", "docker://ubuntu:22.04");

        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("token");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
        when(workerVmClient.submitJob(any())).thenReturn("worker-job-7");
        when(workerVmClient.getJobStatus("worker-job-7"))
                .thenReturn(new WorkerVmClient.WorkerJobStatus("SUCCESS", 0));

        RuntimeContext ctx = new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of());
        executor.execute(buildContext(stepDef, ctx));

        verify(workerVmClient).submitJob(argThat(req ->
                req.logCallbackUrl().equals("http://localhost:8080/internal/workflow-runs/run-123/log-chunk") &&
                req.outputsCallbackUrl().equals("http://localhost:8080/internal/workflow-runs/run-123/outputs") &&
                req.jobFailedCallbackUrl().equals("http://localhost:8080/internal/workflow-runs/run-123/job-failed")));
    }

    @Test
    void getStepType_returnsDocker() {
        assertThat(executor.getStepType()).isEqualTo("docker");
    }
}
