package com.conductor.repository;

import com.conductor.entity.WorkflowScheduleSkip;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowScheduleSkipRepository extends JpaRepository<WorkflowScheduleSkip, String> {

    List<WorkflowScheduleSkip> findByScheduleId(String scheduleId);

    List<WorkflowScheduleSkip> findByScheduleIdOrderBySkippedAtDesc(String scheduleId);
}
