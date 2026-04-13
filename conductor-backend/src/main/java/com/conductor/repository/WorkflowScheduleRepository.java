package com.conductor.repository;

import com.conductor.entity.WorkflowSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface WorkflowScheduleRepository extends JpaRepository<WorkflowSchedule, String> {

    List<WorkflowSchedule> findByWorkflowId(String workflowId);

    List<WorkflowSchedule> findByEnabledTrueAndNextRunAtBefore(OffsetDateTime threshold);
}
