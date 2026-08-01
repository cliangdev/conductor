package com.conductor.knowledge;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.knowledge.domain.KnowledgeDomain;
import com.conductor.knowledge.domain.KnowledgeDomainRepository;
import com.conductor.knowledge.domain.KnowledgeDomainState;
import com.conductor.knowledge.page.KnowledgePage;
import com.conductor.knowledge.page.KnowledgePageRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.service.ProjectSettingsService;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.OffsetDateTime;
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
    @Autowired
    private KnowledgeDomainRepository domainRepository;
    @Autowired
    private ProjectMemberRepository projectMemberRepository;
    @Autowired
    private ProjectSettingsService projectSettingsService;

    private String projectId;
    private User adminUser;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        adminUser = userRepository.save(user);

        Project project = new Project();
        project.setName("Knowledge Provisioning Test Project");
        project.setKey("KP" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        Project saved = projectRepository.save(project);
        projectId = saved.getId();

        ProjectMember membership = new ProjectMember();
        membership.setProject(saved);
        membership.setUser(adminUser);
        membership.setRole(MemberRole.ADMIN);
        membership.setJoinedAt(OffsetDateTime.now());
        projectMemberRepository.save(membership);
    }

    @Test
    void reprovisionRefreshesDriftedLibrarianWorkflowYaml() {
        provisioner.provision(projectId);
        WorkflowDefinition librarian = workflowRepository
                .findByProjectIdAndName(projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME)
                .orElseThrow();
        librarian.setYaml("name: Stale Knowledge Librarian\non:\n  workflow_dispatch: {}\n");
        workflowRepository.save(librarian);
        assertThat(provisioner.isLibrarianWorkflowStale(projectId)).isTrue();

        provisioner.provision(projectId);

        WorkflowDefinition refreshed = workflowRepository
                .findByProjectIdAndName(projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME)
                .orElseThrow();
        assertThat(refreshed.getYaml()).contains("agent: ${{ event.agentSlug }}");
        assertThat(provisioner.isLibrarianWorkflowStale(projectId)).isFalse();
    }

    @Test
    void isLibrarianWorkflowStaleIsFalseImmediatelyAfterProvisioning() {
        provisioner.provision(projectId);

        assertThat(provisioner.isLibrarianWorkflowStale(projectId)).isFalse();
    }

    @Test
    void provisionCreatesBothWorkflowsAndSchemaPage() {
        provisioner.provision(projectId);

        List<WorkflowDefinition> workflows = workflowRepository.findByProjectId(projectId);
        assertThat(workflows).extracting(WorkflowDefinition::getName)
                .containsExactlyInAnyOrder(KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME,
                        KnowledgeWorkflowProvisioner.BOOTSTRAP_WORKFLOW_NAME,
                        KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME);
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
    void provisionSeedsRootCurationPage() {
        provisioner.provision(projectId);

        Optional<KnowledgePage> curationPage = pageRepository.findByProjectIdAndPath(projectId, "_curation.md");
        assertThat(curationPage).isPresent();
        assertThat(curationPage.get().getPageType()).isEqualTo("schema");
    }

    @Test
    void reprovisioningDoesNotDuplicateOrOverwriteRootCurationPage() {
        provisioner.provision(projectId);
        KnowledgePage curationPage = pageRepository.findByProjectIdAndPath(projectId, "_curation.md").orElseThrow();
        curationPage.setBody(curationPage.getBody() + "\n\n- Never file a raw log dump. (added by a human)");
        pageRepository.save(curationPage);

        provisioner.provision(projectId);

        KnowledgePage reloaded = pageRepository.findByProjectIdAndPath(projectId, "_curation.md").orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(1); // never rewritten by re-provisioning
        assertThat(reloaded.getBody()).contains("added by a human"); // the human edit survives
    }

    @Test
    void provisionSeedsOneCurationPagePerActiveDomainWithPlaceholdersSubstituted() {
        provisioner.provision(projectId);

        List<KnowledgeDomain> domains = domainRepository.findByProjectIdOrderBySlugAsc(projectId);
        assertThat(domains).hasSize(5);
        for (KnowledgeDomain domain : domains) {
            String path = domain.getSlug() + "/_curation.md";
            Optional<KnowledgePage> curationPage = pageRepository.findByProjectIdAndPath(projectId, path);
            assertThat(curationPage).as("curation page for domain " + domain.getSlug()).isPresent();
            assertThat(curationPage.get().getPageType()).isEqualTo("schema");
            assertThat(curationPage.get().getBody()).contains(domain.getDisplayName());
            assertThat(curationPage.get().getBody()).doesNotContain("%DOMAIN_");
        }
    }

    @Test
    void reprovisioningDoesNotDuplicateOrOverwriteDomainCurationPages() {
        provisioner.provision(projectId);
        KnowledgePage engineeringCuration = pageRepository
                .findByProjectIdAndPath(projectId, "engineering/_curation.md").orElseThrow();
        engineeringCuration.setBody(engineeringCuration.getBody() + "\n\n- Skip flaky-test reruns.");
        pageRepository.save(engineeringCuration);

        provisioner.provision(projectId);

        KnowledgePage reloaded = pageRepository.findByProjectIdAndPath(projectId, "engineering/_curation.md")
                .orElseThrow();
        assertThat(reloaded.getVersion()).isEqualTo(1);
        assertThat(reloaded.getBody()).contains("Skip flaky-test reruns.");

        // Still exactly 5 domain curation pages -- no duplicates from the second provision() call.
        for (String slug : List.of("engineering", "product", "marketing", "finance", "people")) {
            assertThat(pageRepository.findByProjectIdAndPath(projectId, slug + "/_curation.md")).isPresent();
        }
    }

    @Test
    void provisionSeedsACurationPageForARegistryDomainNotInDomainSeeds() {
        // Simulate a librarian-raised gap-report domain approved via KnowledgeDomainService, i.e. a
        // registry row that exists but is not in KnowledgeWorkflowProvisioner's hardcoded DOMAIN_SEEDS
        // list -- the whole point of driving curation seeding off the live registry, not DOMAIN_SEEDS.
        provisioner.provision(projectId);

        KnowledgeDomain legal = new KnowledgeDomain();
        legal.setProjectId(projectId);
        legal.setSlug("legal");
        legal.setDisplayName("Legal");
        legal.setPathPrefix("legal/");
        legal.setSchemaPagePath("legal/_schema.md");
        legal.setSourceTypePatterns(List.of());
        legal.setState(KnowledgeDomainState.ACTIVE);
        domainRepository.save(legal);

        provisioner.provision(projectId);

        Optional<KnowledgePage> legalCuration = pageRepository.findByProjectIdAndPath(projectId, "legal/_curation.md");
        assertThat(legalCuration).isPresent();
        assertThat(legalCuration.get().getBody()).contains("Legal");
    }

    @Test
    void provisionSeedsDomainRegistryAndSchemaPages() {
        provisioner.provision(projectId);

        List<KnowledgeDomain> domains = domainRepository.findByProjectIdOrderBySlugAsc(projectId);
        assertThat(domains).extracting(KnowledgeDomain::getSlug)
                .containsExactly("engineering", "finance", "marketing", "people", "product");
        assertThat(domains).allSatisfy(d -> {
            assertThat(d.getState()).isEqualTo(KnowledgeDomainState.ACTIVE);
            assertThat(d.getDisplayName()).isNotBlank();
            assertThat(d.getPathPrefix()).isEqualTo(d.getSlug() + "/");
            assertThat(d.getSchemaPagePath()).isEqualTo(d.getSlug() + "/_schema.md");
        });

        KnowledgeDomain engineering = domains.stream()
                .filter(d -> d.getSlug().equals("engineering")).findFirst().orElseThrow();
        assertThat(engineering.getSourceTypePatterns()).containsExactly("github.*");

        List<KnowledgeDomain> nonEngineering = domains.stream()
                .filter(d -> !d.getSlug().equals("engineering")).toList();
        assertThat(nonEngineering).allSatisfy(d -> assertThat(d.getSourceTypePatterns()).isEmpty());

        for (KnowledgeDomain domain : domains) {
            Optional<KnowledgePage> schemaPage = pageRepository.findByProjectIdAndPath(projectId, domain.getSchemaPagePath());
            assertThat(schemaPage).as("schema page for domain " + domain.getSlug()).isPresent();
            assertThat(schemaPage.get().getPageType()).isEqualTo("schema");
        }
    }

    @Test
    void provisionRestoresOnlyMissingDomainArtifactsOnReprovision() {
        provisioner.provision(projectId);

        KnowledgeDomain engineering = domainRepository.findByProjectIdAndSlug(projectId, "engineering").orElseThrow();
        engineering.setDisplayName("Eng (customized)");
        domainRepository.save(engineering);

        domainRepository.delete(domainRepository.findByProjectIdAndSlug(projectId, "product").orElseThrow());
        assertThat(domainRepository.findByProjectIdAndSlug(projectId, "product")).isEmpty();

        KnowledgePage marketingSchema = pageRepository.findByProjectIdAndPath(projectId, "marketing/_schema.md").orElseThrow();
        pageRepository.delete(marketingSchema);
        assertThat(pageRepository.findByProjectIdAndPath(projectId, "marketing/_schema.md")).isEmpty();

        provisioner.provision(projectId);

        // Restored: the deleted product domain row and the deleted marketing schema page.
        assertThat(domainRepository.findByProjectIdAndSlug(projectId, "product")).isPresent();
        assertThat(pageRepository.findByProjectIdAndPath(projectId, "marketing/_schema.md")).isPresent();

        // Untouched: the customized engineering display name was not reset back to the seed default.
        KnowledgeDomain reloadedEngineering = domainRepository.findByProjectIdAndSlug(projectId, "engineering").orElseThrow();
        assertThat(reloadedEngineering.getDisplayName()).isEqualTo("Eng (customized)");

        // Still exactly 5 domain rows and 5 domain schema pages (no duplicates from the restore).
        assertThat(domainRepository.findByProjectIdOrderBySlugAsc(projectId)).hasSize(5);
        for (String slug : List.of("engineering", "product", "marketing", "finance", "people")) {
            assertThat(pageRepository.findByProjectIdAndPath(projectId, slug + "/_schema.md")).isPresent();
        }
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
                .contains("knowledge:write_knowledge_pages")
                .contains("knowledge:list_knowledge_domains")
                .contains("knowledge:suggest_knowledge_domain");
        assertThat(librarian.getConfigJson()).contains("\"maxToolTurns\"").contains("40");
        // Pinned to claude-code at seed time -- never left to runtime auto-detection.
        assertThat(librarian.getConfigJson()).contains("\"runtime\"").contains("claude-code");
        assertThat(librarian.getAvatarEmoji()).isEqualTo("📚");
        assertThat(librarian.getAvatarColor()).isEqualTo("violet");
    }

    @Test
    void provisionBackfillsAvatarOnExistingLibrarianSeededWithoutOne() {
        provisioner.provision(projectId);
        Agent librarian = agentRepository.findByProjectIdAndSlug(
                        projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG)
                .orElseThrow();
        librarian.setAvatarEmoji(null);
        librarian.setAvatarColor(null);
        agentRepository.save(librarian);

        provisioner.provision(projectId);

        Agent backfilled = agentRepository.findByProjectIdAndSlug(
                        projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG)
                .orElseThrow();
        assertThat(backfilled.getAvatarEmoji()).isEqualTo("📚");
        assertThat(backfilled.getAvatarColor()).isEqualTo("violet");
    }

    @Test
    void provisionBackfillsMissingToolIdsOnPreExistingLibrarianAgent() {
        provisioner.provision(projectId);
        Agent librarian = agentRepository.findByProjectIdAndSlug(
                        projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG)
                .orElseThrow();
        // Simulate a librarian seeded before list_knowledge_domains/suggest_knowledge_domain existed --
        // only the original 4 tool ids, plus a custom addition an operator made on top.
        librarian.setToolIds("[\"knowledge:read_knowledge_pages\",\"knowledge:read_knowledge_sources\","
                + "\"knowledge:search_knowledge\",\"knowledge:write_knowledge_pages\",\"custom:my_tool\"]");
        agentRepository.save(librarian);

        provisioner.provision(projectId);

        Agent backfilled = agentRepository.findByProjectIdAndSlug(
                        projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG)
                .orElseThrow();
        assertThat(backfilled.getToolIds()).contains("knowledge:list_knowledge_domains")
                .contains("knowledge:suggest_knowledge_domain")
                .contains("custom:my_tool"); // the operator's custom addition survives the backfill
    }

    @Test
    void provisionBackfillsMissingRuntimePinOnPreExistingLibrarianAgent() {
        provisioner.provision(projectId);
        Agent librarian = agentRepository.findByProjectIdAndSlug(
                        projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG)
                .orElseThrow();
        // Simulate a librarian seeded before the runtime pin existed.
        librarian.setConfigJson("{\"maxToolTurns\":40}");
        agentRepository.save(librarian);

        provisioner.provision(projectId);

        Agent backfilled = agentRepository.findByProjectIdAndSlug(
                        projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG)
                .orElseThrow();
        assertThat(backfilled.getConfigJson()).contains("\"runtime\"").contains("claude-code")
                .contains("\"maxToolTurns\"").contains("40");
    }

    @Test
    void provisionLeavesACustomizedLibrarianRuntimePinAlone() {
        provisioner.provision(projectId);
        Agent librarian = agentRepository.findByProjectIdAndSlug(
                        projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG)
                .orElseThrow();
        librarian.setConfigJson("{\"maxToolTurns\":40,\"runtime\":\"api\"}");
        agentRepository.save(librarian);

        provisioner.provision(projectId);

        Agent unchanged = agentRepository.findByProjectIdAndSlug(
                        projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG)
                .orElseThrow();
        assertThat(unchanged.getConfigJson()).contains("\"runtime\"").contains("\"api\"");
    }

    @Test
    void provisionLeavesACustomizedLibrarianAvatarAlone() {
        provisioner.provision(projectId);
        Agent librarian = agentRepository.findByProjectIdAndSlug(
                        projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG)
                .orElseThrow();
        librarian.setAvatarEmoji("🦉");
        librarian.setAvatarColor("teal");
        agentRepository.save(librarian);

        provisioner.provision(projectId);

        Agent unchanged = agentRepository.findByProjectIdAndSlug(
                        projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG)
                .orElseThrow();
        assertThat(unchanged.getAvatarEmoji()).isEqualTo("🦉");
        assertThat(unchanged.getAvatarColor()).isEqualTo("teal");
    }

    @Test
    void provisionIsIdempotentOnDoubleEnable() {
        provisioner.provision(projectId);
        provisioner.provision(projectId);

        List<WorkflowDefinition> workflows = workflowRepository.findByProjectId(projectId);
        assertThat(workflows).hasSize(3);
        assertThat(workflows).extracting(WorkflowDefinition::getName)
                .containsExactlyInAnyOrder(KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME,
                        KnowledgeWorkflowProvisioner.BOOTSTRAP_WORKFLOW_NAME,
                        KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME);

        // Still exactly one schema page, at version 1 (never rewritten by the second provision() call).
        Optional<KnowledgePage> schemaPage = pageRepository.findByProjectIdAndPath(projectId, "_schema.md");
        assertThat(schemaPage).isPresent();
        assertThat(schemaPage.get().getVersion()).isEqualTo(1);

        // Still exactly the librarian + metrics-analyst agents -- the second provision() call is a
        // no-op on an existing slug.
        List<Agent> agents = agentRepository.findByProjectId(projectId);
        assertThat(agents).extracting(Agent::getSlug)
                .containsExactlyInAnyOrder(KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG,
                        KnowledgeWorkflowProvisioner.METRICS_ANALYST_AGENT_SLUG);

        // Still exactly 5 domain rows and 5 domain schema pages -- the second call double-provisions nothing.
        assertThat(domainRepository.findByProjectIdOrderBySlugAsc(projectId)).hasSize(5);
        for (String slug : List.of("engineering", "product", "marketing", "finance", "people")) {
            Optional<KnowledgePage> page = pageRepository.findByProjectIdAndPath(projectId, slug + "/_schema.md");
            assertThat(page).isPresent();
            assertThat(page.get().getVersion()).isEqualTo(1);
        }
    }

    @Test
    void provisionSeedsMetricsAnalystAgentWithZeroTools() {
        provisioner.provision(projectId);

        Optional<Agent> agent = agentRepository.findByProjectIdAndSlug(
                projectId, KnowledgeWorkflowProvisioner.METRICS_ANALYST_AGENT_SLUG);
        assertThat(agent).isPresent();
        Agent metricsAnalyst = agent.get();
        assertThat(metricsAnalyst.getProvider()).isEqualTo("claude");
        assertThat(metricsAnalyst.getState()).isEqualTo("ACTIVE");
        assertThat(metricsAnalyst.getSystemPrompt()).contains("no tools");
        // The security-relevant part: this agent must never be seeded with any tool ids -- see
        // KnowledgeWorkflowProvisioner#seedMetricsAnalystAgent's javadoc for why.
        assertThat(metricsAnalyst.getToolIds()).isEqualTo("[]");
        assertThat(metricsAnalyst.getConfigJson()).contains("\"runtime\"").contains("claude-code");
        assertThat(metricsAnalyst.getAvatarEmoji()).isEqualTo("📈");
    }

    @Test
    void provisionCreatesTheNarratorWorkflow() {
        provisioner.provision(projectId);

        WorkflowDefinition narrator = workflowRepository
                .findByProjectIdAndName(projectId, KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME)
                .orElseThrow();
        assertThat(narrator.isEnabled()).isTrue();
        assertThat(narrator.getYaml()).contains("agent: ${{ event.agentSlug }}")
                .contains("id: narrate")
                .contains("manual: false");
    }

    @Test
    void isSystemWorkflowStaleGeneralizesToTheNarratorWorkflow() {
        provisioner.provision(projectId);
        assertThat(provisioner.isSystemWorkflowStale(projectId, KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME,
                KnowledgeWorkflowProvisioner.NARRATOR_RESOURCE)).isFalse();

        WorkflowDefinition narrator = workflowRepository
                .findByProjectIdAndName(projectId, KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME)
                .orElseThrow();
        narrator.setYaml("name: Stale Metrics Narrator\non:\n  workflow_dispatch: {}\n");
        workflowRepository.save(narrator);

        assertThat(provisioner.isSystemWorkflowStale(projectId, KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME,
                KnowledgeWorkflowProvisioner.NARRATOR_RESOURCE)).isTrue();

        provisioner.provision(projectId);

        WorkflowDefinition refreshed = workflowRepository
                .findByProjectIdAndName(projectId, KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME)
                .orElseThrow();
        assertThat(refreshed.getYaml()).contains("agent: ${{ event.agentSlug }}");
    }

    // ---- self-healing via ProjectSettingsService (a project already enabled before/independent of
    // this save re-provisions on every save that leaves knowledge enabled, not just false->true) ----

    @Test
    void settingsSaveReseedsLibrarianAgentDeletedAfterInitialProvisioning() {
        provisioner.provision(projectId);
        Agent librarian = agentRepository.findByProjectIdAndSlug(projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG)
                .orElseThrow();
        agentRepository.delete(librarian);
        assertThat(agentRepository.existsByProjectIdAndSlug(projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG)).isFalse();

        projectSettingsService.updateSettings(projectId, null, null, null, null, true, null, adminUser);

        assertThat(agentRepository.existsByProjectIdAndSlug(projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG)).isTrue();
    }

    @Test
    void repeatedSettingsSavesDoNotDuplicateProvisionedArtifacts() {
        projectSettingsService.updateSettings(projectId, null, null, null, null, true, null, adminUser);
        projectSettingsService.updateSettings(projectId, null, null, null, null, true, null, adminUser);
        projectSettingsService.updateSettings(projectId, null, null, null, null, true, null, adminUser);

        List<WorkflowDefinition> workflows = workflowRepository.findByProjectId(projectId);
        assertThat(workflows).hasSize(3);

        List<Agent> agents = agentRepository.findByProjectId(projectId);
        assertThat(agents).extracting(Agent::getSlug)
                .containsExactlyInAnyOrder(KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG,
                        KnowledgeWorkflowProvisioner.METRICS_ANALYST_AGENT_SLUG);

        Optional<KnowledgePage> schemaPage = pageRepository.findByProjectIdAndPath(projectId, "_schema.md");
        assertThat(schemaPage).isPresent();
        assertThat(schemaPage.get().getVersion()).isEqualTo(1);
    }

    // ---- system-prompt refresh (backfillSystemPromptIfUnmodified) ----

    /**
     * The exact {@code librarian-system-prompt.md} shipped at commit {@code 8d388838} ("Domain-aware
     * Knowledge Center", #288), kept as a test fixture rather than inlined so this test proves something
     * real: that {@code HISTORICAL_LIBRARIAN_PROMPT_HASHES} actually contains the digest of a genuinely
     * shipped prompt, not a digest computed from whatever the current file happens to be.
     *
     * <p><b>Maintenance rule this fixture enforces:</b> every future rewrite of the prompt must append the
     * outgoing version's hash to that set. If someone rewrites the prompt and forgets, this test still
     * passes (it pins v2, which stays historical forever) -- but
     * {@link #promptFixtureStillHashesToTheDocumentedV2Digest} pins the digest itself, and the set's own
     * javadoc carries the rule. The genuinely load-bearing guard is that a *stale-prompt* agent gets
     * upgraded, which is what {@link #legacyPromptIsRefreshedToCurrentAndStamped} asserts.
     */
    private static final String V2_PROMPT_RESOURCE = "/knowledge/librarian-system-prompt-v2.md";
    private static final String V2_PROMPT_SHA256 =
            "427d5376ecd1191fe5496e17e77ac3ed2ab9d37f79fa0fbaf7264221dcbab286";

    private String readV2Prompt() {
        try (var in = getClass().getResourceAsStream(V2_PROMPT_RESOURCE)) {
            assertThat(in).as("missing test fixture " + V2_PROMPT_RESOURCE).isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(String content) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(digest.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Agent librarian() {
        return agentRepository.findByProjectIdAndSlug(projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG)
                .orElseThrow();
    }

    /** Guards the fixture against accidental reformatting -- if this fails, the fixture was edited and no
     *  longer represents what shipped, which would make the refresh tests below meaningless. */
    @Test
    void promptFixtureStillHashesToTheDocumentedV2Digest() {
        assertThat(sha256Hex(readV2Prompt())).isEqualTo(V2_PROMPT_SHA256);
    }

    @Test
    void freshlySeededLibrarianCarriesSeededPromptHash() {
        projectSettingsService.updateSettings(projectId, null, null, null, null, true, null, adminUser);

        Agent librarian = librarian();
        assertThat(librarian.getConfigJson())
                .contains(KnowledgeWorkflowProvisioner.SEEDED_PROMPT_HASH_CONFIG_KEY)
                .contains(sha256Hex(librarian.getSystemPrompt()));
    }

    /**
     * The whole point of the mechanism: a project provisioned back when v2 shipped must actually receive
     * the current prompt, because {@code seedLibrarianAgent} returns early for an existing agent and would
     * otherwise leave it on v2 forever.
     */
    @Test
    void legacyPromptIsRefreshedToCurrentAndStamped() {
        projectSettingsService.updateSettings(projectId, null, null, null, null, true, null, adminUser);
        Agent librarian = librarian();
        String currentPrompt = librarian.getSystemPrompt();
        String v2 = readV2Prompt();
        assertThat(v2).as("v2 must differ from current, or this test proves nothing").isNotEqualTo(currentPrompt);

        // Rewind this project's librarian to the v2 prompt, and strip the stamp, exactly as a project
        // provisioned before stamping existed would look.
        librarian.setSystemPrompt(v2);
        librarian.setConfigJson("{\"maxToolTurns\":40,\"runtime\":\"claude-code\"}");
        agentRepository.save(librarian);

        projectSettingsService.updateSettings(projectId, null, null, null, null, true, null, adminUser);

        Agent refreshed = librarian();
        assertThat(refreshed.getSystemPrompt()).isEqualTo(currentPrompt);
        assertThat(refreshed.getConfigJson()).contains(sha256Hex(currentPrompt));
        // The unrelated config keys must survive the stamp rewrite.
        assertThat(refreshed.getConfigJson()).contains("maxToolTurns").contains("claude-code");
    }

    /** The documented promise ("the system prompt is editable under Automation -> Agents") must hold:
     *  once edited, the prompt is the operator's forever. */
    @Test
    void operatorEditedPromptIsLeftByteIdentical() {
        projectSettingsService.updateSettings(projectId, null, null, null, null, true, null, adminUser);
        Agent librarian = librarian();
        String operatorPrompt = "You are our librarian. Only ever file engineering decisions. Nothing else.";
        librarian.setSystemPrompt(operatorPrompt);
        agentRepository.save(librarian);

        projectSettingsService.updateSettings(projectId, null, null, null, null, true, null, adminUser);

        assertThat(librarian().getSystemPrompt()).isEqualTo(operatorPrompt);
    }

    /** An agent already on the current prompt but with no stamp (seeded before stamping shipped) gets
     *  stamped without its prompt being rewritten. */
    @Test
    void currentPromptWithoutStampIsStampedNotRewritten() {
        projectSettingsService.updateSettings(projectId, null, null, null, null, true, null, adminUser);
        Agent librarian = librarian();
        String currentPrompt = librarian.getSystemPrompt();
        librarian.setConfigJson("{\"maxToolTurns\":40}");
        agentRepository.save(librarian);

        projectSettingsService.updateSettings(projectId, null, null, null, null, true, null, adminUser);

        Agent after = librarian();
        assertThat(after.getSystemPrompt()).isEqualTo(currentPrompt);
        assertThat(after.getConfigJson()).contains(sha256Hex(currentPrompt));
    }
}
