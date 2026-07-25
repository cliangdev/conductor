package com.conductor.knowledge.domain;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.DefaultAgentSlugs;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.knowledge.Actor;
import com.conductor.knowledge.KnowledgeIngestionService;
import com.conductor.knowledge.KnowledgeSource;
import com.conductor.knowledge.KnowledgeSourceRepository;
import com.conductor.knowledge.KnowledgeSubmission;
import com.conductor.knowledge.SourceReceipt;
import com.conductor.knowledge.page.KnowledgePage;
import com.conductor.knowledge.page.KnowledgePageRepository;
import com.conductor.knowledge.page.KnowledgePageService;
import com.conductor.knowledge.page.PageWrite;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;
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
    private KnowledgePageRepository pageRepository;
    @Autowired
    private KnowledgePageService pageService;
    @Autowired
    private KnowledgeIngestionService ingestionService;
    @Autowired
    private KnowledgeSourceRepository sourceRepository;
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

    // ---- approving a SUGGESTED domain seeds a skeleton schema page ----

    @Test
    void approvingSuggestedDomainSeedsSkeletonSchemaPage() {
        createDomain("legal", "Legal", KnowledgeDomainState.SUGGESTED);

        domainService.update(projectId, "legal", null, null, null, KnowledgeDomainState.ACTIVE);

        Optional<KnowledgePage> page = pageRepository.findByProjectIdAndPath(projectId, "legal/_schema.md");
        assertThat(page).isPresent();
        assertThat(page.get().getPageType()).isEqualTo("schema");
        assertThat(page.get().getTitle()).isEqualTo("Legal domain schema");
        assertThat(page.get().getBody()).contains("librarian-raised gap report")
                .contains("under `legal/`"); // %DOMAIN_SLUG% placeholder replaced
    }

    @Test
    void approvingSuggestedDomainWithExistingSchemaPageDoesNotOverwriteIt() {
        createDomain("legal", "Legal", KnowledgeDomainState.SUGGESTED);
        String customContent = "---\ntype: schema\ntitle: Custom\n---\n\nAlready written by someone.";
        pageService.batchWrite(projectId, List.of(new PageWrite("legal/_schema.md", customContent, null, false)),
                List.of(), new Actor("user", "u1", null));

        domainService.update(projectId, "legal", null, null, null, KnowledgeDomainState.ACTIVE);

        KnowledgePage page = pageRepository.findByProjectIdAndPath(projectId, "legal/_schema.md").orElseThrow();
        assertThat(page.getVersion()).isEqualTo(1);
        assertThat(page.getBody()).contains("Already written by someone.");
    }

    @Test
    void updateWithoutStateTransitionToActiveDoesNotSeedSkeletonPage() {
        createDomain("legal", "Legal", KnowledgeDomainState.SUGGESTED);

        domainService.update(projectId, "legal", "Legal Affairs", null, null, null);

        assertThat(pageRepository.findByProjectIdAndPath(projectId, "legal/_schema.md")).isEmpty();
    }

    @Test
    void reApprovingADismissedDomainAlsoSeedsTheSkeletonPage() {
        createDomain("legal", "Legal", KnowledgeDomainState.DISMISSED);

        domainService.update(projectId, "legal", null, null, null, KnowledgeDomainState.ACTIVE);

        assertThat(pageRepository.findByProjectIdAndPath(projectId, "legal/_schema.md")).isPresent();
    }

    // ---- applyPatch atomicity (PATCH endpoint's entry point) ----

    @Test
    void applyPatchRollsBackMetadataChangeWhenOwningAgentSlugIsInvalid() {
        createDomain("engineering", "Engineering", KnowledgeDomainState.ACTIVE);

        assertThatThrownBy(() -> domainService.applyPatch(projectId, "engineering", "Eng", null, null, null,
                false, "no-such-agent"))
                .isInstanceOf(BusinessException.class);

        KnowledgeDomain reloaded = domainRepository.findByProjectIdAndSlug(projectId, "engineering").orElseThrow();
        assertThat(reloaded.getDisplayName()).isEqualTo("Engineering"); // the rename did NOT commit
        assertThat(reloaded.getOwningAgentSlug()).isNull();
    }

    @Test
    void applyPatchAppliesBothMetadataAndOwningAgentWhenValid() {
        createDomain("engineering", "Engineering", KnowledgeDomainState.ACTIVE);
        saveAgent("knowledge-engineering");

        KnowledgeDomain updated = domainService.applyPatch(projectId, "engineering", "Eng", null, null, null,
                false, "knowledge-engineering");

        assertThat(updated.getDisplayName()).isEqualTo("Eng");
        assertThat(updated.getOwningAgentSlug()).isEqualTo("knowledge-engineering");
    }

    @Test
    void applyPatchClearOwningAgentTakesPrecedenceOverOwningAgentSlug() {
        createDomain("engineering", "Engineering", KnowledgeDomainState.ACTIVE);
        saveAgent("knowledge-engineering");
        domainService.updateOwningAgent(projectId, "engineering", "knowledge-engineering");

        KnowledgeDomain updated = domainService.applyPatch(projectId, "engineering", null, null, null, null,
                true, "knowledge-engineering");

        assertThat(updated.getOwningAgentSlug()).isNull();
    }

    // ---- gap-report suggestions (claim-or-return) ----

    @Test
    void suggestCreatesNewSuggestedRow() {
        KnowledgeDomainService.SuggestResult result = domainService.suggest(projectId, "legal", "Legal",
                "Contracts and compliance", "Sources keep mentioning contracts with nowhere to go", List.of(), "agent-1");

        assertThat(result.created()).isTrue();
        assertThat(result.domain().getState()).isEqualTo(KnowledgeDomainState.SUGGESTED);
        assertThat(result.domain().getPathPrefix()).isEqualTo("legal/");
        assertThat(result.domain().getSchemaPagePath()).isEqualTo("legal/_schema.md");
        assertThat(result.domain().getSuggestedBy()).isEqualTo("agent-1");
        assertThat(result.domain().getSuggestionReason()).isEqualTo("Sources keep mentioning contracts with nowhere to go");
    }

    @Test
    void suggestReturnsExistingActiveRowWithCreatedFalse() {
        createDomain("engineering", "Engineering", KnowledgeDomainState.ACTIVE);

        KnowledgeDomainService.SuggestResult result =
                domainService.suggest(projectId, "engineering", "Eng", null, "reason", List.of(), "agent-1");

        assertThat(result.created()).isFalse();
        assertThat(result.domain().getState()).isEqualTo(KnowledgeDomainState.ACTIVE);
    }

    @Test
    void suggestReturnsExistingDismissedRowWithCreatedFalse() {
        createDomain("legal", "Legal", KnowledgeDomainState.DISMISSED);

        KnowledgeDomainService.SuggestResult result =
                domainService.suggest(projectId, "legal", "Legal", null, "reason again", List.of(), "agent-2");

        assertThat(result.created()).isFalse();
        assertThat(result.domain().getState()).isEqualTo(KnowledgeDomainState.DISMISSED);
    }

    @Test
    void suggestRejectsInvalidSlug() {
        assertThatThrownBy(() -> domainService.suggest(projectId, "Not Valid!", "X", null, "r", List.of(), "a"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void suggestRejectsBlankDisplayName() {
        assertThatThrownBy(() -> domainService.suggest(projectId, "legal", "  ", null, "r", List.of(), "a"))
                .isInstanceOf(BusinessException.class);
    }

    // ---- specialist creation ----

    @Test
    void createSpecialistCreatesAgentAndAssignsOwningAgent() {
        createDomain("engineering", "Engineering", KnowledgeDomainState.ACTIVE);

        KnowledgeDomain updated = domainService.createSpecialist(projectId, "engineering");

        assertThat(updated.getOwningAgentSlug()).isEqualTo("knowledge-engineering");
        Agent agent = agentRepository.findByProjectIdAndSlug(projectId, "knowledge-engineering").orElseThrow();
        assertThat(agent.getProvider()).isEqualTo("claude");
        assertThat(agent.getSystemPrompt()).contains("Engineering").contains("engineering/_schema.md");
        assertThat(agent.getToolIds()).contains("knowledge:list_knowledge_domains")
                .contains("knowledge:suggest_knowledge_domain");
        assertThat(agent.getConfigJson()).contains("\"maxToolTurns\"").contains("40");
        assertThat(agent.getConfigJson()).contains("\"runtime\"").contains("claude-code");
        assertThat(agent.getAvatarEmoji()).isNotBlank();
        assertThat(agent.getAvatarColor()).isNotBlank();
        assertThat(DefaultAgentSlugs.isDefault("knowledge-engineering")).isFalse();
    }

    @Test
    void createSpecialistIsIdempotentWhenAgentAlreadyExists() {
        createDomain("engineering", "Engineering", KnowledgeDomainState.ACTIVE);

        domainService.createSpecialist(projectId, "engineering");
        domainService.createSpecialist(projectId, "engineering");

        List<Agent> agents = agentRepository.findByProjectId(projectId);
        assertThat(agents).filteredOn(a -> a.getSlug().equals("knowledge-engineering")).hasSize(1);
    }

    @Test
    void createSpecialistThrowsNotFoundForUnknownDomain() {
        assertThatThrownBy(() -> domainService.createSpecialist(projectId, "nonexistent"))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ---- end to end: gap report -> approval -> routing ----

    @Test
    void approvingASuggestedDomainMakesItRoutableForMatchingSources() {
        KnowledgeDomainService.SuggestResult suggestion = domainService.suggest(projectId, "legal", "Legal",
                "Contracts", "Sources keep mentioning contracts", List.of("legal.*"), "agent-1");
        assertThat(suggestion.domain().getState()).isEqualTo(KnowledgeDomainState.SUGGESTED);

        // While still SUGGESTED, the pattern is not yet live -- only ACTIVE domains route by pattern.
        SourceReceipt beforeApproval = ingestionService.submit(new KnowledgeSubmission(projectId, "legal.contract",
                "ref-1", null, "text/plain", "content", null, null, null, null, null));
        KnowledgeSource beforeSource = sourceRepository.findById(beforeApproval.sourceId()).orElseThrow();
        assertThat(beforeSource.getDomain()).isNull();

        domainService.update(projectId, "legal", null, null, null, KnowledgeDomainState.ACTIVE);

        SourceReceipt afterApproval = ingestionService.submit(new KnowledgeSubmission(projectId, "legal.contract",
                "ref-2", null, "text/plain", "content", null, null, null, null, null));
        KnowledgeSource afterSource = sourceRepository.findById(afterApproval.sourceId()).orElseThrow();
        assertThat(afterSource.getDomain()).isEqualTo("legal");
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
