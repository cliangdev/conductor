package com.conductor.knowledge;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.knowledge.page.KnowledgePage;
import com.conductor.knowledge.page.KnowledgePageRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-backed: exercises real repository queries + {@code KnowledgePageService.batchWrite} rather than
 * mocking them, so "idempotent double-enable" is proven against actual persisted rows, not a mocked
 * expectation of what idempotency should look like.
 */
class KnowledgeWorkflowProvisionerIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private KnowledgeWorkflowProvisioner provisioner;
    @Autowired
    private WorkflowDefinitionRepository workflowRepository;
    @Autowired
    private KnowledgePageRepository pageRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private AgentRepository agentRepository;

    private String projectId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        Project project = new Project();
        project.setName("Knowledge Provisioning Test Project");
        project.setKey("KP" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        projectId = projectRepository.save(project).getId();
    }

    @Test
    void provisionCreatesBothWorkflowsAndSchemaPage() {
        provisioner.provision(projectId);

        List<WorkflowDefinition> workflows = workflowRepository.findByProjectId(projectId);
        assertThat(workflows).extracting(WorkflowDefinition::getName)
                .containsExactlyInAnyOrder(KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME,
                        KnowledgeWorkflowProvisioner.BOOTSTRAP_WORKFLOW_NAME);
        assertThat(workflows).allSatisfy(w -> {
            assertThat(w.isEnabled()).isTrue();
            assertThat(w.getYaml()).isNotBlank();
            assertThat(w.isLifecycle()).isFalse(); // YAML automation, not a statechart
        });

        Optional<KnowledgePage> schemaPage = pageRepository.findByProjectIdAndPath(projectId, "_schema.md");
        assertThat(schemaPage).isPresent();
        assertThat(schemaPage.get().getPageType()).isEqualTo("schema");
        assertThat(schemaPage.get().getTitle()).isEqualTo("Knowledge Center schema");
    }

    @Test
    void provisionSeedsLibrarianAgentWithExpectedFields() {
        provisioner.provision(projectId);

        Optional<Agent> agent = agentRepository.findByProjectIdAndSlug(
                projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG);
        assertThat(agent).isPresent();
        Agent librarian = agent.get();
        assertThat(librarian.getProvider()).isEqualTo("claude");
        assertThat(librarian.getModel()).isNull();
        assertThat(librarian.getState()).isEqualTo("ACTIVE");
        assertThat(librarian.getSystemPrompt()).contains("Knowledge Center librarian");
        assertThat(librarian.getToolIds()).contains("knowledge:read_knowledge_pages")
                .contains("knowledge:read_knowledge_sources")
                .contains("knowledge:search_knowledge")
                .contains("knowledge:write_knowledge_pages");
        assertThat(librarian.getConfigJson()).contains("\"maxToolTurns\"").contains("40");
        // No runtime key -- resolved at execution time (auto-detect), never pinned by the seed.
        assertThat(librarian.getConfigJson()).doesNotContain("runtime");
    }

    @Test
    void provisionIsIdempotentOnDoubleEnable() {
        provisioner.provision(projectId);
        provisioner.provision(projectId);

        List<WorkflowDefinition> workflows = workflowRepository.findByProjectId(projectId);
        assertThat(workflows).hasSize(2);
        assertThat(workflows).extracting(WorkflowDefinition::getName)
                .containsExactlyInAnyOrder(KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME,
                        KnowledgeWorkflowProvisioner.BOOTSTRAP_WORKFLOW_NAME);

        // Still exactly one schema page, at version 1 (never rewritten by the second provision() call).
        Optional<KnowledgePage> schemaPage = pageRepository.findByProjectIdAndPath(projectId, "_schema.md");
        assertThat(schemaPage).isPresent();
        assertThat(schemaPage.get().getVersion()).isEqualTo(1);

        // Still exactly one librarian agent -- the second provision() call is a no-op on an existing slug.
        List<Agent> agents = agentRepository.findByProjectId(projectId);
        assertThat(agents).extracting(Agent::getSlug)
                .containsExactly(KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG);
    }
}
