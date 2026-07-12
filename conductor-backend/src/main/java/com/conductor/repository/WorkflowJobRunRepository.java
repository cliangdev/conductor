package com.conductor.repository;

import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowJobRunRepository extends JpaRepository<WorkflowJobRun, String> {

    List<WorkflowJobRun> findByRunId(String runId);

    Optional<WorkflowJobRun> findByRunIdAndJobId(String runId, String jobId);

    List<WorkflowJobRun> findByStatus(WorkflowJobStatus status);

    @Query(value = "SELECT * FROM workflow_job_runs WHERE status = :status AND started_at < :cutoff",
           nativeQuery = true)
    List<WorkflowJobRun> findByStatusAndStartedAtBefore(@Param("status") String status, @Param("cutoff") OffsetDateTime cutoff);

    /** Returns the job run with the highest iteration for a given run+job (for loop jobs). */
    @Query("SELECT jr FROM WorkflowJobRun jr WHERE jr.run.id = :runId AND jr.jobId = :jobId ORDER BY jr.iteration DESC")
    List<WorkflowJobRun> findByRunIdAndJobIdOrderByIterationDesc(@Param("runId") String runId, @Param("jobId") String jobId);
}
