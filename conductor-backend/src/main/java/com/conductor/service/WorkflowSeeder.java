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
 * Seeds the built-in ENGINEERING lifecycle Workflow as real DB rows for a project (COND-22).
 *
 * <p>Existing projects are seeded by the {@code V74} Flyway migration; this is the runtime path for
 * <em>new</em> projects, invoked from {@link ProjectService} on workspace creation. Both read the same
 * canonical statechart JSON ({@code schema/examples/engineering.workflow.json}) so there is a single
 * source of truth. Idempotent: a project that already has an ENGINEERING header is left untouched.
 */
@Service
public class WorkflowSeeder {

    static final String ENGINEERING_SLUG = "ENGINEERING";
    private static final String ENGINEERING_RESOURCE = "schema/examples/engineering.workflow.json";

    private final WorkflowDefinitionRepository workflowRepository;
    private final WorkflowDefinitionVersionRepository versionRepository;
    private final JsonNode engineeringDefinition;

    public WorkflowSeeder(WorkflowDefinitionRepository workflowRepository,
                          WorkflowDefinitionVersionRepository versionRepository,
                          ObjectMapper objectMapper) {
        this.workflowRepository = workflowRepository;
        this.versionRepository = versionRepository;
        this.engineeringDefinition = loadEngineeringDefinition(objectMapper);
    }

    /**
     * Inserts the ENGINEERING header + its v1 published snapshot for the project, unless one already
     * exists. Runs in the caller's transaction.
     */
    public void seedEngineering(Project project) {
        if (workflowRepository.findByProjectIdAndName(project.getId(), ENGINEERING_SLUG).isPresent()) {
            return;
        }

        WorkflowDefinition header = new WorkflowDefinition();
        header.setProject(project);
        header.setName(ENGINEERING_SLUG);
        header.setDefinition(engineeringDefinition);
        header.setVersion(1);
        header.setState("PUBLISHED");
        header.setArea(ENGINEERING_SLUG);
        header.setSchemaVersion(1);
        header.setSidebarEnabled(true);
        header.setEnabled(true);
        WorkflowDefinition saved = workflowRepository.save(header);

        WorkflowDefinitionVersion snapshot = new WorkflowDefinitionVersion();
        snapshot.setWorkflowDefinition(saved);
        snapshot.setVersion(1);
        snapshot.setDefinition(engineeringDefinition);
        snapshot.setSchemaVersion(1);
        versionRepository.save(snapshot);
    }

    private static JsonNode loadEngineeringDefinition(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(ENGINEERING_RESOURCE).getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + ENGINEERING_RESOURCE, e);
        }
    }
}
