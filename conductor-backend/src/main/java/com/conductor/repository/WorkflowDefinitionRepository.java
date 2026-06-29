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
     * Whether a project already has a workflow with the given statechart slug ({@code definition->>'id'}).
     * Keys on the slug — the same identity the resolver and sidebar use — rather than the human-label
     * {@code name}, which can diverge from the slug for user-authored lifecycle workflows.
     */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM workflow_definitions w
                WHERE w.project_id = :projectId AND w.definition ->> 'id' = :slug
            )
            """, nativeQuery = true)
    boolean existsByProjectIdAndDefinitionSlug(@Param("projectId") String projectId, @Param("slug") String slug);

    /**
     * The workflow in a project owning the given statechart slug ({@code definition->>'id'}), if any.
     * Used to detect slug collisions on update (the owning row may differ from the one being edited).
     */
    @Query(value = """
            SELECT * FROM workflow_definitions w
            WHERE w.project_id = :projectId AND w.definition ->> 'id' = :slug
            LIMIT 1
            """, nativeQuery = true)
    Optional<WorkflowDefinition> findByProjectIdAndDefinitionSlug(@Param("projectId") String projectId, @Param("slug") String slug);
}
