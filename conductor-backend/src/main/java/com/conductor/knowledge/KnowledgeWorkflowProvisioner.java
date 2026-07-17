package com.conductor.knowledge;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.DefaultAgentSlugs;
import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.knowledge.page.KnowledgePageRepository;
import com.conductor.knowledge.page.KnowledgePageService;
import com.conductor.knowledge.page.PageWrite;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Idempotently provisions the two knowledge-domain system workflows (see
 * {@code src/main/resources/knowledge/*.yaml}) and the {@code _schema.md} seed page for a project.
 * Called from {@code ProjectSettingsService} on every settings save that leaves knowledge enabled
 * (not just the false-&gt;true transition) -- catch-up provisioning for projects that were enabled
 * before a given artifact existed, or where a seeded artifact (most commonly the librarian
 * {@code Agent}) was since deleted -- and from {@code LibrarianDispatchService} as a just-in-time
 * self-heal before dispatch if seeding turns out to be incomplete. {@link #provision} is a no-op for
 * anything that already exists, so any number of callers racing or repeating never duplicates rows.
 *
 * <p>{@link WorkflowDefinition} has no "system-managed" column (COND-18's lifecycle layer added one
 * for statecharts -- {@code sidebar_enabled} -- but nothing generic), so identity here is purely the
 * reserved workflow name: {@link #LIBRARIAN_WORKFLOW_NAME} / {@link #BOOTSTRAP_WORKFLOW_NAME}.
 * Provisioning writes {@link WorkflowDefinitionRepository} rows directly rather than going through
 * {@code WorkflowService.createWorkflow} -- same precedent as {@code V74__seed_engineering_workflow}
 * seeding the ENGINEERING lifecycle: a system seed isn't a user action subject to
 * {@code WorkflowService}'s admin-gate/quota/reserved-name checks.
 *
 * <p>Also seeds the {@value #LIBRARIAN_AGENT_SLUG} {@code Agent} definition the librarian workflow's
 * {@code uses: agent} step resolves at dispatch time -- the agent module's config (prompt, tools,
 * {@code maxToolTurns}) is decoupled from which runtime executes it (see
 * {@code com.conductor.workflow.AgentRuntimeResolver}). This goes through {@link AgentRepository}
 * directly, same direct-write style as the workflow/page seeds above -- and for more than
 * consistency: going through {@code AgentService} would create a real Spring bean cycle here
 * (AgentService -> AgentToolRegistry -> KnowledgeToolProvider -> ProjectSettingsService ->
 * KnowledgeWorkflowProvisioner), since {@code ProjectSettingsService} is what calls {@link #provision}
 * in the first place.
 */
@Service
public class KnowledgeWorkflowProvisioner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeWorkflowProvisioner.class);

    public static final String LIBRARIAN_WORKFLOW_NAME = "knowledge-librarian";
    public static final String BOOTSTRAP_WORKFLOW_NAME = "knowledge-bootstrap";
    /** Alias for {@link DefaultAgentSlugs#KNOWLEDGE_LIBRARIAN} -- kept here too since callers/tests
     *  reference the provisioner for it. */
    public static final String LIBRARIAN_AGENT_SLUG = DefaultAgentSlugs.KNOWLEDGE_LIBRARIAN;

    private static final String SCHEMA_PAGE_PATH = "_schema.md";
    private static final String LIBRARIAN_RESOURCE = "/knowledge/knowledge-librarian.yaml";
    private static final String BOOTSTRAP_RESOURCE = "/knowledge/knowledge-bootstrap.yaml";
    private static final String SCHEMA_RESOURCE = "/knowledge/_schema.md";
    private static final String LIBRARIAN_SYSTEM_PROMPT_RESOURCE = "/knowledge/librarian-system-prompt.md";
    private static final String LIBRARIAN_AGENT_NAME = "Knowledge Librarian";
    private static final String LIBRARIAN_AGENT_PROVIDER = "claude";
    private static final List<String> LIBRARIAN_TOOL_IDS = List.of(
            "knowledge:read_knowledge_pages", "knowledge:read_knowledge_sources",
            "knowledge:search_knowledge", "knowledge:write_knowledge_pages");
    private static final Actor PROVISIONER_ACTOR = new Actor("system", "knowledge-provisioner", null);

    private final WorkflowDefinitionRepository workflowRepository;
    private final ProjectRepository projectRepository;
    private final KnowledgePageRepository pageRepository;
    private final KnowledgePageService pageService;
    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;

    public KnowledgeWorkflowProvisioner(WorkflowDefinitionRepository workflowRepository,
                                        ProjectRepository projectRepository,
                                        KnowledgePageRepository pageRepository,
                                        KnowledgePageService pageService,
                                        AgentRepository agentRepository,
                                        ObjectMapper objectMapper) {
        this.workflowRepository = workflowRepository;
        this.projectRepository = projectRepository;
        this.pageRepository = pageRepository;
        this.pageService = pageService;
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Creates whatever is missing (workflows, schema page, librarian agent) for {@code projectId}; a
     * no-op for anything that already exists, so re-provisioning (e.g. a repeated enable/disable/
     * enable) never duplicates rows.
     */
    @Transactional
    public void provision(String projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new EntityNotFoundException("Project not found: " + projectId));
        seedLibrarianAgent(projectId);
        upsertWorkflow(project, LIBRARIAN_WORKFLOW_NAME, LIBRARIAN_RESOURCE);
        upsertWorkflow(project, BOOTSTRAP_WORKFLOW_NAME, BOOTSTRAP_RESOURCE);
        seedSchemaPage(projectId);
    }

    private void seedLibrarianAgent(String projectId) {
        if (agentRepository.existsByProjectIdAndSlug(projectId, LIBRARIAN_AGENT_SLUG)) {
            return;
        }
        Agent agent = new Agent();
        agent.setProjectId(projectId);
        agent.setName(LIBRARIAN_AGENT_NAME);
        agent.setSlug(LIBRARIAN_AGENT_SLUG);
        agent.setDescription("Files knowledge-inbox sources into the wiki.");
        agent.setProvider(LIBRARIAN_AGENT_PROVIDER);
        agent.setSystemPrompt(readResource(LIBRARIAN_SYSTEM_PROMPT_RESOURCE));
        // No "runtime" key -- resolved at execution time (AgentRuntimeResolver auto-detects from
        // project credentials) rather than pinned by the seed.
        agent.setConfigJson(writeJson(Map.of("maxToolTurns", 40)));
        agent.setToolIds(writeJson(LIBRARIAN_TOOL_IDS));
        agent.setState("ACTIVE");
        agentRepository.save(agent);
        log.info("Provisioned '{}' agent for project {}", LIBRARIAN_AGENT_SLUG, projectId);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize librarian agent config", e);
        }
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
