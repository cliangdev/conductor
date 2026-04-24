package com.conductor.workflow;

import com.conductor.entity.*;
import com.conductor.repository.*;
import com.conductor.service.WorkflowSecretsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowLoopOrchestratorTest {

    @Mock WorkflowJobRunRepository jobRunRepository;
    @Mock WorkflowStepRunRepository stepRunRepository;
    @Mock WorkflowRunRepository runRepository;
    @Mock WorkflowDefinitionRepository workflowRepository;
    @Mock WorkflowExecutionEngine engine;
    @Mock WorkflowSecretsService secretsService;
    @Mock LogRedactionService logRedactionService;

    WorkflowJobOrchestrator orchestrator;
    ConditionEvaluator conditionEvaluator = new ConditionEvaluator();
    WorkflowInterpolator interpolator = new WorkflowInterpolator();
    ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RuntimeContextBuilder contextBuilder = new RuntimeContextBuilder(
                secretsService, stepRunRepository, jobRunRepository, objectMapper);
        orchestrator = new WorkflowJobOrchestrator(
                jobRunRepository, stepRunRepository, runRepository, workflowRepository,
                engine, conditionEvaluator, interpolator, contextBuilder,
                logRedactionService, List.of(), objectMapper);
        // The production code uses @Lazy @Autowired self so @Transactional helper methods
        // go through the Spring proxy. In unit tests there is no Spring context, so point
        // self at the bare instance — @Transactional is a no-op without a tx manager anyway.
        ReflectionTestUtils.setField(orchestrator, "self", orchestrator);

        when(secretsService.resolveSecrets(any())).thenReturn(Map.of());
        when(stepRunRepository.findByJobRunId(any())).thenReturn(List.of());
        when(logRedactionService.redact(any(), any())).thenAnswer(inv -> inv.getArgument(1));
    }

    /** Mock the reloads that planJobExecution / finalizeJob now do by ID. */
    private void mockEntityReloads(WorkflowRun run, WorkflowJobRun jobRun) {
        when(runRepository.findById(run.getId())).thenReturn(Optional.of(run));
        when(jobRunRepository.findById(jobRun.getId())).thenReturn(Optional.of(jobRun));
    }

    private WorkflowRun makeRun(String workflowYaml) {
        com.conductor.entity.Project project = new com.conductor.entity.Project();
        project.setId("proj-1");

        WorkflowDefinition def = new WorkflowDefinition();
        def.setYaml(workflowYaml);
        def.setProject(project);

        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        run.setWorkflow(def);
        return run;
    }

    private WorkflowJobRun pendingJobRun(WorkflowRun run, String jobId, int iteration) {
        WorkflowJobRun jr = new WorkflowJobRun();
        jr.setId("jr-" + jobId + "-" + iteration);
        jr.setRun(run);
        jr.setJobId(jobId);
        jr.setIteration(iteration);
        jr.setStatus(WorkflowJobStatus.PENDING);
        return jr;
    }

    // ---- Loop tests ----

    @Test
    void loopUntilTrueOnFirstIterationMarksSuccessAndEnqueuesDownstream() {
        String yaml = """
                on:
                  push: {}
                jobs:
                  poll:
                    loop:
                      max_iterations: 5
                      until: "true"
                    steps: []
                """;
        WorkflowRun run = makeRun(yaml);
        WorkflowJobRun jobRun = pendingJobRun(run, "poll", 0);
        mockEntityReloads(run, jobRun);

        when(jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc("run-1", "poll"))
                .thenReturn(List.of(jobRun));
        when(jobRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        orchestrator.executeJob(run, "poll");

        assertThat(jobRun.getStatus()).isEqualTo(WorkflowJobStatus.SUCCESS);
        verify(engine, never()).enqueueJob(any(), eq("poll"));
    }

    @Test
    void loopExhaustedWithFailOnExhaustedDefaultMarksLoopExhausted() {
        String yaml = """
                on:
                  push: {}
                jobs:
                  poll:
                    loop:
                      max_iterations: 2
                      until: "false"
                    steps: []
                """;
        WorkflowRun run = makeRun(yaml);
        // Already at max iteration index (iteration=1, maxIterations=2, so index 0 and 1)
        WorkflowJobRun jobRun = pendingJobRun(run, "poll", 1);
        mockEntityReloads(run, jobRun);

        when(jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc("run-1", "poll"))
                .thenReturn(List.of(jobRun));
        when(jobRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        orchestrator.executeJob(run, "poll");

        assertThat(jobRun.getStatus()).isEqualTo(WorkflowJobStatus.LOOP_EXHAUSTED);
        verify(engine, never()).enqueueJob(any(), eq("poll"));
    }

    @Test
    void loopExhaustedWithFailOnExhaustedFalseMarksSuccess() {
        String yaml = """
                on:
                  push: {}
                jobs:
                  poll:
                    loop:
                      max_iterations: 2
                      until: "false"
                      fail_on_exhausted: false
                    steps: []
                """;
        WorkflowRun run = makeRun(yaml);
        WorkflowJobRun jobRun = pendingJobRun(run, "poll", 1);
        mockEntityReloads(run, jobRun);

        when(jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc("run-1", "poll"))
                .thenReturn(List.of(jobRun));
        when(jobRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        orchestrator.executeJob(run, "poll");

        assertThat(jobRun.getStatus()).isEqualTo(WorkflowJobStatus.SUCCESS);
    }

    @Test
    void loopReEnqueuesWithNextIterationWhenNotDone() {
        String yaml = """
                on:
                  push: {}
                jobs:
                  poll:
                    loop:
                      max_iterations: 5
                      until: "false"
                    steps: []
                """;
        WorkflowRun run = makeRun(yaml);
        WorkflowJobRun jobRun = pendingJobRun(run, "poll", 0);
        mockEntityReloads(run, jobRun);

        when(jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc("run-1", "poll"))
                .thenReturn(List.of(jobRun));
        when(jobRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        orchestrator.executeJob(run, "poll");

        assertThat(jobRun.getStatus()).isEqualTo(WorkflowJobStatus.SUCCESS);

        ArgumentCaptor<WorkflowJobRun> savedCaptor = ArgumentCaptor.forClass(WorkflowJobRun.class);
        verify(jobRunRepository, atLeastOnce()).save(savedCaptor.capture());
        List<WorkflowJobRun> savedRuns = savedCaptor.getAllValues();
        boolean nextIterationCreated = savedRuns.stream()
                .anyMatch(jr -> jr.getIteration() == 1 && jr.getStatus() == WorkflowJobStatus.PENDING);
        assertThat(nextIterationCreated).isTrue();

        verify(engine).enqueueJob("run-1", "poll");
    }

    // ---- Condition routing tests ----

    @Test
    void conditionTrueEnqueuesThenBranchAndSkipsElse() {
        String yaml = """
                on:
                  push: {}
                jobs:
                  router:
                    steps:
                      - type: condition
                        expression: "'a' == 'a'"
                        then: job-a
                        else: job-b
                  job-a:
                    steps: []
                  job-b:
                    steps: []
                """;
        WorkflowRun run = makeRun(yaml);
        WorkflowJobRun jobRun = pendingJobRun(run, "router", 0);
        mockEntityReloads(run, jobRun);

        when(jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc("run-1", "router"))
                .thenReturn(List.of(jobRun));
        when(jobRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        orchestrator.executeJob(run, "router");

        verify(engine).enqueueJob("run-1", "job-a");
        verify(engine, never()).enqueueJob("run-1", "job-b");

        ArgumentCaptor<WorkflowJobRun> savedCaptor = ArgumentCaptor.forClass(WorkflowJobRun.class);
        verify(jobRunRepository, atLeastOnce()).save(savedCaptor.capture());
        boolean jobBSkipped = savedCaptor.getAllValues().stream()
                .anyMatch(jr -> "job-b".equals(jr.getJobId()) && jr.getStatus() == WorkflowJobStatus.SKIPPED);
        assertThat(jobBSkipped).isTrue();
    }

    @Test
    void conditionFalseEnqueuesElseBranchAndSkipsThen() {
        String yaml = """
                on:
                  push: {}
                jobs:
                  router:
                    steps:
                      - type: condition
                        expression: "'a' == 'b'"
                        then: job-a
                        else: job-b
                  job-a:
                    steps: []
                  job-b:
                    steps: []
                """;
        WorkflowRun run = makeRun(yaml);
        WorkflowJobRun jobRun = pendingJobRun(run, "router", 0);
        mockEntityReloads(run, jobRun);

        when(jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc("run-1", "router"))
                .thenReturn(List.of(jobRun));
        when(jobRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        orchestrator.executeJob(run, "router");

        verify(engine).enqueueJob("run-1", "job-b");
        verify(engine, never()).enqueueJob("run-1", "job-a");

        ArgumentCaptor<WorkflowJobRun> savedCaptor = ArgumentCaptor.forClass(WorkflowJobRun.class);
        verify(jobRunRepository, atLeastOnce()).save(savedCaptor.capture());
        boolean jobASkipped = savedCaptor.getAllValues().stream()
                .anyMatch(jr -> "job-a".equals(jr.getJobId()) && jr.getStatus() == WorkflowJobStatus.SKIPPED);
        assertThat(jobASkipped).isTrue();
    }

    @Test
    void conditionSkippedBranchHasSkipReason() {
        String yaml = """
                on:
                  push: {}
                jobs:
                  router:
                    steps:
                      - type: condition
                        expression: "'a' == 'a'"
                        then: job-a
                        else: job-b
                  job-a:
                    steps: []
                  job-b:
                    steps: []
                """;
        WorkflowRun run = makeRun(yaml);
        WorkflowJobRun jobRun = pendingJobRun(run, "router", 0);
        mockEntityReloads(run, jobRun);

        when(jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc("run-1", "router"))
                .thenReturn(List.of(jobRun));
        when(jobRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        orchestrator.executeJob(run, "router");

        ArgumentCaptor<WorkflowJobRun> savedCaptor = ArgumentCaptor.forClass(WorkflowJobRun.class);
        verify(jobRunRepository, atLeastOnce()).save(savedCaptor.capture());

        WorkflowJobRun skippedJobRun = savedCaptor.getAllValues().stream()
                .filter(jr -> "job-b".equals(jr.getJobId()) && jr.getStatus() == WorkflowJobStatus.SKIPPED)
                .findFirst().orElse(null);
        assertThat(skippedJobRun).isNotNull();
        assertThat(skippedJobRun.getSkipReason()).contains("Condition routed to then branch");
    }
}
