package com.conductor.workflow;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.generated.model.JobDispatchPayloadDto;
import com.conductor.repository.ProjectSettingsRepository;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Plain-Mockito unit test for {@link JobDispatchPayloadService#resolveJobImage} precedence:
 * {@code container.image} > {@code docker://} step > single-sourced default for a claude-code step >
 * null (self-hosted daemon falls back to its own {@code DEFAULT_RUNNER_IMAGE} constant).
 */
@ExtendWith(MockitoExtension.class)
class JobDispatchPayloadServiceTest {

    @Mock private WorkflowRunRepository runRepository;
    @Mock private WorkflowJobRunRepository jobRunRepository;
    @Mock private WorkflowStepRunRepository stepRunRepository;
    @Mock private RuntimeContextBuilder contextBuilder;
    @Mock private RunTokenService runTokenService;
    @Mock private ProjectSettingsRepository projectSettingsRepository;
    @Mock private UpstreamOutputsResolver upstreamOutputsResolver;

    private JobDispatchPayloadService service;

    @BeforeEach
    void setUp() {
        service = new JobDispatchPayloadService(runRepository, jobRunRepository, stepRunRepository,
                contextBuilder, new WorkflowInterpolator(), runTokenService, projectSettingsRepository,
                new ObjectMapper(), upstreamOutputsResolver, "http://localhost:8080");
    }

    private WorkflowRun runWithYaml(String yaml) {
        Project project = new Project();
        project.setId("proj-1");

        WorkflowDefinition workflow = new WorkflowDefinition();
        workflow.setId("wf-1");
        workflow.setProject(project);
        workflow.setYaml(yaml);

        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        run.setWorkflow(workflow);
        return run;
    }

    private WorkflowJobRun awaitingPickupJobRun(String jobId) {
        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setId("jobrun-1");
        jobRun.setJobId(jobId);
        jobRun.setStatus(WorkflowJobStatus.AWAITING_PICKUP);
        return jobRun;
    }

    private void stubCommonCollaborators(WorkflowRun run, WorkflowJobRun jobRun, String jobId) {
        when(runRepository.findByIdWithWorkflow("run-1")).thenReturn(Optional.of(run));
        when(jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc("run-1", jobId))
                .thenReturn(List.of(jobRun));
        when(contextBuilder.loadSecrets(anyString())).thenReturn(Map.of());
        when(upstreamOutputsResolver.collectUpstreamOutputs(any(), any(), anyString())).thenReturn(Map.of());
        when(contextBuilder.build(any(), any(), any(), any(), anyInt()))
                .thenReturn(new RuntimeContext(Map.of(), Map.of(), Map.of(), Map.of()));
        when(runTokenService.generateRunToken(anyString(), anyInt())).thenReturn("run-token");
        when(projectSettingsRepository.findByProjectId(anyString())).thenReturn(Optional.empty());
    }

    @Test
    void buildPayload_claudeCodeStepResolvesDefaultRunnerImage() {
        String yaml = """
                jobs:
                  analyze:
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "Analyze"
                """;
        WorkflowRun run = runWithYaml(yaml);
        WorkflowJobRun jobRun = awaitingPickupJobRun("analyze");
        stubCommonCollaborators(run, jobRun, "analyze");

        JobDispatchPayloadDto dto = service.buildPayload("run-1", "analyze");

        assertThat(dto.getImage()).isEqualTo(RunnerImage.DEFAULT);
    }

    @Test
    void buildPayload_dockerUsesPrefixTakesPrecedenceOverClaudeCodeDefault() {
        String yaml = """
                jobs:
                  analyze:
                    steps:
                      - id: build
                        uses: "docker://ghcr.io/example/custom:1"
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "Analyze"
                """;
        WorkflowRun run = runWithYaml(yaml);
        WorkflowJobRun jobRun = awaitingPickupJobRun("analyze");
        stubCommonCollaborators(run, jobRun, "analyze");

        JobDispatchPayloadDto dto = service.buildPayload("run-1", "analyze");

        assertThat(dto.getImage()).isEqualTo("ghcr.io/example/custom:1");
    }

    @Test
    void buildPayload_containerImageTakesPrecedenceOverEverything() {
        String yaml = """
                jobs:
                  analyze:
                    container:
                      image: "ghcr.io/example/job-image:2"
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: "Analyze"
                """;
        WorkflowRun run = runWithYaml(yaml);
        WorkflowJobRun jobRun = awaitingPickupJobRun("analyze");
        stubCommonCollaborators(run, jobRun, "analyze");

        JobDispatchPayloadDto dto = service.buildPayload("run-1", "analyze");

        assertThat(dto.getImage()).isEqualTo("ghcr.io/example/job-image:2");
    }

    @Test
    void buildPayload_noDockerOrClaudeCodeStepsReturnsNullImage() {
        String yaml = """
                jobs:
                  analyze:
                    steps:
                      - id: notify
                        type: http
                """;
        WorkflowRun run = runWithYaml(yaml);
        WorkflowJobRun jobRun = awaitingPickupJobRun("analyze");
        stubCommonCollaborators(run, jobRun, "analyze");

        JobDispatchPayloadDto dto = service.buildPayload("run-1", "analyze");

        assertThat(dto.getImage()).isNull();
    }
}
