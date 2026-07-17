package com.conductor.knowledge.domain;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DB-backed: exercises real repository queries against {@link KnowledgeDomainService}, including
 * owning-agent validation against a real {@link AgentRepository} row.
 */
class KnowledgeDomainServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private KnowledgeDomainService domainService;
    @Autowired
    private KnowledgeDomainRepository domainRepository;
    @Autowired
    private AgentRepository agentRepository;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private UserRepository userRepository;

    private String projectId;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        userRepository.save(user);

        Project project = new Project();
        project.setName("Knowledge Domain Service Test Project");
        project.setKey("KD" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        projectId = projectRepository.save(project).getId();
    }

    private KnowledgeDomain createDomain(String slug, String displayName, KnowledgeDomainState state) {
        KnowledgeDomain domain = new KnowledgeDomain();
        domain.setProjectId(projectId);
        domain.setSlug(slug);
        domain.setDisplayName(displayName);
        domain.setPathPrefix(slug + "/");
        domain.setSchemaPagePath(slug + "/_schema.md");
        domain.setSourceTypePatterns(List.of());
        domain.setState(state);
        return domainRepository.save(domain);
    }

    @Test
    void listReturnsDomainsOrderedBySlug() {
        createDomain("marketing", "Marketing", KnowledgeDomainState.ACTIVE);
        createDomain("engineering", "Engineering", KnowledgeDomainState.ACTIVE);
        createDomain("finance", "Finance", KnowledgeDomainState.ACTIVE);

        List<KnowledgeDomain> domains = domainService.list(projectId);

        assertThat(domains).extracting(KnowledgeDomain::getSlug)
                .containsExactly("engineering", "finance", "marketing");
    }

    @Test
    void updateEditsProvidedFieldsAndLeavesOthersUnchanged() {
        createDomain("engineering", "Engineering", KnowledgeDomainState.ACTIVE);

        KnowledgeDomain updated = domainService.update(projectId, "engineering", "Eng", "Backend + frontend",
                List.of("github.*", "gitlab.*"), null);

        assertThat(updated.getDisplayName()).isEqualTo("Eng");
        assertThat(updated.getDescription()).isEqualTo("Backend + frontend");
        assertThat(updated.getSourceTypePatterns()).containsExactly("github.*", "gitlab.*");
        assertThat(updated.getState()).isEqualTo(KnowledgeDomainState.ACTIVE); // null arg -- unchanged

        KnowledgeDomain again = domainService.update(projectId, "engineering", null, null, null, null);
        assertThat(again.getDisplayName()).isEqualTo("Eng"); // still unchanged by the all-null call
        assertThat(again.getDescription()).isEqualTo("Backend + frontend");
        assertThat(again.getSourceTypePatterns()).containsExactly("github.*", "gitlab.*");
    }

    @Test
    void updateThrowsNotFoundForUnknownSlug() {
        assertThatThrownBy(() -> domainService.update(projectId, "nonexistent", "X", null, null, null))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void updateTransitionsStateFromSuggestedToActive() {
        createDomain("legal", "Legal", KnowledgeDomainState.SUGGESTED);

        KnowledgeDomain updated = domainService.update(projectId, "legal", null, null, null, KnowledgeDomainState.ACTIVE);

        assertThat(updated.getState()).isEqualTo(KnowledgeDomainState.ACTIVE);
    }

    @Test
    void updateOwningAgentRejectsUnknownAgentSlug() {
        createDomain("engineering", "Engineering", KnowledgeDomainState.ACTIVE);

        assertThatThrownBy(() -> domainService.updateOwningAgent(projectId, "engineering", "no-such-agent"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void updateOwningAgentAcceptsExistingAgentSlug() {
        createDomain("engineering", "Engineering", KnowledgeDomainState.ACTIVE);
        saveAgent("knowledge-engineering");

        KnowledgeDomain updated = domainService.updateOwningAgent(projectId, "engineering", "knowledge-engineering");

        assertThat(updated.getOwningAgentSlug()).isEqualTo("knowledge-engineering");
    }

    @Test
    void updateOwningAgentNullClearsAssignment() {
        createDomain("engineering", "Engineering", KnowledgeDomainState.ACTIVE);
        saveAgent("knowledge-engineering");
        domainService.updateOwningAgent(projectId, "engineering", "knowledge-engineering");

        KnowledgeDomain cleared = domainService.updateOwningAgent(projectId, "engineering", null);

        assertThat(cleared.getOwningAgentSlug()).isNull();
    }

    private void saveAgent(String slug) {
        Agent agent = new Agent();
        agent.setProjectId(projectId);
        agent.setName(slug);
        agent.setSlug(slug);
        agent.setProvider("claude");
        agent.setState("ACTIVE");
        agentRepository.save(agent);
    }
}
