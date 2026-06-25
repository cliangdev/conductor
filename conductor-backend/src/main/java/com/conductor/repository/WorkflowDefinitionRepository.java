package com.conductor.repository;

import com.conductor.entity.WorkflowDefinition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, String> {

    List<WorkflowDefinition> findByProjectId(String projectId);

    long countByProjectId(String projectId);

    Optional<WorkflowDefinition> findByProjectIdAndName(String projectId, String name);

    Optional<WorkflowDefinition> findByWebhookToken(String webhookToken);

    /**
     * COND-18: the latest PUBLISHED definition in a project whose statechart slug (the
     * {@code definition->>'id'} field) matches {@code slug}. This is the DB-first half of
     * {@code WorkflowDefinitionResolver}; when absent, the resolver falls back to a built-in
     * classpath definition. Native because the slug lives inside the JSONB document.
     */
    @Query(value = """
            SELECT * FROM workflow_definitions w
            WHERE w.project_id = :projectId
              AND w.state = 'PUBLISHED'
              AND w.definition ->> 'id' = :slug
            ORDER BY w.version DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<WorkflowDefinition> findLatestPublishedBySlug(@Param("projectId") String projectId,
                                                           @Param("slug") String slug);
}
