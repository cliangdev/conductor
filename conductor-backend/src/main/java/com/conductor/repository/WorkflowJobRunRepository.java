package com.conductor.repository;

import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowJobRunRepository extends JpaRepository<WorkflowJobRun, String> {

    List<WorkflowJobRun> findByRunId(String runId);

    Optional<WorkflowJobRun> findByRunIdAndJobId(String runId, String jobId);

    List<WorkflowJobRun> findByStatus(WorkflowJobStatus status);
}
