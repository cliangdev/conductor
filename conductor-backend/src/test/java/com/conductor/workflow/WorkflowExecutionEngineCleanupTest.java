package com.conductor.workflow;

import com.conductor.entity.*;
import com.conductor.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * cleanupStuckRuns' daily sweep: run-level >24h RUNNING/PENDING -> FAILED (pre-existing), plus the new
 * job-level >24h AWAITING_PICKUP -> completeRemoteJob(..., FAILED, DAEMON_PICKUP_TIMEOUT) path — a
 * self-hosted job whose daemon never picked it up.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowExecutionEngineCleanupTest {

    @Mock WorkflowJobQueueRepository queueRepository;
    @Mock WorkflowRunRepository runRepository;
    @Mock WorkflowJobRunRepository jobRunRepository;
    @Mock WorkflowStepRunRepository stepRunRepository;
    @Mock WorkflowDefinitionRepository workflowRepository;
    @Mock WorkflowJobOrchestrator orchestrator;
    @Mock WorkflowFailureCircuitBreaker circuitBreaker;

    WorkflowExecutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new WorkflowExecutionEngine(
                queueRepository, runRepository, jobRunRepository,
                stepRunRepository, workflowRepository, orchestrator, new com.conductor.workflow.model.WorkflowYamlParser(),
                circuitBreaker, 4);
    }

    @Test
    void cleanupStuckRuns_failsAwaitingPickupJobsOlderThan24h() {
        when(runRepository.findByStatusIn(anyList())).thenReturn(List.of());

        WorkflowRun run = new WorkflowRun();
        run.setId("run-1");
        WorkflowJobRun stuckJobRun = new WorkflowJobRun();
        stuckJobRun.setId("jr-1");
        stuckJobRun.setRun(run);
        stuckJobRun.setJobId("deploy");
        stuckJobRun.setStatus(WorkflowJobStatus.AWAITING_PICKUP);

        when(jobRunRepository.findByStatusAndStartedAtBefore(eq(WorkflowJobStatus.AWAITING_PICKUP.name()), any()))
                .thenReturn(List.of(stuckJobRun));

        engine.cleanupStuckRuns();

        verify(orchestrator).completeRemoteJob("run-1", "deploy", WorkflowJobStatus.FAILED, "DAEMON_PICKUP_TIMEOUT");
    }

    @Test
    void cleanupStuckRuns_doesNothingWhenNoStuckAwaitingPickupJobs() {
        when(runRepository.findByStatusIn(anyList())).thenReturn(List.of());
        when(jobRunRepository.findByStatusAndStartedAtBefore(eq(WorkflowJobStatus.AWAITING_PICKUP.name()), any()))
                .thenReturn(List.of());

        engine.cleanupStuckRuns();

        verify(orchestrator, never()).completeRemoteJob(any(), any(), any(), any());
    }
}
