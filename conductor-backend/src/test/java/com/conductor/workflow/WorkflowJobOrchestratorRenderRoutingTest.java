package com.conductor.workflow;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowJobQueueRepository;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.service.LogRedactionService;
import com.conductor.service.WorkflowSecretsService;
import com.conductor.workflow.model.WorkflowYamlParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The system render workflow ({@code creative/creative-render.yaml}) has two jobs gated on
 * {@code event.runtime}: exactly one runs, the other is SKIPPED, and the run still settles SUCCESS.
 * Uses the real YAML, the real interpolator and condition evaluator.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowJobOrchestratorRenderRoutingTest {

    private static final String RUN_ID = "run-1";

    @Mock WorkflowJobRunRepository jobRunRepository;
    @Mock WorkflowStepRunRepository stepRunRepository;
    @Mock WorkflowRunRepository runRepository;
    @Mock WorkflowDefinitionRepository workflowRepository;
    @Mock WorkflowExecutionEngine engine;
    @Mock WorkflowSecretsService secretsService;
    @Mock LogRedactionService logRedactionService;
    @Mock SelfHostedJobDispatcher selfHostedJobDispatcher;
    @Mock UpstreamOutputsResolver upstreamOutputsResolver;
    @Mock com.conductor.service.WorkflowArtifactService artifactService;
    @Mock WorkflowJobQueueRepository queueRepository;
    @Mock WorkflowFailureCircuitBreaker circuitBreaker;
    @Mock WorkflowRunFailureNotifier failureNotifier;
    @Mock WorkflowJobDispatcher dispatcher;

    private WorkflowJobOrchestrator orchestrator;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<WorkflowJobRun> rows = new ArrayList<>();
    private String yaml;

    @BeforeEach
    void setUp() throws IOException {
        yaml = new String(getClass().getResourceAsStream("/creative/creative-render.yaml").readAllBytes(),
                StandardCharsets.UTF_8);
        RuntimeContextBuilder contextBuilder = new RuntimeContextBuilder(
                secretsService, stepRunRepository, jobRunRepository, objectMapper, artifactService);
        orchestrator = new WorkflowJobOrchestrator(
                jobRunRepository, stepRunRepository, runRepository, workflowRepository,
                engine, new ConditionEvaluator(), new WorkflowInterpolator(), contextBuilder,
                logRedactionService, List.of(), objectMapper, selfHostedJobDispatcher,
                upstreamOutputsResolver, new WorkflowYamlParser());
        when(secretsService.resolveSecrets(any())).thenReturn(Map.of());
        when(stepRunRepository.findByJobRunId(any())).thenReturn(List.of());
        when(logRedactionService.redact(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc(eq(RUN_ID), any())).thenAnswer(inv -> {
            String jobId = inv.getArgument(1);
            List<WorkflowJobRun> sorted = new ArrayList<>(rows.stream().filter(r -> jobId.equals(r.getJobId())).toList());
            sorted.sort(Comparator.comparingInt(WorkflowJobRun::getIteration).reversed());
            return sorted;
        });
        when(jobRunRepository.save(any())).thenAnswer(inv -> {
            WorkflowJobRun jr = inv.getArgument(0);
            if (!rows.contains(jr)) {
                rows.add(jr);
            }
            return jr;
        });
    }

    private WorkflowRun runWithRuntime(String runtime) {
        Project project = new Project();
        project.setId("proj-1");
        WorkflowDefinition def = new WorkflowDefinition();
        def.setYaml(yaml);
        def.setProject(project);
        WorkflowRun run = new WorkflowRun();
        run.setId(RUN_ID);
        run.setStatus(WorkflowRunStatus.PENDING);
        run.setWorkflow(def);
        run.setEventPayload("{\"type\":\"creative.render\",\"creativeId\":\"cr-1\",\"runtime\":"
                + (runtime == null ? "null" : "\"" + runtime + "\"") + "}");
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(runRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));
        return run;
    }

    private WorkflowJobStatus statusOf(String jobId) {
        return rows.stream().filter(r -> jobId.equals(r.getJobId())).findFirst().orElseThrow().getStatus();
    }

    @Test
    void theYamlDeclaresTheTwoJobsWithTheSplitIfForm() {
        var spec = new WorkflowYamlParser().parse(yaml);

        assertThat(spec.jobs()).containsOnlyKeys("render", "render-target");
        assertThat(spec.jobs().get("render").runsOn()).isEqualTo("self-hosted");
        assertThat(spec.jobs().get("render").ifCondition()).isEqualTo("${{ event.runtime }} == 'self-hosted'");
        assertThat(spec.jobs().get("render-target").runsOn()).isEqualTo("cloud-run");
        assertThat(spec.jobs().get("render-target").ifCondition()).isEqualTo("${{ event.runtime }} == 'target'");
    }

    @Test
    void targetRuntime_runsRenderTarget_andSkipsTheDaemonJob() {
        runWithRuntime("target");

        WorkflowJobOrchestrator.JobExecutionPlan daemon = orchestrator.planJobExecution(RUN_ID, "render");
        WorkflowJobOrchestrator.JobExecutionPlan target = orchestrator.planJobExecution(RUN_ID, "render-target");

        assertThat(daemon.done).isTrue();
        assertThat(statusOf("render")).isEqualTo(WorkflowJobStatus.SKIPPED);
        verify(selfHostedJobDispatcher, never()).dispatch(any(), any(), any(), any());
        assertThat(target.done).isFalse();
        assertThat(statusOf("render-target")).isEqualTo(WorkflowJobStatus.RUNNING);
    }

    @Test
    void selfHostedRuntime_dispatchesToTheDaemon_andSkipsRenderTarget() {
        WorkflowRun run = runWithRuntime("self-hosted");

        WorkflowJobOrchestrator.JobExecutionPlan target = orchestrator.planJobExecution(RUN_ID, "render-target");
        WorkflowJobOrchestrator.JobExecutionPlan daemon = orchestrator.planJobExecution(RUN_ID, "render");

        assertThat(target.done).isTrue();
        assertThat(statusOf("render-target")).isEqualTo(WorkflowJobStatus.SKIPPED);
        assertThat(daemon.done).isTrue(); // handed to the daemon
        verify(selfHostedJobDispatcher).dispatch(eq(run), eq("render"), any(), any());
        assertThat(statusOf("render")).isEqualTo(WorkflowJobStatus.AWAITING_PICKUP);
    }

    @Test
    void aPayloadWithoutRuntime_matchesNeitherGate() {
        WorkflowRun run = runWithRuntime(null);
        run.setEventPayload("{\"type\":\"creative.render\",\"creativeId\":\"cr-1\"}");

        // No event.runtime matches neither gate. The dispatcher always sets it, so the daemon job is
        // reached only by runtime == self-hosted.
        orchestrator.planJobExecution(RUN_ID, "render");
        orchestrator.planJobExecution(RUN_ID, "render-target");

        assertThat(statusOf("render")).isEqualTo(WorkflowJobStatus.SKIPPED);
        assertThat(statusOf("render-target")).isEqualTo(WorkflowJobStatus.SKIPPED);
    }

    @Test
    void aRunWithOneSkippedJobAndOneSuccessfulJobSettlesSuccess() {
        for (String ran : List.of("render", "render-target")) {
            rows.clear();
            WorkflowRun run = runWithRuntime("target");
            run.setStatus(WorkflowRunStatus.RUNNING);
            WorkflowExecutionEngine realEngine = new WorkflowExecutionEngine(queueRepository, runRepository,
                    jobRunRepository, stepRunRepository, workflowRepository, null, new WorkflowYamlParser(),
                    circuitBreaker, failureNotifier, dispatcher);
            rows.add(jobRun(run, ran, WorkflowJobStatus.SUCCESS));
            rows.add(jobRun(run, ran.equals("render") ? "render-target" : "render", WorkflowJobStatus.SKIPPED));
            when(jobRunRepository.findByRunId(RUN_ID)).thenReturn(rows);

            realEngine.checkRunCompletion(run);

            assertThat(run.getStatus()).as("ran " + ran).isEqualTo(WorkflowRunStatus.SUCCESS);
        }
    }

    private WorkflowJobRun jobRun(WorkflowRun run, String jobId, WorkflowJobStatus status) {
        WorkflowJobRun jr = new WorkflowJobRun();
        jr.setId("jr-" + jobId);
        jr.setRun(run);
        jr.setJobId(jobId);
        jr.setIteration(0);
        jr.setStatus(status);
        return jr;
    }
}
