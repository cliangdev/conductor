package com.conductor.workflow;

import com.conductor.entity.*;
import com.conductor.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");

        when(queueRepository.claimAllReadyJobs()).thenReturn(List.of(entry1, entry2));
        when(runRepository.findById("run-1")).thenReturn(java.util.Optional.of(run));
        doNothing().when(orchestrator).executeJob(any(), any());

        engine.pollQueue();

        // Allow async tasks to complete
        Thread.sleep(200);

        verify(orchestrator, times(2)).executeJob(any(WorkflowRun.class), anyString());
    }
}
