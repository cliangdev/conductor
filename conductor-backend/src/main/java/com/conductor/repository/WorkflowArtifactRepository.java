package com.conductor.repository;

import com.conductor.entity.WorkflowArtifact;
import com.conductor.entity.WorkflowArtifactStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WorkflowArtifactRepository extends JpaRepository<WorkflowArtifact, String> {

    Optional<WorkflowArtifact> findByRunIdAndName(String runId, String name);

    List<WorkflowArtifact> findByRunIdAndJobIdAndStatus(String runId, String jobId, WorkflowArtifactStatus status);
}
