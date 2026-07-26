package com.conductor.workflow;

import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.exception.BusinessException;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the filter-resolution and waitReason-derivation logic that used to live directly in
 * {@code WorkflowController#listWorkflowRuns} (moved out per {@code WorkflowController#listWorkflows}'s
 * own "filtering is domain/query logic and lives in the service" convention).
 */
@ExtendWith(MockitoExtension.class)
class WorkflowRunQueryServiceTest {

    @Mock WorkflowRunRepository runRepository;
    @Mock WorkflowJobRunRepository jobRunRepository;
    @Mock Pageable pageable;

    private WorkflowRunQueryService newService() {
        return new WorkflowRunQueryService(runRepository, jobRunRepository);
    }

    private WorkflowRun runWithStatus(String id, WorkflowRunStatus status) {
        WorkflowRun run = new WorkflowRun();
        run.setId(id);
        run.setStatus(status);
        return run;
    }

    @Test
    void findRuns_rejectsStateAndStatusTogether() {
        WorkflowRunQueryService svc = newService();

        assertThatThrownBy(() -> svc.findRuns("wf-1", List.of("PENDING"), "queued", pageable))
                .isInstanceOf(BusinessException.class);

        verify(runRepository, never()).findByWorkflowIdAndStatusIn(anyString(), any(), any(Pageable.class));
        verify(runRepository, never()).findQueuedByWorkflowId(anyString(), any(), any(), any(), any(Pageable.class));
    }

    @Test
    void findRuns_rejectsAnUnrecognizedState() {
        WorkflowRunQueryService svc = newService();

        assertThatThrownBy(() -> svc.findRuns("wf-1", null, "not-a-real-state", pageable))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void findRuns_rejectsAnUnrecognizedStatus() {
        WorkflowRunQueryService svc = newService();

        assertThatThrownBy(() -> svc.findRuns("wf-1", List.of("NOT_A_REAL_STATUS"), null, pageable))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void findRuns_stateQueued_delegatesToTheQueuedQuery() {
        WorkflowRunQueryService svc = newService();
        WorkflowRun run = runWithStatus("run-1", WorkflowRunStatus.RUNNING);
        when(runRepository.findQueuedByWorkflowId(eq("wf-1"),
                eq(Set.of(WorkflowRunStatus.PENDING, WorkflowRunStatus.PENDING_LOCAL_PICKUP)),
                eq(WorkflowJobStatus.AWAITING_PICKUP), eq(WorkflowRunStatus.TERMINAL_STATUSES), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(run)));

        List<WorkflowRun> result = svc.findRuns("wf-1", null, "queued", pageable);

        assertThat(result).containsExactly(run);
        verify(runRepository, never()).findRunningByWorkflowId(anyString(), any(), any(), any(Pageable.class));
    }

    @Test
    void findRuns_stateRunning_delegatesToTheRunningQuery() {
        WorkflowRunQueryService svc = newService();
        WorkflowRun run = runWithStatus("run-1", WorkflowRunStatus.RUNNING);
        when(runRepository.findRunningByWorkflowId(eq("wf-1"),
                eq(Set.of(WorkflowRunStatus.RUNNING, WorkflowRunStatus.CANCELLING)),
                eq(WorkflowJobStatus.AWAITING_PICKUP), eq(pageable)))
                .thenReturn(new PageImpl<>(List.of(run)));

        List<WorkflowRun> result = svc.findRuns("wf-1", null, "running", pageable);

        assertThat(result).containsExactly(run);
        verify(runRepository, never()).findQueuedByWorkflowId(anyString(), any(), any(), any(), any(Pageable.class));
    }

    @Test
    void findRuns_statusFilter_delegatesToTheStatusInQuery() {
        WorkflowRunQueryService svc = newService();
        WorkflowRun run = runWithStatus("run-1", WorkflowRunStatus.PENDING);
        when(runRepository.findByWorkflowIdAndStatusIn(eq("wf-1"), eq(Set.of(WorkflowRunStatus.PENDING)),
                eq(pageable))).thenReturn(new PageImpl<>(List.of(run)));

        List<WorkflowRun> result = svc.findRuns("wf-1", List.of("PENDING"), null, pageable);

        assertThat(result).containsExactly(run);
    }

    @Test
    void findRuns_noFilter_returnsEveryStatus() {
        WorkflowRunQueryService svc = newService();
        WorkflowRun run = runWithStatus("run-1", WorkflowRunStatus.SUCCESS);
        when(runRepository.findByWorkflowId(eq("wf-1"), eq(pageable))).thenReturn(new PageImpl<>(List.of(run)));

        List<WorkflowRun> result = svc.findRuns("wf-1", null, null, pageable);

        assertThat(result).containsExactly(run);
    }

    @Test
    void deriveWaitReasons_excludesTerminalRunsFromTheLookup_andBatchesInOneCall() {
        WorkflowRunQueryService svc = newService();
        WorkflowRun awaitingRunner = runWithStatus("run-1", WorkflowRunStatus.RUNNING);
        WorkflowRun pending = runWithStatus("run-2", WorkflowRunStatus.PENDING);
        WorkflowRun finished = runWithStatus("run-3", WorkflowRunStatus.SUCCESS);
        when(jobRunRepository.findDistinctRunIdsByRunIdInAndStatusAndClaimedAtIsNull(
                eq(List.of("run-1", "run-2")), eq(WorkflowJobStatus.AWAITING_PICKUP)))
                .thenReturn(List.of("run-1"));

        Map<String, String> reasons = svc.deriveWaitReasons(List.of(awaitingRunner, pending, finished));

        assertThat(reasons).containsExactly(Map.entry("run-1", "AWAITING_RUNNER"));
        verify(jobRunRepository, times(1))
                .findDistinctRunIdsByRunIdInAndStatusAndClaimedAtIsNull(any(), eq(WorkflowJobStatus.AWAITING_PICKUP));
    }

    @Test
    void deriveWaitReasons_skipsTheLookupEntirely_whenEveryRunIsTerminal() {
        WorkflowRunQueryService svc = newService();
        WorkflowRun finished = runWithStatus("run-1", WorkflowRunStatus.SUCCESS);

        Map<String, String> reasons = svc.deriveWaitReasons(List.of(finished));

        assertThat(reasons).isEmpty();
        verify(jobRunRepository, never()).findDistinctRunIdsByRunIdInAndStatusAndClaimedAtIsNull(any(), any());
    }
}
