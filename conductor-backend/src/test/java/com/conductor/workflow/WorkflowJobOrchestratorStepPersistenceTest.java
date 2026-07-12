package com.conductor.workflow;

import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.entity.WorkflowStepStatus;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.service.LogRedactionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Pure unit test (no Spring context, per docs/testing-guidelines.md) for
 * {@link WorkflowJobOrchestrator#persistStepResult}'s update-in-place behavior: a pre-created row
 * (Phase 5's Cloud Run executor, or self-hosted's per-step pre-creation) is updated rather than
 * duplicated when the {@link StepResult} carries a matching workerJobId; today's insert path is
 * unchanged when no workerJobId is set (all current step executors).
 */
class WorkflowJobOrchestratorStepPersistenceTest {

    private final WorkflowJobRunRepository jobRunRepository = mock(WorkflowJobRunRepository.class);
    private final WorkflowStepRunRepository stepRunRepository = mock(WorkflowStepRunRepository.class);
    private final LogRedactionService logRedactionService = mock(LogRedactionService.class);

    private WorkflowJobOrchestrator orchestrator;
    private WorkflowJobRun jobRun;

    @BeforeEach
    void setUp() {
        orchestrator = new WorkflowJobOrchestrator(
                jobRunRepository,
                stepRunRepository,
                mock(WorkflowRunRepository.class),
                mock(WorkflowDefinitionRepository.class),
                mock(WorkflowExecutionEngine.class),
                mock(ConditionEvaluator.class),
                mock(WorkflowInterpolator.class),
                mock(RuntimeContextBuilder.class),
                logRedactionService,
                List.of(),
                new ObjectMapper(),
                mock(SelfHostedJobDispatcher.class),
                mock(UpstreamOutputsResolver.class));

        jobRun = new WorkflowJobRun();
        jobRun.setId("jobrun-1");
        when(jobRunRepository.findById("jobrun-1")).thenReturn(Optional.of(jobRun));
        when(logRedactionService.redact(anyString(), anyString())).thenAnswer(inv -> inv.getArgument(1));
    }

    @Test
    void updatesPreCreatedRowInPlace_whenWorkerJobIdMatches() {
        WorkflowStepRun preCreated = new WorkflowStepRun();
        preCreated.setId("step-1");
        preCreated.setJobRun(jobRun);
        preCreated.setWorkerJobId("worker-abc");
        preCreated.setStatus(WorkflowStepStatus.PENDING);
        when(stepRunRepository.findByJobRunIdAndWorkerJobId("jobrun-1", "worker-abc"))
                .thenReturn(Optional.of(preCreated));

        StepResult result = StepResult.success("done", Map.of("summary", "ok")).withWorkerJobId("worker-abc");
        Map<String, Object> stepDef = Map.of("id", "seo", "name", "SEO step", "uses", "claude-code");

        orchestrator.persistStepResult("jobrun-1", stepDef, result, "proj-1");

        ArgumentCaptor<WorkflowStepRun> captor = ArgumentCaptor.forClass(WorkflowStepRun.class);
        verify(stepRunRepository, times(1)).save(captor.capture());
        WorkflowStepRun saved = captor.getValue();
        assertThat(saved).isSameAs(preCreated);
        assertThat(saved.getId()).isEqualTo("step-1");
        assertThat(saved.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        assertThat(saved.getOutputJson()).contains("summary");
        assertThat(saved.getCompletedAt()).isNotNull();
    }

    @Test
    void insertsNewRow_whenNoWorkerJobIdOnResult() {
        StepResult result = StepResult.success("done", Map.of());
        Map<String, Object> stepDef = Map.of("id", "post", "name", "Notify", "type", "http");

        orchestrator.persistStepResult("jobrun-1", stepDef, result, "proj-1");

        verify(stepRunRepository, never()).findByJobRunIdAndWorkerJobId(anyString(), anyString());
        ArgumentCaptor<WorkflowStepRun> captor = ArgumentCaptor.forClass(WorkflowStepRun.class);
        verify(stepRunRepository, times(1)).save(captor.capture());
        WorkflowStepRun saved = captor.getValue();
        assertThat(saved.getId()).isNull(); // not yet persisted; @PrePersist assigns on flush
        assertThat(saved.getWorkerJobId()).isNull();
        assertThat(saved.getStepId()).isEqualTo("post");
        assertThat(saved.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
    }

    @Test
    void doesNotOverwriteLog_whenPreCreatedRowAlreadyHasStreamedContent() {
        WorkflowStepRun preCreated = new WorkflowStepRun();
        preCreated.setId("step-1");
        preCreated.setJobRun(jobRun);
        preCreated.setWorkerJobId("worker-abc");
        preCreated.setStatus(WorkflowStepStatus.RUNNING);
        // Launcher lines + streamed container lines, already appended in arrival order while the
        // step was running (ClaudeCodeStepExecutor / WorkflowRunLogBroker) — strictly richer than
        // the terminal result's own log text.
        preCreated.setLog("→ Launching Cloud Run execution\ncontainer streamed line 1\ncontainer streamed line 2\n");
        when(stepRunRepository.findByJobRunIdAndWorkerJobId("jobrun-1", "worker-abc"))
                .thenReturn(Optional.of(preCreated));

        StepResult result = StepResult.success("→ Launching Cloud Run execution\n← execution finished: SUCCEEDED\n",
                Map.of("summary", "ok")).withWorkerJobId("worker-abc");
        Map<String, Object> stepDef = Map.of("id", "seo", "name", "SEO step", "uses", "claude-code");

        orchestrator.persistStepResult("jobrun-1", stepDef, result, "proj-1");

        ArgumentCaptor<WorkflowStepRun> captor = ArgumentCaptor.forClass(WorkflowStepRun.class);
        verify(stepRunRepository, times(1)).save(captor.capture());
        WorkflowStepRun saved = captor.getValue();
        assertThat(saved.getLog())
                .isEqualTo("→ Launching Cloud Run execution\ncontainer streamed line 1\ncontainer streamed line 2\n");
        assertThat(saved.getStatus()).isEqualTo(WorkflowStepStatus.SUCCESS);
        assertThat(saved.getOutputJson()).contains("summary");
        verify(logRedactionService, never()).redact(anyString(), anyString());
    }

    @Test
    void insertsNewRow_whenWorkerJobIdSetButNoMatchingPreCreatedRow() {
        when(stepRunRepository.findByJobRunIdAndWorkerJobId("jobrun-1", "worker-missing"))
                .thenReturn(Optional.empty());

        StepResult result = StepResult.failed("boom", "CLAUDE_AGENT_ERROR").withWorkerJobId("worker-missing");
        Map<String, Object> stepDef = Map.of("id", "seo", "name", "SEO step", "uses", "claude-code");

        orchestrator.persistStepResult("jobrun-1", stepDef, result, "proj-1");

        ArgumentCaptor<WorkflowStepRun> captor = ArgumentCaptor.forClass(WorkflowStepRun.class);
        verify(stepRunRepository, times(1)).save(captor.capture());
        WorkflowStepRun saved = captor.getValue();
        assertThat(saved.getWorkerJobId()).isEqualTo("worker-missing");
        assertThat(saved.getStatus()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(saved.getErrorReason()).isEqualTo("CLAUDE_AGENT_ERROR");
    }
}
