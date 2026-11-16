package com.conductor.comment;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Seeds the {@code conductor-comment-agent} system workflow (classpath {@code comments/comment-agent.yaml}),
 * lazily on the first {@code @agent} mention in a project. Same shape as {@code CreativeWorkflowProvisioner}:
 * idempotent, drifted YAML (an older release's copy) is refreshed in place, and a workflow with that name that
 * lacks the marker comment is the user's own and is never overwritten.
 */
@Service
public class CommentAgentWorkflowProvisioner {

    private static final Logger log = LoggerFactory.getLogger(CommentAgentWorkflowProvisioner.class);

    public static final String WORKFLOW_NAME = "conductor-comment-agent";
    static final String RESOURCE = "/comments/comment-agent.yaml";
    /** First line of the system workflow's YAML: how a seeded workflow is told from a user's namesake. */
    public static final String MARKER = "# conductor-system-workflow: comment-agent";

    private final WorkflowDefinitionRepository workflowRepository;
    private final ProjectRepository projectRepository;
    /** Inserts commit on their own, so a lost race on the unique (project_id, name) index poisons nothing. */
    private final TransactionTemplate insertTx;

    public CommentAgentWorkflowProvisioner(WorkflowDefinitionRepository workflowRepository,
                                           ProjectRepository projectRepository,
                                           PlatformTransactionManager transactionManager) {
        this.workflowRepository = workflowRepository;
        this.projectRepository = projectRepository;
        this.insertTx = new TransactionTemplate(transactionManager);
        this.insertTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
     * The project's comment-agent workflow, created if absent and refreshed if its YAML drifted.
     *
     * @throws NameTakenException when a user-authored workflow already has the name
     */
    @Transactional
    public WorkflowDefinition ensure(String projectId) {
        String classpathYaml = readResource();
        Optional<WorkflowDefinition> existing = workflowRepository.findByProjectIdAndName(projectId, WORKFLOW_NAME);
        if (existing.isPresent()) {
            WorkflowDefinition def = existing.get();
            if (!hasMarker(def.getYaml())) {
                throw new NameTakenException(def.getId());
            }
            if (!classpathYaml.equals(def.getYaml())) {
                def.setYaml(classpathYaml);
                def = workflowRepository.save(def);
                log.info("Refreshed drifted system workflow '{}' YAML for project {}", WORKFLOW_NAME, projectId);
            }
            return def;
        }
        try {
            WorkflowDefinition created = insertTx.execute(status -> insert(projectId, classpathYaml));
            log.info("Provisioned system workflow '{}' for project {}", WORKFLOW_NAME, projectId);
            return created;
        } catch (DataIntegrityViolationException raced) {
            // Another first dispatch inserted it between our read and our insert: use theirs.
            WorkflowDefinition winner = workflowRepository.findByProjectIdAndName(projectId, WORKFLOW_NAME)
                    .orElseThrow(() -> raced);
            if (!hasMarker(winner.getYaml())) {
                throw new NameTakenException(winner.getId());
            }
            return winner;
        }
    }

    private WorkflowDefinition insert(String projectId, String yaml) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found"));
        WorkflowDefinition def = new WorkflowDefinition();
        def.setProject(project);
        def.setName(WORKFLOW_NAME);
        def.setYaml(yaml);
        def.setEnabled(true);
        return workflowRepository.saveAndFlush(def);
    }

    /** A user's own workflow already uses the system workflow's name. */
    public static class NameTakenException extends RuntimeException {
        private final String workflowId;

        public NameTakenException(String workflowId) {
            super("A workflow named '" + WORKFLOW_NAME + "' already exists and is not Conductor's comment-agent workflow");
            this.workflowId = workflowId;
        }

        public String workflowId() {
            return workflowId;
        }
    }

    static String readResource() {
        try (InputStream in = CommentAgentWorkflowProvisioner.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read " + RESOURCE, e);
        }
    }
}
