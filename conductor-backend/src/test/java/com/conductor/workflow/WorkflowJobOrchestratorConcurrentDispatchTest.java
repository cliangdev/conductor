package com.conductor.workflow;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.repository.WorkflowDefinitionRepository;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the cloud-run double-dispatch race (observed live on a {@code review_backend}
 * job): {@link WorkflowJobOrchestrator#planJobExecution} could create two separate {@code
 * WorkflowJobRun} rows — and trigger two separate Cloud Run launches — for one logical job execution
 * when two concurrent readiness triggers landed for the same non-self-hosted job.
 *
 * <p>Pure unit test (no Spring context, per docs/testing-guidelines.md), following the pattern in
 * {@link WorkflowLoopOrchestratorTest}. Rather than spinning up real threads (impractical against a
 * mocked, non-thread-safe repository), this drives {@code planJobExecution} directly, twice in a row,
 * against a small in-memory "table" backing the mocked {@link WorkflowJobRunRepository}. The first
 * call reproduces what a real concurrent caller would already have committed (the row moved to
 * RUNNING) by the time a second caller acquires the row lock — since {@code runSteps}/{@code
 * finalizeJob} run later, outside this transaction, exactly as described in the bug report. The
 * second call then reproduces the race itself.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowJobOrchestratorConcurrentDispatchTest {

    private static final String RUN_ID = "run-1";
    private static final String JOB_ID = "review_backend";

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

    WorkflowJobOrchestrator orchestrator;
    ConditionEvaluator conditionEvaluator = new ConditionEvaluator();
    WorkflowInterpolator interpolator = new WorkflowInterpolator();
    ObjectMapper objectMapper = new ObjectMapper();

    /** In-memory stand-in for the workflow_job_runs rows for (RUN_ID, JOB_ID), across both calls. */
    private final List<WorkflowJobRun> backingRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        RuntimeContextBuilder contextBuilder = new RuntimeContextBuilder(
                secretsService, stepRunRepository, jobRunRepository, objectMapper, artifactService);
        orchestrator = new WorkflowJobOrchestrator(
                jobRunRepository, stepRunRepository, runRepository, workflowRepository,
                engine, conditionEvaluator, interpolator, contextBuilder,
                logRedactionService, List.of(), objectMapper, selfHostedJobDispatcher,
                upstreamOutputsResolver, new WorkflowYamlParser());

        when(secretsService.resolveSecrets(any())).thenReturn(Map.of());
        when(stepRunRepository.findByJobRunId(any())).thenReturn(List.of());
        when(logRedactionService.redact(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        WorkflowRun run = makeRun("""
                on:
                  push: {}
                jobs:
                  review_backend:
                    runs-on: cloud-run
                    steps: []
                """);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(runRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));

        // Dynamic view over backingRows (desc by iteration), and a save() that appends into it —
        // together these let the second call see whatever the first call actually persisted.
        when(jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc(eq(RUN_ID), eq(JOB_ID)))
                .thenAnswer(inv -> {
                    List<WorkflowJobRun> sorted = new ArrayList<>(backingRows);
                    sorted.sort(Comparator.comparingInt(WorkflowJobRun::getIteration).reversed());
                    return sorted;
                });
        when(jobRunRepository.save(any())).thenAnswer(inv -> {
            WorkflowJobRun jr = inv.getArgument(0);
            if (!backingRows.contains(jr)) {
                backingRows.add(jr);
            }
            return jr;
        });
    }

    private WorkflowRun makeRun(String yaml) {
        Project project = new Project();
        project.setId("proj-1");

        WorkflowDefinition def = new WorkflowDefinition();
        def.setYaml(yaml);
        def.setProject(project);

        WorkflowRun run = new WorkflowRun();
        run.setId(RUN_ID);
        run.setWorkflow(def);
        return run;
    }

    @Test
    void secondConcurrentCallerSkipsDuplicateDispatch_whenFirstCallerAlreadyMarkedRunning() {
        // First caller: no existing row yet — creates one, moves it to RUNNING, and returns an
        // executable plan (runSteps/finalizeJob have NOT run yet — they happen later, outside this
        // transaction — so the row is left RUNNING exactly as a real concurrent second caller would
        // observe it).
        WorkflowJobOrchestrator.JobExecutionPlan first = orchestrator.planJobExecution(RUN_ID, JOB_ID);

        assertThat(first.done).isFalse();
        assertThat(backingRows).hasSize(1);
        assertThat(backingRows.get(0).getStatus()).isEqualTo(WorkflowJobStatus.RUNNING);

        // Second, concurrent caller for the exact same job: must see the RUNNING row under the same
        // lock and bail out without creating a second row or a second dispatch.
        WorkflowJobOrchestrator.JobExecutionPlan second = orchestrator.planJobExecution(RUN_ID, JOB_ID);

        assertThat(second.done).isTrue();
        assertThat(backingRows).hasSize(1); // no duplicate WorkflowJobRun row was created
        verify(runRepository, org.mockito.Mockito.times(2)).findByIdForUpdate(RUN_ID); // both callers took the lock
    }

    @Test
    void firstCallProceedsNormally_controlCase() {
        WorkflowJobOrchestrator.JobExecutionPlan plan = orchestrator.planJobExecution(RUN_ID, JOB_ID);

        assertThat(plan.done).isFalse();
        assertThat(plan.jobId).isEqualTo(JOB_ID);
        assertThat(backingRows).hasSize(1);
        assertThat(backingRows.get(0).getStatus()).isEqualTo(WorkflowJobStatus.RUNNING);
    }

    @Test
    void selfHostedJobIsNotSkippedByRunningGuard_onlyByAwaitingPickup() {
        // A self-hosted job whose latest row is RUNNING (e.g. mid-execution on the daemon) must NOT
        // be caught by the cloud-run RUNNING guard — only AWAITING_PICKUP means "already dispatched"
        // for the self-hosted path.
        WorkflowRun run = makeRun("""
                on:
                  push: {}
                jobs:
                  daemon_job:
                    runs-on: self-hosted
                    steps: []
                """);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(runRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));

        WorkflowJobRun runningRow = new WorkflowJobRun();
        runningRow.setId("jr-daemon-0");
        runningRow.setRun(run);
        runningRow.setJobId("daemon_job");
        runningRow.setIteration(0);
        runningRow.setStatus(WorkflowJobStatus.RUNNING);
        when(jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc(RUN_ID, "daemon_job"))
                .thenReturn(List.of(runningRow));

        WorkflowJobOrchestrator.JobExecutionPlan plan = orchestrator.planJobExecution(RUN_ID, "daemon_job");

        assertThat(plan.done).isTrue(); // dispatched again via selfHostedJobDispatcher, then AWAITING_PICKUP
        verify(selfHostedJobDispatcher).dispatch(eq(run), eq("daemon_job"), any(), any());
    }

    @Test
    void runStatusFlipsToRunning_onFirstJobDispatch() {
        // Regression: the run row itself never left PENDING until completion, so the run-level status
        // shown on the list/overview pages lagged behind the job/step-level status on the run detail
        // page (which reads WorkflowJobRun/WorkflowStepRun directly) — a run mid-execution showed
        // "Pending" everywhere except its own step rows.
        WorkflowRun run = makeRun("""
                on:
                  push: {}
                jobs:
                  review_backend:
                    runs-on: cloud-run
                    steps: []
                """);
        run.setStatus(WorkflowRunStatus.PENDING);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(runRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));

        orchestrator.planJobExecution(RUN_ID, JOB_ID);

        assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.RUNNING);
        verify(runRepository).save(run);
    }

    @Test
    void runStatusUntouched_whenAlreadyRunning() {
        WorkflowRun run = makeRun("""
                on:
                  push: {}
                jobs:
                  review_backend:
                    runs-on: cloud-run
                    steps: []
                """);
        run.setStatus(WorkflowRunStatus.RUNNING);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(runRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));

        orchestrator.planJobExecution(RUN_ID, JOB_ID);

        assertThat(run.getStatus()).isEqualTo(WorkflowRunStatus.RUNNING);
        verify(runRepository, never()).save(run);
    }

    @Test
    void selfHostedJobSkipsDuplicateDispatch_whenLatestIsAwaitingPickup() {
        WorkflowRun run = makeRun("""
                on:
                  push: {}
                jobs:
                  daemon_job:
                    runs-on: self-hosted
                    steps: []
                """);
        when(runRepository.findById(RUN_ID)).thenReturn(Optional.of(run));
        when(runRepository.findByIdForUpdate(RUN_ID)).thenReturn(Optional.of(run));

        WorkflowJobRun awaitingRow = new WorkflowJobRun();
        awaitingRow.setId("jr-daemon-0");
        awaitingRow.setRun(run);
        awaitingRow.setJobId("daemon_job");
        awaitingRow.setIteration(0);
        awaitingRow.setStatus(WorkflowJobStatus.AWAITING_PICKUP);
        when(jobRunRepository.findByRunIdAndJobIdOrderByIterationDesc(RUN_ID, "daemon_job"))
                .thenReturn(List.of(awaitingRow));

        WorkflowJobOrchestrator.JobExecutionPlan plan = orchestrator.planJobExecution(RUN_ID, "daemon_job");

        assertThat(plan.done).isTrue();
        verify(selfHostedJobDispatcher, never()).dispatch(any(), any(), any(), any());
        verify(jobRunRepository, never()).save(any());
    }
}
