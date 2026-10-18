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

    /** Columns the sidebar nav needs, without the heavy yaml / definition / webhook_token. */
    interface SidebarRow {
        String getId();
        String getName();
        String getSlug();
        String getNoun();
        String getArea();
        String getTag();
        String getState();
        Integer getVersion();
        Integer getSchemaVersion();
        Boolean getEnabled();
        Boolean getSidebarEnabled();
        java.time.Instant getCreatedAt();
        java.time.Instant getUpdatedAt();
        Integer getConsecutiveFailures();
    }

    /**
     * Lifecycle workflows for the sidebar, filtered in SQL: a lifecycle row has a non-empty {@code definition}
     * (see {@code WorkflowDefinition#isLifecycle}). Slug and noun come out of the JSON in the query so the
     * statechart itself is never loaded or parsed. {@code state} null = any, matched case-insensitively.
     */
    @Query(value = """
            SELECT w.id AS id, w.name AS name, w.definition ->> 'id' AS slug, w.definition ->> 'noun' AS noun,
                   w.area AS area, w.tag AS tag, w.state AS state, w.version AS version,
                   w.schema_version AS schemaVersion, w.enabled AS enabled, w.sidebar_enabled AS sidebarEnabled,
                   w.created_at AS createdAt, w.updated_at AS updatedAt,
                   w.consecutive_failures AS consecutiveFailures
            FROM workflow_definitions w
            WHERE w.project_id = :projectId
              AND w.definition IS NOT NULL AND w.definition <> '{}'::jsonb
              AND w.sidebar_enabled = TRUE
              AND (CAST(:state AS TEXT) IS NULL OR UPPER(w.state) = UPPER(CAST(:state AS TEXT)))
            ORDER BY w.created_at
            """, nativeQuery = true)
    List<SidebarRow> findSidebarLifecycleRows(@Param("projectId") String projectId, @Param("state") String state);

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
