package com.conductor.repository;

import com.conductor.entity.WorkItemStepRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkItemStepRunRepository extends JpaRepository<WorkItemStepRun, String> {

    List<WorkItemStepRun> findAllByWorkItemIdOrderByCreatedAtDesc(String workItemId);
}
