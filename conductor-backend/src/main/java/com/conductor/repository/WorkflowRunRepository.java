package com.conductor.repository;

import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, String> {

    List<WorkflowRun> findByWorkflowIdOrderByStartedAtDesc(String workflowId);

    Page<WorkflowRun> findByWorkflowId(String workflowId, Pageable pageable);

    Page<WorkflowRun> findByWorkflowIdAndStatusIn(String workflowId, Collection<WorkflowRunStatus> statuses,
                                                   Pageable pageable);

    List<WorkflowRun> findByStatusIn(Collection<WorkflowRunStatus> statuses);

    List<WorkflowRun> findByWorkflowIdAndStatusIn(String workflowId, Collection<WorkflowRunStatus> statuses);

    List<WorkflowRun> findByStatusAndStartedAtBefore(WorkflowRunStatus status, OffsetDateTime cutoff);

    @Query("SELECT r FROM WorkflowRun r JOIN FETCH r.workflow WHERE r.id = :id")
    Optional<WorkflowRun> findByIdWithWorkflow(@Param("id") String id);

    /**
     * Row-level lock on a single run, used to serialize concurrent completion signals (daemon
     * complete, legacy PATCH shim, pickup-timeout sweep) that could otherwise race past the
     * terminal-status guard in {@code WorkflowJobOrchestrator}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from WorkflowRun r where r.id = :id")
    Optional<WorkflowRun> findByIdForUpdate(@Param("id") String id);

    /**
     * Status-only projection, polled between steps by the non-transactional job loop so a cancellation
     * lands mid-job without loading (and holding) the whole run entity.
     */
    @Query("SELECT r.status FROM WorkflowRun r WHERE r.id = :id")
    Optional<WorkflowRunStatus> findStatusById(@Param("id") String id);

    /**
     * Backs {@code ?state=queued} on {@code listWorkflowRuns}: a run is "queued" either at the run
     * level ({@code queuedStatuses}, typically PENDING/PENDING_LOCAL_PICKUP) or because it's blocked
     * on a self-hosted job nobody has claimed yet ({@code awaitingPickup} status with {@code
     * claimedAt IS NULL}) — the latter catches a run that's already flipped to RUNNING at the run
     * level (see {@code WorkflowJobOrchestrator#planJobExecution}) purely because a self-hosted job
     * hasn't been picked up. Filtered and sorted in SQL via {@code pageable} so pagination isn't
     * corrupted by an in-Java re-filter after the page is drawn.
     */
    @Query("SELECT r FROM WorkflowRun r WHERE r.workflow.id = :workflowId AND (r.status IN :queuedStatuses "
            + "OR EXISTS (SELECT 1 FROM WorkflowJobRun j WHERE j.run = r AND j.status = :awaitingPickup "
            + "AND j.claimedAt IS NULL))")
    Page<WorkflowRun> findQueuedByWorkflowId(@Param("workflowId") String workflowId,
                                              @Param("queuedStatuses") Collection<WorkflowRunStatus> queuedStatuses,
                                              @Param("awaitingPickup") WorkflowJobStatus awaitingPickup,
                                              Pageable pageable);

    /**
     * Backs {@code ?state=running}: the complement of {@link #findQueuedByWorkflowId} within {@code
     * runningStatuses} (typically RUNNING/CANCELLING) — a run counts as actually running only if it's
     * NOT also blocked on an unclaimed self-hosted job.
     */
    @Query("SELECT r FROM WorkflowRun r WHERE r.workflow.id = :workflowId AND r.status IN :runningStatuses "
            + "AND NOT EXISTS (SELECT 1 FROM WorkflowJobRun j WHERE j.run = r AND j.status = :awaitingPickup "
            + "AND j.claimedAt IS NULL)")
    Page<WorkflowRun> findRunningByWorkflowId(@Param("workflowId") String workflowId,
                                               @Param("runningStatuses") Collection<WorkflowRunStatus> runningStatuses,
                                               @Param("awaitingPickup") WorkflowJobStatus awaitingPickup,
                                               Pageable pageable);

    /**
     * Backs {@link com.conductor.workflow.WorkflowRunCancellationService#cancelQueuedRuns}: the set of
     * runs the bulk "cancel queued" action should actually touch. Wider than a plain PENDING filter —
     * it also picks up a run blocked on an unclaimed self-hosted job — but deliberately narrower than
     * {@link #findQueuedByWorkflowId}: a run with any {@code RUNNING} job, or any {@code
     * AWAITING_PICKUP} job that's already been claimed (i.e. actively executing on a daemon), is
     * excluded even if some other job on it is still an unclaimed AWAITING_PICKUP — cancellation must
     * never touch a run with genuinely in-flight work, unlike the display-only queued/running split.
     */
    @Query("SELECT r FROM WorkflowRun r WHERE r.workflow.id = :workflowId AND (r.status = :pendingStatus "
            + "OR (EXISTS (SELECT 1 FROM WorkflowJobRun j WHERE j.run = r AND j.status = :awaitingPickup "
            + "AND j.claimedAt IS NULL) "
            + "AND NOT EXISTS (SELECT 1 FROM WorkflowJobRun j2 WHERE j2.run = r AND (j2.status = :runningJobStatus "
            + "OR (j2.status = :awaitingPickup AND j2.claimedAt IS NOT NULL)))))")
    List<WorkflowRun> findQueuedForCancellationByWorkflowId(@Param("workflowId") String workflowId,
                                                             @Param("pendingStatus") WorkflowRunStatus pendingStatus,
                                                             @Param("awaitingPickup") WorkflowJobStatus awaitingPickup,
                                                             @Param("runningJobStatus") WorkflowJobStatus runningJobStatus);
}
