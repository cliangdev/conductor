package com.conductor.repository;

import com.conductor.entity.WorkflowStepRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowStepRunRepository extends JpaRepository<WorkflowStepRun, String> {

    List<WorkflowStepRun> findByJobRunId(String jobRunId);

    /**
     * Same rows as {@link #findByJobRunId}, in deterministic execution order (started_at, then id as
     * a tiebreaker for same-millisecond starts). Used wherever multiple steps' outputs are merged —
     * later step wins, in execution order — so the merge result doesn't depend on JPA's unspecified
     * default ordering.
     */
    List<WorkflowStepRun> findByJobRunIdOrderByStartedAtAscIdAsc(String jobRunId);

    Optional<WorkflowStepRun> findByJobRunIdAndStepId(String jobRunId, String stepId);

    Optional<WorkflowStepRun> findByJobRunIdAndWorkerJobId(String jobRunId, String workerJobId);

    /**
     * The first failure reason a run recorded, in one round trip: a step's {@code error_reason} (earliest
     * first) wins over a job's {@code skip_reason}. Empty when nothing was recorded.
     */
    @Query(value = "SELECT reason FROM ("
            + " SELECT sr.error_reason AS reason, 0 AS pri, sr.started_at AS at FROM workflow_step_runs sr"
            + "   JOIN workflow_job_runs jr ON jr.id = sr.job_run_id"
            + "   WHERE jr.run_id = :runId AND sr.error_reason IS NOT NULL AND sr.error_reason <> ''"
            + " UNION ALL"
            + " SELECT jr.skip_reason, 1, jr.started_at FROM workflow_job_runs jr"
            + "   WHERE jr.run_id = :runId AND jr.skip_reason IS NOT NULL AND jr.skip_reason <> ''"
            + ") t ORDER BY pri, at NULLS LAST LIMIT 1", nativeQuery = true)
    List<String> findFirstFailureReasonByRunId(@Param("runId") String runId);
}
