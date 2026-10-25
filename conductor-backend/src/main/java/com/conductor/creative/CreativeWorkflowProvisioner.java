package com.conductor.creative;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Seeds the {@code conductor-creative-render} system workflow (classpath {@code creative/creative-render.yaml}),
 * lazily on the first dispatch. Mirrors {@code KnowledgeWorkflowProvisioner}: idempotent, drifted YAML is
 * refreshed in place. A workflow with that name that does not carry the marker comment is the user's own
 * and is never overwritten.
 */
@Service
public class CreativeWorkflowProvisioner {

    private static final Logger log = LoggerFactory.getLogger(CreativeWorkflowProvisioner.class);

    public static final String RENDER_WORKFLOW_NAME = "conductor-creative-render";
    static final String RENDER_RESOURCE = "/creative/creative-render.yaml";
    /** First line of the system workflow's YAML: how a seeded workflow is told from a user's namesake. */
    public static final String MARKER = "# conductor-system-workflow: creative-render";

    private final WorkflowDefinitionRepository workflowRepository;
    private final ProjectRepository projectRepository;

    public CreativeWorkflowProvisioner(WorkflowDefinitionRepository workflowRepository,
                                       ProjectRepository projectRepository) {
        this.workflowRepository = workflowRepository;
        this.projectRepository = projectRepository;
    }

    /** True if {@code yaml} is a seeded system workflow (marker on its first line). */
    public static boolean hasMarker(String yaml) {
        if (yaml == null) {
            return false;
        }
        int eol = yaml.indexOf('\n');
        String first = (eol < 0 ? yaml : yaml.substring(0, eol)).strip();
        return first.equals(MARKER);
    }

    /**
     * The project's render workflow, created if absent and refreshed if its YAML drifted.
     *
     * @throws NameTakenException when a user-authored workflow already has the name
     */
    @Transactional
    public WorkflowDefinition ensureProvisioned(String projectId) {
        String classpathYaml = readResource();
        Optional<WorkflowDefinition> existing = workflowRepository.findByProjectIdAndName(projectId, RENDER_WORKFLOW_NAME);
        if (existing.isPresent()) {
            WorkflowDefinition def = existing.get();
            if (!hasMarker(def.getYaml())) {
                throw new NameTakenException(def.getId());
            }
            if (!classpathYaml.equals(def.getYaml())) {
                def.setYaml(classpathYaml);
                def = workflowRepository.save(def);
                log.info("Refreshed drifted system workflow '{}' YAML for project {}", RENDER_WORKFLOW_NAME, projectId);
            }
            return def;
        }
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));
        WorkflowDefinition def = new WorkflowDefinition();
        def.setProject(project);
        def.setName(RENDER_WORKFLOW_NAME);
        def.setYaml(classpathYaml);
        def.setEnabled(true);
        def.setArea("marketing");
        def = workflowRepository.save(def);
        log.info("Provisioned system workflow '{}' for project {}", RENDER_WORKFLOW_NAME, projectId);
        return def;
    }

    /** A user's own workflow already uses the system workflow's name. */
    public static class NameTakenException extends RuntimeException {
        private final String workflowId;

        public NameTakenException(String workflowId) {
            super("A workflow named '" + RENDER_WORKFLOW_NAME + "' already exists and is not Conductor's render workflow");
            this.workflowId = workflowId;
        }

        public String workflowId() {
            return workflowId;
        }
    }

    static String readResource() {
        try (InputStream in = CreativeWorkflowProvisioner.class.getResourceAsStream(RENDER_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + RENDER_RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + RENDER_RESOURCE, e);
        }
    }
}
