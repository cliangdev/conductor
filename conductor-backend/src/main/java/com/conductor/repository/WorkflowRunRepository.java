package com.conductor.repository;

import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, String> {

    List<WorkflowRun> findByWorkflowIdOrderByStartedAtDesc(String workflowId);

    Page<WorkflowRun> findByWorkflowId(String workflowId, Pageable pageable);

    List<WorkflowRun> findByStatusIn(Collection<WorkflowRunStatus> statuses);

    List<WorkflowRun> findByWorkflowIdAndStatusIn(String workflowId, Collection<WorkflowRunStatus> statuses);

    List<WorkflowRun> findByStatusAndStartedAtBefore(WorkflowRunStatus status, OffsetDateTime cutoff);

    @Query("SELECT r FROM WorkflowRun r JOIN FETCH r.workflow WHERE r.id = :id")
    Optional<WorkflowRun> findByIdWithWorkflow(@Param("id") String id);
}
