package com.conductor.repository;

import com.conductor.entity.WorkflowDefinitionVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowDefinitionVersionRepository extends JpaRepository<WorkflowDefinitionVersion, String> {

    /**
     * A specific published snapshot of a Workflow in a project, addressed by its statechart slug
     * ({@code definition->>'id'}) and version. Backs version-pinned resolution for in-flight Work Items.
     */
    @Query(value = """
            SELECT v.* FROM workflow_definition_versions v
            JOIN workflow_definitions w ON w.id = v.workflow_definition_id
            WHERE w.project_id = :projectId
              AND v.definition ->> 'id' = :slug
              AND v.version = :version
            LIMIT 1
            """, nativeQuery = true)
    Optional<WorkflowDefinitionVersion> findByProjectSlugAndVersion(@Param("projectId") String projectId,
                                                                    @Param("slug") String slug,
                                                                    @Param("version") Integer version);

    /**
     * The latest published snapshot of a Workflow in a project (highest version). Guards on the header's
     * {@code state = 'PUBLISHED'} so a future unpublish stops the resolver from handing out a revoked
     * definition (today snapshots are only written on publish, so this is defensive).
     */
    @Query(value = """
            SELECT v.* FROM workflow_definition_versions v
            JOIN workflow_definitions w ON w.id = v.workflow_definition_id
            WHERE w.project_id = :projectId
              AND w.state = 'PUBLISHED'
              AND v.definition ->> 'id' = :slug
            ORDER BY v.version DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<WorkflowDefinitionVersion> findLatestPublished(@Param("projectId") String projectId,
                                                            @Param("slug") String slug);

    /** All published snapshots for a workflow definition header, newest first. */
    List<WorkflowDefinitionVersion> findByWorkflowDefinitionIdOrderByVersionDesc(String workflowDefinitionId);
}
