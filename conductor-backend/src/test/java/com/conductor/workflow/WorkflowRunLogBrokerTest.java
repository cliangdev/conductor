package com.conductor.workflow;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.entity.WorkflowStepStatus;
import com.conductor.entity.Project;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.service.LogRedactionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (no Spring context) for {@link WorkflowRunLogBroker}'s worker-callback recording
 * methods. Phase 1 pre-creates one {@code WorkflowStepRun} per self-hosted step with a real
 * {@code workerJobId} ({@code jobRunId + ":" + stepIndex}), which is what lets {@code recordOutputs} /
 * {@code recordJobFailed} / {@code recordStepCompleted} actually match a row instead of silently
 * finding nothing.
 */
class WorkflowRunLogBrokerTest {

    private final WorkflowRunRepository runRepository = mock(WorkflowRunRepository.class);
    private final WorkflowJobRunRepository jobRunRepository = mock(WorkflowJobRunRepository.class);
    private final WorkflowStepRunRepository stepRunRepository = mock(WorkflowStepRunRepository.class);
    private final LogRedactionService logRedactionService = mock(LogRedactionService.class);

    private WorkflowRunLogBroker broker;

    @BeforeEach
    void setUp() {
        broker = new WorkflowRunLogBroker(runRepository, jobRunRepository, stepRunRepository, new ObjectMapper(),
                logRedactionService, new com.conductor.workflow.model.WorkflowYamlParser());
    }

    private WorkflowJobRun jobRunWithStep(WorkflowStepRun step) {
        WorkflowJobRun jobRun = new WorkflowJobRun();
        jobRun.setId("jobrun-1");
        when(stepRunRepository.findByJobRunId("jobrun-1")).thenReturn(List.of(step));
        return jobRun;
    }

