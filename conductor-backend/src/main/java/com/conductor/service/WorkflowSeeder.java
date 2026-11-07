package com.conductor.service;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowDefinitionVersion;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Seeds the built-in lifecycle Workflows as real DB rows for a project — ENGINEERING (COND-22),
 * MARKETING (COND-23), MARKETING_CHANGES, CREATORS and TASK (the sidebar-hidden lifecycle of child Work Items).
 *
 * <p>Existing projects are seeded by the {@code V74} Flyway migration; this is the runtime path for
 * <em>new</em> projects, invoked from {@link ProjectService} on workspace creation. Both read the same
 * canonical statechart JSON ({@code schema/examples/*.workflow.json}) so there is a single
 * source of truth. Idempotent per (project, definition slug): a project that already has a header for a
 * slug is left untouched, so the two seeds never disturb each other.
 */
@Service
public class WorkflowSeeder {

    static final String ENGINEERING_SLUG = "ENGINEERING";
    static final String MARKETING_SLUG = "MARKETING";
    static final String MARKETING_CHANGES_SLUG = "MARKETING_CHANGES";
    static final String TASK_SLUG = "TASK";
    static final String CREATORS_SLUG = "CREATORS";
    private static final String ENGINEERING_RESOURCE = "schema/examples/engineering.workflow.json";
    private static final String MARKETING_RESOURCE = "schema/examples/marketing.workflow.json";

    private static final String MARKETING_CHANGES_RESOURCE = "schema/examples/marketing-changes.workflow.json";
    private static final String TASK_RESOURCE = "schema/examples/task.workflow.json";
    private static final String CREATORS_RESOURCE = "schema/examples/creators.workflow.json";

    private final WorkflowDefinitionRepository workflowRepository;
    private final WorkflowDefinitionVersionRepository versionRepository;
    private final JsonNode engineeringDefinition;
    private final JsonNode marketingDefinition;
    private final JsonNode marketingChangesDefinition;
    private final JsonNode taskDefinition;
    private final JsonNode creatorsDefinition;

    public WorkflowSeeder(WorkflowDefinitionRepository workflowRepository,
                          WorkflowDefinitionVersionRepository versionRepository,
                          ObjectMapper objectMapper) {
        this.workflowRepository = workflowRepository;
        this.versionRepository = versionRepository;
        this.engineeringDefinition = loadDefinition(objectMapper, ENGINEERING_RESOURCE);
        this.marketingDefinition = loadDefinition(objectMapper, MARKETING_RESOURCE);
        this.marketingChangesDefinition = loadDefinition(objectMapper, MARKETING_CHANGES_RESOURCE);
        this.taskDefinition = loadDefinition(objectMapper, TASK_RESOURCE);
        this.creatorsDefinition = loadDefinition(objectMapper, CREATORS_RESOURCE);
    }

    /**
     * Inserts the ENGINEERING header + its v1 published snapshot for the project, unless one already
     * exists. Runs in the caller's transaction.
     */
    public void seedEngineering(Project project) {
        seed(project, ENGINEERING_SLUG, engineeringDefinition);
    }

    /**
     * Inserts the MARKETING header + its v1 published snapshot for the project, unless one already
     * exists. Runs in the caller's transaction.
     */
    public void seedMarketing(Project project) {
        seed(project, MARKETING_SLUG, marketingDefinition);
    }

    /**
     * Inserts the MARKETING_CHANGES header (a log of marketing changes and their reviewed outcomes) + its
     * v1 published snapshot for the project, unless one already exists. Hidden from the sidebar: Changes are
     * reached from the open-Changes card on Marketing Overview (or /marketing/changes) and written via MCP and
     * the report skill. Runs in the caller's transaction.
     */
    public void seedMarketingChanges(Project project) {
        seed(project, MARKETING_CHANGES_SLUG, marketingChangesDefinition, false);
    }

    /**
     * Inserts the TASK header (the lifecycle of child Work Items) + its v1 published snapshot for the project,
     * unless one already exists. Hidden from the sidebar: tasks are reached from their parent, not from
     * navigation. Runs in the caller's transaction.
     */
    public void seedTask(Project project) {
        seed(project, TASK_SLUG, taskDefinition, false);
    }

    /**
     * Inserts the CREATORS header (the creator pipeline: prospect, briefed, posted, paid out) + its v1
     * published snapshot for the project, unless one already exists. Runs in the caller's transaction.
     */
    public void seedCreators(Project project) {
        seed(project, CREATORS_SLUG, creatorsDefinition);
    }

    private void seed(Project project, String slug, JsonNode definition) {
        seed(project, slug, definition, true);
    }

    private void seed(Project project, String slug, JsonNode definition, boolean sidebarEnabled) {
        // Guard on the statechart slug (definition->>'id') — the identity the resolver and sidebar key on —
        // not the human-label `name` (they coincide for the built-ins but diverge for authored workflows).
        if (workflowRepository.existsByProjectIdAndDefinitionSlug(project.getId(), slug)) {
            return;
        }

        WorkflowDefinition header = new WorkflowDefinition();
        header.setProject(project);
        header.setName(slug);
        header.setDefinition(definition);
        header.setVersion(1);
        header.setState("PUBLISHED");
        header.setArea(areaOf(definition, slug));
        header.setSchemaVersion(1);
        header.setSidebarEnabled(sidebarEnabled);
        header.setEnabled(true);
        WorkflowDefinition saved = workflowRepository.save(header);

        WorkflowDefinitionVersion snapshot = new WorkflowDefinitionVersion();
        snapshot.setWorkflowDefinition(saved);
        snapshot.setVersion(1);
        snapshot.setDefinition(definition);
        snapshot.setSchemaVersion(1);
        versionRepository.save(snapshot);
    }

    /** The nav-grouping area: the definition's own {@code area}, falling back to the slug only if absent. */
    private static String areaOf(JsonNode definition, String slug) {
        JsonNode area = definition.get("area");
        return area != null && area.isTextual() && !area.asText().isBlank() ? area.asText() : slug;
    }

    // NOTE: this seeder (and the V74 backfill migration) writes workflow_definitions directly, bypassing
    // WorkflowDefinitionValidator. Since #240 loosened the schema `trigger` field from a closed enum to an open
    // string (registry-enforced only on the publish path), a typo'd/unregistered trigger in a seeded or
    // migrated definition would persist silently and simply never fire. The shipped ENGINEERING/MARKETING
    // resources are trusted + covered by WorkflowDefinitionValidatorTest and WorkflowSeederTest, but any future
    // seed/import here should stay in sync with SystemTriggerRegistry.
    private static JsonNode loadDefinition(ObjectMapper objectMapper, String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + resource, e);
        }
    }
}
