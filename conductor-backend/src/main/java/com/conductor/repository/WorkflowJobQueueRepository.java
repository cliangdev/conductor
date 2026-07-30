package com.conductor.repository;

import com.conductor.entity.WorkflowJobQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowJobQueueRepository extends JpaRepository<WorkflowJobQueue, String> {

    @Query(value = "SELECT * FROM workflow_job_queue WHERE claimed_at IS NULL ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<WorkflowJobQueue> claimNextJob();

    /**
     * Claims at most {@code limit} ready rows. The bound is load-bearing, not a tuning knob: a claimed
     * row is only ever re-driven by {@code WorkflowExecutionEngine#recoverOrphanedClaims} at startup, so
     * claiming more than the job executor can start right now widens the window in which a restart
     * strands work. Callers pass their free executor capacity — see {@code pollQueueOnce}.
     */
    @Query(value = "SELECT * FROM workflow_job_queue WHERE claimed_at IS NULL ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<WorkflowJobQueue> claimReadyJobs(@Param("limit") int limit);

    /**
     * Claimed rows whose job never reached {@code planJobExecution} — that method is what creates the
     * {@code workflow_job_runs} row, and it runs on the worker thread, so a claim marked in the poll
     * transaction and then lost to a restart leaves a row that no query would ever return again:
     * {@link #claimReadyJobs} filters on {@code claimed_at IS NULL}, and the stuck-job sweep looks for
     * RUNNING {@code workflow_job_runs} that in this case don't exist. Used at startup to re-open them.
     */
    @Query(value = "SELECT q.* FROM workflow_job_queue q WHERE q.claimed_at IS NOT NULL "
            + "AND NOT EXISTS (SELECT 1 FROM workflow_job_runs jr "
            + "WHERE jr.run_id = q.run_id AND jr.job_id = q.job_id)", nativeQuery = true)
    List<WorkflowJobQueue> findClaimedWithoutJobRun();

    @Modifying
    @Query(value = "UPDATE workflow_job_queue SET claimed_at = NOW() WHERE id = :id", nativeQuery = true)
    void markClaimed(@Param("id") String id);

    @Modifying
    @Query(value = "UPDATE workflow_job_queue SET claimed_at = NOW() WHERE id IN (:ids)", nativeQuery = true)
    void markAllClaimed(@Param("ids") List<String> ids);

    /**
     * Used by {@code WorkflowExecutionEngine#enqueueJob} to skip inserting a duplicate row when
     * one is already queued and unclaimed for this (run, job) — e.g. two upstream jobs completing
     * near-simultaneously both trying to enqueue the same diamond-`needs` dependent. Best-effort
     * only: without a DB unique partial index on (run_id, job_id) WHERE claimed_at IS NULL, two
     * concurrent callers can still both pass this check before either inserts.
     */
    List<WorkflowJobQueue> findByRunIdAndJobIdAndClaimedAtIsNull(String runId, String jobId);

    /**
     * Drops a cancelled run's not-yet-dispatched queue rows. Claimed rows are left alone — those are
     * already in flight and settle through the orchestrator's own cancellation checks instead.
     */
    @Modifying
    @Query("DELETE FROM WorkflowJobQueue q WHERE q.run.id = :runId AND q.claimedAt IS NULL")
    void deleteUnclaimedByRunId(@Param("runId") String runId);
}
