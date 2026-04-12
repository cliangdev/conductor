package com.conductor.repository;

import com.conductor.entity.WorkflowJobQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WorkflowJobQueueRepository extends JpaRepository<WorkflowJobQueue, String> {

    @Query(value = "SELECT * FROM workflow_job_queue WHERE claimed_at IS NULL ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<WorkflowJobQueue> claimNextJob();

    @Modifying
    @Query(value = "UPDATE workflow_job_queue SET claimed_at = NOW() WHERE id = :id", nativeQuery = true)
    void markClaimed(@Param("id") String id);
}
