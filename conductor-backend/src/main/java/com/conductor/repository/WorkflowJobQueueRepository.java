package com.conductor.repository;

import com.conductor.entity.WorkflowJobQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowJobQueueRepository extends JpaRepository<WorkflowJobQueue, String> {

    /**
     * Claimed rows whose job never reached {@code planJobExecution} — that method is what creates the
     * {@code workflow_job_runs} row, and it runs after the claim, so a claim marked and then lost to a
     * restart leaves a row that no query would ever return again: {@link
     * #claimUnclaimedByRunIdAndJobId} filters on {@code claimed_at IS NULL}, and the stuck-job sweep
     * looks for RUNNING {@code workflow_job_runs} that in this case don't exist. Used at startup to
     * re-open them.
     */
    @Query(value = "SELECT q.* FROM workflow_job_queue q WHERE q.claimed_at IS NOT NULL "
            + "AND NOT EXISTS (SELECT 1 FROM workflow_job_runs jr "
            + "WHERE jr.run_id = q.run_id AND jr.job_id = q.job_id)", nativeQuery = true)
    List<WorkflowJobQueue> findClaimedWithoutJobRun();

    /**
     * Claims the unclaimed queue row(s) for this (run, job), if any still exist — the atomic claim
     * behind the Cloud-Tasks-triggered dispatch path (see {@code WorkflowExecutionEngine#claimQueuedJob}).
     * Returns the number of rows claimed: 0 means someone else — a duplicate Cloud Tasks delivery
     * (at-least-once) — already claimed it, so the caller should treat this as a safe no-op rather
     * than executing the job again. Bulk by design, not just by accident: {@code enqueueJob}'s dedup is
     * itself best-effort (see its own comment), so if that race is ever lost and two unclaimed rows
     * exist for the same (run, job), this claims both in one statement rather than leaving a second
     * row behind unclaimed — only one {@code processJob} call ever results from a single {@code
     * claimQueuedJob} invocation regardless of how many rows it claimed.
     */
    @Modifying
    @Query(value = "UPDATE workflow_job_queue SET claimed_at = NOW() "
            + "WHERE run_id = :runId AND job_id = :jobId AND claimed_at IS NULL", nativeQuery = true)
    int claimUnclaimedByRunIdAndJobId(@Param("runId") String runId, @Param("jobId") String jobId);

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
