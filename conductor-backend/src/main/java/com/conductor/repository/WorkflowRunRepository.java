package com.conductor.repository;

import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, String> {

    List<WorkflowRun> findByWorkflowIdOrderByStartedAtDesc(String workflowId);

    Page<WorkflowRun> findByWorkflowId(String workflowId, Pageable pageable);

    List<WorkflowRun> findByStatusIn(Collection<WorkflowRunStatus> statuses);
}
