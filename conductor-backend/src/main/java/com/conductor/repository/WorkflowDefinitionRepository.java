package com.conductor.repository;

import com.conductor.entity.WorkflowDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, String> {

    List<WorkflowDefinition> findByProjectId(String projectId);

    long countByProjectId(String projectId);

    Optional<WorkflowDefinition> findByProjectIdAndName(String projectId, String name);

    Optional<WorkflowDefinition> findByWebhookToken(String webhookToken);
}
