package com.conductor.repository;

import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * Row-level lock on a single run, used to serialize concurrent completion signals (daemon
     * complete, legacy PATCH shim, pickup-timeout sweep) that could otherwise race past the
     * terminal-status guard in {@code WorkflowJobOrchestrator}.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from WorkflowRun r where r.id = :id")
    Optional<WorkflowRun> findByIdForUpdate(@Param("id") String id);

    /**
     * Status-only projection, polled between steps by the non-transactional job loop so a cancellation
     * lands mid-job without loading (and holding) the whole run entity.
     */
    @Query("SELECT r.status FROM WorkflowRun r WHERE r.id = :id")
    Optional<WorkflowRunStatus> findStatusById(@Param("id") String id);
}
