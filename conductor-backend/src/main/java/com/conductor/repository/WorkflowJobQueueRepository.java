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

    @Query(value = "SELECT * FROM workflow_job_queue WHERE claimed_at IS NULL ORDER BY created_at FOR UPDATE SKIP LOCKED", nativeQuery = true)
    List<WorkflowJobQueue> claimAllReadyJobs();

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
}
