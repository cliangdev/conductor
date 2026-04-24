package com.conductor.workflow;

import com.conductor.entity.*;
import com.conductor.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionEngineParallelTest {

    @Mock WorkflowJobQueueRepository queueRepository;
    @Mock WorkflowRunRepository runRepository;
    @Mock WorkflowJobRunRepository jobRunRepository;
    @Mock WorkflowStepRunRepository stepRunRepository;
    @Mock WorkflowDefinitionRepository workflowRepository;
    @Mock WorkflowJobOrchestrator orchestrator;

    WorkflowExecutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new WorkflowExecutionEngine(
                queueRepository, runRepository, jobRunRepository,
                stepRunRepository, workflowRepository, orchestrator);
        // In unit tests there is no Spring context to inject @Lazy @Autowired self,
        // so we wire it manually to avoid NPE in the async CompletableFuture dispatch.
        ReflectionTestUtils.setField(engine, "self", engine);
    }

    private WorkflowJobQueue makeQueueEntry(String runId, String jobId) {
        WorkflowRun run = new WorkflowRun();
        run.setId(runId);

        WorkflowJobQueue entry = new WorkflowJobQueue();
        entry.setId(java.util.UUID.randomUUID().toString());
        entry.setRun(run);
        entry.setJobId(jobId);
        return entry;
    }

    @Test
    void pollQueue_claimsAllReadyJobsAndMarksThemClaimed() {
        WorkflowJobQueue entry1 = makeQueueEntry("run-1", "job-a");
        WorkflowJobQueue entry2 = makeQueueEntry("run-1", "job-b");

        when(queueRepository.claimAllReadyJobs()).thenReturn(List.of(entry1, entry2));

        engine.pollQueue();

        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
        verify(queueRepository).markAllClaimed(idsCaptor.capture());
        assertThat(idsCaptor.getValue()).contains(entry1.getId(), entry2.getId());
    }

    @Test
    void pollQueue_doesNothingWhenQueueEmpty() {
        when(queueRepository.claimAllReadyJobs()).thenReturn(List.of());

        engine.pollQueue();

        verify(queueRepository, never()).markAllClaimed(any());
    }

    @Test
    void pollQueue_dispatchesBothJobsForParallelRun() throws InterruptedException {
        WorkflowJobQueue entry1 = makeQueueEntry("run-1", "job-a");
        WorkflowJobQueue entry2 = makeQueueEntry("run-1", "job-b");

        when(queueRepository.claimAllReadyJobs()).thenReturn(List.of(entry1, entry2));
        doNothing().when(orchestrator).executeJob(anyString(), anyString());

        engine.pollQueue();

        // Allow async tasks to complete
        Thread.sleep(200);

        verify(orchestrator, times(2)).executeJob(anyString(), anyString());
    }

    @Test
    void pollQueue_backsOffExponentiallyWhenQueueStaysEmpty() {
        when(queueRepository.claimAllReadyJobs()).thenReturn(List.of());

        // Tick #1 — does the DB query, finds nothing, bumps backoff to 2 ticks
        engine.pollQueue();
        // Tick #2 — skipped (backoff counter still positive)
        engine.pollQueue();
        // Tick #3 — does DB query, empty again, bumps backoff to 4
        engine.pollQueue();
        // Ticks #4..#6 — skipped
        engine.pollQueue();
        engine.pollQueue();
        engine.pollQueue();
        // Tick #7 — does DB query
        engine.pollQueue();

        // 7 scheduled ticks, only 3 should have actually hit the DB (#1, #3, #7)
        verify(queueRepository, times(3)).claimAllReadyJobs();
    }

    @Test
    void pollQueue_resetsBackoffWhenWorkAppears() {
        WorkflowJobQueue entry = makeQueueEntry("run-1", "job-a");
        when(queueRepository.claimAllReadyJobs())
                .thenReturn(List.of())         // tick #1 — empty, backoff → 2
                .thenReturn(List.of(entry))    // tick #3 — has work, backoff → 1
                .thenReturn(List.of(entry));   // tick #4 — still has work
        // orchestrator.executeJob is void → default mock behavior (no-op) suffices; no stub needed.

        engine.pollQueue();  // #1 — queries (empty), sets skip=1
        engine.pollQueue();  // #2 — skipped
        engine.pollQueue();  // #3 — queries (work found), resets backoff, skip=0
        engine.pollQueue();  // #4 — queries again immediately (no skip)

        verify(queueRepository, times(3)).claimAllReadyJobs();
    }
}
