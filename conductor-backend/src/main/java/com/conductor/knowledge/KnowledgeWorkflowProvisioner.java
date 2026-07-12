package com.conductor.knowledge;

import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.knowledge.page.KnowledgePageRepository;
import com.conductor.knowledge.page.KnowledgePageService;
import com.conductor.knowledge.page.PageWrite;
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
import java.util.List;

/**
 * Idempotently provisions the two knowledge-domain system workflows (see
 * {@code src/main/resources/knowledge/*.yaml}) and the {@code _schema.md} seed page for a project the
 * first time knowledge is enabled -- called from {@code ProjectSettingsService} on the false->true
 * transition of {@code knowledge_enabled}.
 *
 * <p>{@link WorkflowDefinition} has no "system-managed" column (COND-18's lifecycle layer added one
 * for statecharts -- {@code sidebar_enabled} -- but nothing generic), so identity here is purely the
 * reserved workflow name: {@link #LIBRARIAN_WORKFLOW_NAME} / {@link #BOOTSTRAP_WORKFLOW_NAME}.
 * Provisioning writes {@link WorkflowDefinitionRepository} rows directly rather than going through
 * {@code WorkflowService.createWorkflow} -- same precedent as {@code V74__seed_engineering_workflow}
 * seeding the ENGINEERING lifecycle: a system seed isn't a user action subject to
 * {@code WorkflowService}'s admin-gate/quota/reserved-name checks.
 */
@Service
public class KnowledgeWorkflowProvisioner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeWorkflowProvisioner.class);

    public static final String LIBRARIAN_WORKFLOW_NAME = "knowledge-librarian";
    public static final String BOOTSTRAP_WORKFLOW_NAME = "knowledge-bootstrap";

    private static final String SCHEMA_PAGE_PATH = "_schema.md";
    private static final String LIBRARIAN_RESOURCE = "/knowledge/knowledge-librarian.yaml";
    private static final String BOOTSTRAP_RESOURCE = "/knowledge/knowledge-bootstrap.yaml";
    private static final String SCHEMA_RESOURCE = "/knowledge/_schema.md";
    private static final Actor PROVISIONER_ACTOR = new Actor("system", "knowledge-provisioner", null);

    private final WorkflowDefinitionRepository workflowRepository;
    private final ProjectRepository projectRepository;
    private final KnowledgePageRepository pageRepository;
    private final KnowledgePageService pageService;

    public KnowledgeWorkflowProvisioner(WorkflowDefinitionRepository workflowRepository,
                                        ProjectRepository projectRepository,
                                        KnowledgePageRepository pageRepository,
                                        KnowledgePageService pageService) {
        this.workflowRepository = workflowRepository;
        this.projectRepository = projectRepository;
        this.pageRepository = pageRepository;
        this.pageService = pageService;
    }

    /**
     * Creates whatever is missing (workflows, schema page) for {@code projectId}; a no-op for anything
     * that already exists, so re-provisioning (e.g. a repeated enable/disable/enable) never duplicates
     * rows.
     */
    @Transactional
    public void provision(String projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found: " + projectId));
        upsertWorkflow(project, LIBRARIAN_WORKFLOW_NAME, LIBRARIAN_RESOURCE);
        upsertWorkflow(project, BOOTSTRAP_WORKFLOW_NAME, BOOTSTRAP_RESOURCE);
        seedSchemaPage(projectId);
    }

    private void upsertWorkflow(Project project, String name, String resource) {
        if (workflowRepository.findByProjectIdAndName(project.getId(), name).isPresent()) {
            return;
        }
        WorkflowDefinition def = new WorkflowDefinition();
        def.setProject(project);
        def.setName(name);
        def.setYaml(readResource(resource));
        def.setEnabled(true);
        def.setArea("knowledge");
        workflowRepository.save(def);
        log.info("Provisioned system workflow '{}' for project {}", name, project.getId());
    }

    private void seedSchemaPage(String projectId) {
        if (pageRepository.findByProjectIdAndPath(projectId, SCHEMA_PAGE_PATH).isPresent()) {
            return;
        }
        String content = readResource(SCHEMA_RESOURCE);
        pageService.batchWrite(projectId, List.of(new PageWrite(SCHEMA_PAGE_PATH, content, null, false)),
                List.of(), PROVISIONER_ACTOR);
        log.info("Seeded {} for project {}", SCHEMA_PAGE_PATH, projectId);
    }

    private String readResource(String classpathPath) {
        try (InputStream in = getClass().getResourceAsStream(classpathPath)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource: " + classpathPath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read classpath resource: " + classpathPath, e);
        }
    }
}
