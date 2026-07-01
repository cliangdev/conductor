package com.conductor.repository;

import com.conductor.entity.StepRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StepRunRepository extends JpaRepository<StepRun, String> {

    List<StepRun> findAllByWorkItemIdOrderByCreatedAtDesc(String workItemId);
}
