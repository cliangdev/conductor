package com.conductor.repository;

import com.conductor.entity.WorkflowStepRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowStepRunRepository extends JpaRepository<WorkflowStepRun, String> {

    List<WorkflowStepRun> findByJobRunId(String jobRunId);

    Optional<WorkflowStepRun> findByJobRunIdAndStepId(String jobRunId, String stepId);
}