    @Test
    void recordStepCompleted_setsStatusOutputsErrorReasonAndCompletedAt_forMatchingWorkerJobId() {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setId("step-1");
        step.setWorkerJobId("jobrun-1:0");
        step.setStatus(WorkflowStepStatus.PENDING);
        WorkflowJobRun jobRun = jobRunWithStep(step);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        broker.recordStepCompleted("run-1", "jobrun-1:0", WorkflowStepStatus.SUCCESS, 0, null,
                Map.of("summary", "done"));

        assertThat(step.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        assertThat(step.getErrorReason()).isNull();
        assertThat(step.getOutputJson()).contains("summary");
        assertThat(step.getStartedAt()).isNotNull();
        assertThat(step.getCompletedAt()).isNotNull();
        verify(stepRunRepository).save(step);
    }

    @Test
    void recordStepCompleted_setsErrorReason_andDoesNotOverwriteExistingStartedAt() {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setId("step-1");
        step.setWorkerJobId("jobrun-1:0");
        java.time.OffsetDateTime startedAt = java.time.OffsetDateTime.now().minusMinutes(5);
        step.setStartedAt(startedAt);
        WorkflowJobRun jobRun = jobRunWithStep(step);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        broker.recordStepCompleted("run-1", "jobrun-1:0", WorkflowStepStatus.FAILED, 1,
                "CLAUDE_AGENT_ERROR", null);

        assertThat(step.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(step.getErrorReason()).isEqualTo("CLAUDE_AGENT_ERROR");
        assertThat(step.getStartedAt()).isEqualTo(startedAt);
    }

    @Test
    void recordStepCompleted_terminalStep_ignoresLateReportAndDoesNotSave() {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setId("step-1");
        step.setWorkerJobId("jobrun-1:0");
        step.setStatus(WorkflowStepStatus.SUCCESS);
        step.setOutputJson("{\"summary\":\"original\"}");
        WorkflowJobRun jobRun = jobRunWithStep(step);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        broker.recordStepCompleted("run-1", "jobrun-1:0", WorkflowStepStatus.FAILED, 1, "LATE_ERROR", Map.of());

        assertThat(step.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        assertThat(step.getOutputJson()).isEqualTo("{\"summary\":\"original\"}");
        assertThat(step.getErrorReason()).isNull();
        verify(stepRunRepository, never()).save(step);
    }

    @Test
    void recordStepCompleted_appliesDeclaredOutputsMapping_resolvedByStepId() {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setId("step-1");
        step.setStepId("seo");
        step.setWorkerJobId("jobrun-1:0");
        step.setStatus(WorkflowStepStatus.PENDING);
        WorkflowJobRun jobRun = jobRunWithStep(step);
        jobRun.setJobId("analyze");
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        WorkflowDefinition def = new WorkflowDefinition();
        def.setYaml("""
                jobs:
                  analyze:
                    runs-on: self-hosted
                    steps:
                      - id: seo
                        uses: claude-code
                        with:
                          prompt: hi
                        outputs:
                          result: body.summary
                """);
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        run.setWorkflow(def);
        when(runRepository.findByIdWithWorkflow("run-1")).thenReturn(Optional.of(run));

        broker.recordStepCompleted("run-1", "jobrun-1:0", WorkflowStepStatus.SUCCESS, 0, null,
                Map.of("summary", "the analysis"));

        assertThat(step.getOutputJson()).contains("\"result\":\"the analysis\"");
        assertThat(step.getOutputJson()).contains("\"summary\":\"the analysis\"");
    }

    @Test
    void recordStepCompleted_appliesDeclaredOutputsMapping_resolvedByWorkerJobIdIndex_whenStepIdMissing() {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setId("step-1");
        step.setWorkerJobId("jobrun-1:0");
        step.setStatus(WorkflowStepStatus.PENDING);
        WorkflowJobRun jobRun = jobRunWithStep(step);
        jobRun.setJobId("analyze");
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        WorkflowDefinition def = new WorkflowDefinition();
        def.setYaml("""
                jobs:
                  analyze:
                    runs-on: self-hosted
                    steps:
                      - uses: claude-code
                        with:
                          prompt: hi
                        outputs:
                          result: body.summary
                """);
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        run.setWorkflow(def);
        when(runRepository.findByIdWithWorkflow("run-1")).thenReturn(Optional.of(run));

        broker.recordStepCompleted("run-1", "jobrun-1:0", WorkflowStepStatus.SUCCESS, 0, null,
                Map.of("summary", "the analysis"));

        assertThat(step.getOutputJson()).contains("\"result\":\"the analysis\"");
    }

    @Test
    void recordStepCompleted_unresolvableStepDefinition_persistsOutputsUnmapped() {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setId("step-1");
        step.setStepId("seo");
        step.setWorkerJobId("jobrun-1:0");
        step.setStatus(WorkflowStepStatus.PENDING);
        WorkflowJobRun jobRun = jobRunWithStep(step);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));
        when(runRepository.findByIdWithWorkflow("run-1")).thenReturn(Optional.empty());

        broker.recordStepCompleted("run-1", "jobrun-1:0", WorkflowStepStatus.SUCCESS, 0, null,
                Map.of("summary", "the analysis"));

        assertThat(step.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        assertThat(step.getOutputJson()).contains("\"summary\":\"the analysis\"");
    }

    @Test
    void recordStepCompleted_unknownWorkerJobId_isNoOp() {
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of());

        broker.recordStepCompleted("run-1", "does-not-exist", WorkflowStepStatus.SUCCESS, 0, null, Map.of());

        verify(stepRunRepository, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordOutputs_matchesPreCreatedRowByWorkerJobId() {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setId("step-1");
        step.setWorkerJobId("jobrun-1:0");
        WorkflowJobRun jobRun = jobRunWithStep(step);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        broker.recordOutputs("run-1", "jobrun-1:0", Map.of("data", "hello"));

        assertThat(step.getOutputJson()).contains("hello");
        verify(stepRunRepository).save(step);
    }

    @Test
    void appendLogChunk_withWorkerJobId_appendsRedactedLinesToStepRowAndBuffersRunLevel() {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setId("step-1");
        step.setWorkerJobId("jobrun-1:0");
        step.setLog("existing line\n");
        WorkflowJobRun jobRun = jobRunWithStep(step);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));

        WorkflowDefinition def = new WorkflowDefinition();
        def.setYaml("jobs: {}");
        Project project = new Project();
        project.setId("proj-1");
        def.setProject(project);
        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        run.setWorkflow(def);
        when(runRepository.findByIdWithWorkflow("run-1")).thenReturn(Optional.of(run));

        when(logRedactionService.redact(eq("proj-1"), anyString())).thenAnswer(inv -> inv.getArgument(1));

        broker.appendLogChunk("run-1", "jobrun-1:0", List.of("new line 1", "new line 2"));

        assertThat(step.getLog()).isEqualTo("existing line\nnew line 1\nnew line 2\n");
        verify(logRedactionService).redact("proj-1", "new line 1\nnew line 2\n");
        verify(stepRunRepository).save(step);
    }

    @Test
    void appendToStepLog_staleCallerEntity_doesNotWipeChunksAlreadyOnTheRow() {
        // The executor holds its entity across the whole step execution; container chunks land on
        // the row in the meantime. The terminal append must re-read the row, not save the stale copy
        // (the bug showed as "claude logs vanish when the step completes").
        WorkflowStepRun stale = new WorkflowStepRun();
        stale.setId("step-1");
        stale.setWorkerJobId("jobrun-1:0");
        stale.setLog("→ Launching\n← execution: exec-1\n");

        WorkflowStepRun fresh = new WorkflowStepRun();
        fresh.setId("step-1");
        fresh.setWorkerJobId("jobrun-1:0");
        fresh.setLog("→ Launching\n← execution: exec-1\n→ tool: Read\n💬 analyzing…\n");
        when(stepRunRepository.findById("step-1")).thenReturn(Optional.of(fresh));

        broker.appendToStepLog(stale, List.of("← execution finished: SUCCEEDED"), null);

        verify(stepRunRepository).save(fresh);
        assertThat(fresh.getLog()).isEqualTo(
                "→ Launching\n← execution: exec-1\n→ tool: Read\n💬 analyzing…\n← execution finished: SUCCEEDED\n");
        // Caller's copy is synced forward so later appends through it can't resurrect the stale prefix.
        assertThat(stale.getLog()).isEqualTo(fresh.getLog());
    }

    @Test
    void appendLogChunk_withoutWorkerJobId_behavesExactlyAsBefore_noStepRowLookup() {
        broker.appendLogChunk("run-1", List.of("line 1"));

        verifyNoInteractions(jobRunRepository, stepRunRepository, logRedactionService);
    }

    @Test
    void appendLogChunk_unknownWorkerJobId_isRunLevelOnly_doesNotCrash() {
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of());

        broker.appendLogChunk("run-1", "does-not-exist", List.of("line 1"));

        verify(stepRunRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordJobFailed_matchesPreCreatedRowByWorkerJobId_andRollsUpJobStatus() {
        WorkflowStepRun step = new WorkflowStepRun();
        step.setId("step-1");
        step.setWorkerJobId("jobrun-1:0");
        WorkflowJobRun jobRun = jobRunWithStep(step);
        jobRun.setStatus(WorkflowJobStatus.RUNNING);
        when(jobRunRepository.findByRunId("run-1")).thenReturn(List.of(jobRun));
        when(runRepository.findByIdWithWorkflow("run-1")).thenReturn(java.util.Optional.empty());

        broker.recordJobFailed("run-1", "jobrun-1:0", "Container exited with code 1");

        assertThat(step.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(step.getErrorReason()).isEqualTo("Container exited with code 1");
        assertThat(jobRun.getStatus()).isEqualTo(WorkflowJobStatus.FAILED);
        verify(stepRunRepository).save(step);
        verify(jobRunRepository).save(jobRun);
    }
}
