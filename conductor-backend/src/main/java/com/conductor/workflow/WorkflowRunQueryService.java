package com.conductor.workflow;

import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.exception.BusinessException;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves the run list's {@code ?status=}/{@code ?state=} filters and derives
 * {@code WorkflowRunDto.waitReason} — the query/domain logic behind {@code listWorkflowRuns}, kept out
 * of the controller per the same "filtering lives in the service" convention {@code
 * WorkflowController#listWorkflows} already documents.
 */
@Service
public class WorkflowRunQueryService {

    /** Run-level statuses {@code ?state=queued} matches outright (plus any run blocked on an unclaimed
     *  self-hosted job regardless of its own run-level status — see {@link
     *  WorkflowRunRepository#findQueuedByWorkflowId}). */
    private static final Set<WorkflowRunStatus> QUEUED_STATE_STATUSES =
            Set.of(WorkflowRunStatus.PENDING, WorkflowRunStatus.PENDING_LOCAL_PICKUP);

    /** Run-level statuses {@code ?state=running} matches, minus any run blocked on an unclaimed
     *  self-hosted job — see {@link WorkflowRunRepository#findRunningByWorkflowId}. */
    private static final Set<WorkflowRunStatus> RUNNING_STATE_STATUSES =
            Set.of(WorkflowRunStatus.RUNNING, WorkflowRunStatus.CANCELLING);

    private final WorkflowRunRepository runRepository;
    private final WorkflowJobRunRepository jobRunRepository;

    public WorkflowRunQueryService(WorkflowRunRepository runRepository, WorkflowJobRunRepository jobRunRepository) {
        this.runRepository = runRepository;
        this.jobRunRepository = jobRunRepository;
    }

    /**
     * Resolves a workflow's run list for the given filters. {@code state} and a non-empty {@code
     * status} are mutually exclusive (rejected with {@link BusinessException}); an unrecognized value
     * in either is also rejected rather than silently returning an empty/misleading page.
     */
    public List<WorkflowRun> findRuns(String workflowId, List<String> status, String state, Pageable pageable) {
        if (state != null && status != null && !status.isEmpty()) {
            throw new BusinessException("state and status cannot both be specified");
        }
        if (state != null) {
            return switch (state) {
                case "queued" -> runRepository.findQueuedByWorkflowId(workflowId, QUEUED_STATE_STATUSES,
                        WorkflowJobStatus.AWAITING_PICKUP, WorkflowRunStatus.TERMINAL_STATUSES, pageable)
                        .getContent();
                case "running" -> runRepository.findRunningByWorkflowId(workflowId, RUNNING_STATE_STATUSES,
                        WorkflowJobStatus.AWAITING_PICKUP, pageable).getContent();
                default -> throw new BusinessException("Unrecognized state: " + state);
            };
        }
        if (status != null && !status.isEmpty()) {
            Set<WorkflowRunStatus> statuses = parseRunStatuses(status);
            return runRepository.findByWorkflowIdAndStatusIn(workflowId, statuses, pageable).getContent();
        }
        return runRepository.findByWorkflowId(workflowId, pageable).getContent();
    }

    /** Rejects an unrecognized status value with 400 (via {@link BusinessException}) instead of letting
     *  a typo silently fall through to an empty/misleading result set. */
    private Set<WorkflowRunStatus> parseRunStatuses(List<String> status) {
        Set<WorkflowRunStatus> statuses = new HashSet<>();
        for (String value : status) {
            try {
                statuses.add(WorkflowRunStatus.valueOf(value));
            } catch (IllegalArgumentException e) {
                throw new BusinessException("Unrecognized run status: " + value);
            }
        }
        return statuses;
    }

    /**
     * Batched — one query for the whole page rather than one per run — derivation of
     * {@code WorkflowRunDto.waitReason}. Only non-terminal runs can have a wait reason, so terminal
     * runs are excluded from the lookup entirely. A job in AWAITING_PICKUP that's already been claimed
     * by a self-hosted daemon is actively running, not waiting — {@code claimedAt IS NULL} in the
     * repository query is what actually distinguishes the two, since status alone doesn't change for
     * the rest of that job's execution.
     */
    public Map<String, String> deriveWaitReasons(List<WorkflowRun> runs) {
        List<String> nonTerminalRunIds = runs.stream()
                .filter(run -> !run.getStatus().isTerminal())
                .map(WorkflowRun::getId)
                .toList();
        if (nonTerminalRunIds.isEmpty()) {
            return Map.of();
        }
        List<String> awaitingRunnerRunIds = jobRunRepository.findDistinctRunIdsByRunIdInAndStatusAndClaimedAtIsNull(
                nonTerminalRunIds, WorkflowJobStatus.AWAITING_PICKUP);
        return awaitingRunnerRunIds.stream()
                .collect(Collectors.toMap(runId -> runId, runId -> "AWAITING_RUNNER"));
    }
}
