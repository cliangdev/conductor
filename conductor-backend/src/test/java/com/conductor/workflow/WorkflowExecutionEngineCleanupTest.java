package com.conductor.workflow;

import com.conductor.entity.*;
import com.conductor.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
    @Mock WorkflowRunFailureNotifier runFailureNotifier;
    @Mock WorkflowJobDispatcher cloudTasksJobDispatcher;

    WorkflowExecutionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new WorkflowExecutionEngine(
                queueRepository, runRepository, jobRunRepository,
                stepRunRepository, workflowRepository, orchestrator, new com.conductor.workflow.model.WorkflowYamlParser(),
                circuitBreaker, runFailureNotifier, cloudTasksJobDispatcher);
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

    @Test
    void cleanupStuckRuns_marksRunOlderThan24hFailed_andNotifies() {
        WorkflowRun stuckRun = new WorkflowRun();
        stuckRun.setId("run-stuck");
        stuckRun.setStatus(WorkflowRunStatus.RUNNING);
        stuckRun.setStartedAt(OffsetDateTime.now().minusHours(25));
        when(runRepository.findByStatusIn(anyList())).thenReturn(List.of(stuckRun));
        when(jobRunRepository.findByStatusAndStartedAtBefore(eq(WorkflowJobStatus.AWAITING_PICKUP.name()), any()))
                .thenReturn(List.of());

        engine.cleanupStuckRuns();

        assertThat(stuckRun.getStatus()).isEqualTo(WorkflowRunStatus.FAILED);
        verify(runRepository).save(stuckRun);
        verify(runFailureNotifier).notifyFailed(stuckRun);
    }

    @Test
    void cleanupStuckRuns_leavesRecentRunAlone_neverNotifies() {
        WorkflowRun recentRun = new WorkflowRun();
        recentRun.setId("run-recent");
        recentRun.setStatus(WorkflowRunStatus.RUNNING);
        recentRun.setStartedAt(OffsetDateTime.now().minusHours(1));
        when(runRepository.findByStatusIn(anyList())).thenReturn(List.of(recentRun));
        when(jobRunRepository.findByStatusAndStartedAtBefore(eq(WorkflowJobStatus.AWAITING_PICKUP.name()), any()))
                .thenReturn(List.of());

        engine.cleanupStuckRuns();

        assertThat(recentRun.getStatus()).isEqualTo(WorkflowRunStatus.RUNNING);
        verify(runFailureNotifier, never()).notifyFailed(any());
    }
}
