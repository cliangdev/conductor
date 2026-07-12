package com.conductor.repository;

import com.conductor.entity.WorkflowStepRun;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
