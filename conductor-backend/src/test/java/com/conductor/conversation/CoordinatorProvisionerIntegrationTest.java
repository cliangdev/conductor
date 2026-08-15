package com.conductor.conversation;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.DefaultAgentSlugs;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB-backed: exercises real {@link AgentRepository} queries rather than mocking them, mirroring {@code
 * KnowledgeWorkflowProvisionerIntegrationTest}'s style. Deliberately NOT {@code @Transactional} at the
 * class level -- {@link CoordinatorProvisioner#ensureProvisioned} inserts via a {@code REQUIRES_NEW}
 * self-proxy call (same shape as {@code ConversationService#findOrCreateByChannelKey}), which would
 * suspend and be unable to see this test's setup data if it were still sitting uncommitted in an outer
 * test transaction. Isolation instead comes from each test using its own random project id.
 */
class CoordinatorProvisionerIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired
    private CoordinatorProvisioner provisioner;
    @Autowired
    private AddressableAgentResolver resolver;
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
        project.setName("CEO Provisioning Test Project");
        project.setKey("CP" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        projectId = projectRepository.save(project).getId();
    }

    private Agent ceo() {
        return agentRepository.findByProjectIdAndSlug(projectId, DefaultAgentSlugs.CEO).orElseThrow();
    }

    private static String currentPromptResource() {
        try (InputStream in = CoordinatorProvisionerIntegrationTest.class
                .getResourceAsStream("/conversation/ceo-system-prompt.md")) {
            assertThat(in).as("missing /conversation/ceo-system-prompt.md").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void resolveOnAFreshProjectSelfHealsTheCeoBeforeResolving() {
        // The very first conversation request a project ever sees goes through resolve() -- it must
        // seed the CEO rather than 404 (the controller path never calls ensureProvisioned itself).
        Agent resolved = resolver.resolve(projectId, null);

        assertThat(resolved.getSlug()).isEqualTo(DefaultAgentSlugs.CEO);
        assertThat(agentRepository.findByProjectIdAndSlug(projectId, DefaultAgentSlugs.CEO)).isPresent();
    }

    @Test
    void ensureProvisionedSeedsAgentWithExpectedFields() {
        provisioner.ensureProvisioned(projectId);

        Agent ceo = ceo();
        assertThat(ceo.getName()).isEqualTo("John");
        assertThat(ceo.getSlug()).isEqualTo(DefaultAgentSlugs.CEO);
        assertThat(ceo.getState()).isEqualTo("ACTIVE");
        assertThat(ceo.getProvider()).isEqualTo("claude");
        assertThat(ceo.getModel()).isNull();
        assertThat(ceo.getDescription()).isEqualTo("Project coordinator — ask me anything about this project");
        assertThat(ceo.getSystemPrompt()).isEqualTo(currentPromptResource());
        // The prompt is name-neutral -- AgentConversationRunner injects the agent's CURRENT name/slug
        // as a suffix on every turn instead, so a rename takes effect immediately (see
        // AgentConversationRunnerIntegrationTest#systemPromptSuffixNamesTheCurrentAgent).
        assertThat(ceo.getSystemPrompt()).doesNotContain("John");
        assertThat(ceo.getAvatarEmoji()).isNotNull();
        assertThat(ceo.getAvatarColor()).isNotNull();

        assertThat(ceo.getConfigJson()).contains("\"runtime\"").contains("\"api\"")
                .contains("\"maxToolTurns\"").contains("24")
                .contains("\"addressable\"").contains("true")
                .contains("seededPromptHash").contains(sha256Hex(ceo.getSystemPrompt()));

        assertThat(ceo.getToolIds())
                .contains("knowledge:search_knowledge")
                .contains("knowledge:read_knowledge_pages")
                .contains("knowledge:list_knowledge_domains")
                .contains("coordinator:create_work_item")
                .contains("coordinator:list_work_items")
                .contains("coordinator:get_work_item")
                .contains("coordinator:list_workflows")
                .contains("coordinator:dispatch_workflow")
                .contains("coordinator:get_workflow_run")
                .contains("coordinator:list_agents")
                .contains("coordinator:search_project_docs")
                .contains("coordinator:read_project_doc")
                .contains("coordinator:ask_agent")
                .contains("memory:search_memory");
    }

    @Test
    void ensureProvisionedTwiceCreatesOnlySingleRow() {
        provisioner.ensureProvisioned(projectId);
        provisioner.ensureProvisioned(projectId);

        List<Agent> ceoAgents = agentRepository.findByProjectId(projectId).stream()
                .filter(a -> DefaultAgentSlugs.CEO.equals(a.getSlug()))
                .toList();
        assertThat(ceoAgents).hasSize(1);
    }

    @Test
    void deletedAgentReSeedsViaSelfHeal() {
        provisioner.ensureProvisioned(projectId);
        agentRepository.delete(ceo());
        assertThat(agentRepository.existsByProjectIdAndSlug(projectId, DefaultAgentSlugs.CEO)).isFalse();

        provisioner.ensureProvisioned(projectId);

        assertThat(agentRepository.existsByProjectIdAndSlug(projectId, DefaultAgentSlugs.CEO)).isTrue();
    }

    @Test
    void userRemovedToolIdsAreReAddedAdditivelyOnReprovision() {
        provisioner.ensureProvisioned(projectId);
        Agent ceo = ceo();
        // Simulate an operator having removed a couple of the default tool ids, plus added their own.
        ceo.setToolIds("[\"knowledge:search_knowledge\",\"custom:my_tool\"]");
        agentRepository.save(ceo);

        provisioner.ensureProvisioned(projectId);

        Agent backfilled = ceo();
        assertThat(backfilled.getToolIds())
                .contains("knowledge:read_knowledge_pages") // removed, then re-added (matches librarian semantics)
                .contains("coordinator:create_work_item")
                .contains("custom:my_tool"); // the operator's custom addition survives
    }

    @Test
    void userConfigOverrideSurvivesReprovision() {
        provisioner.ensureProvisioned(projectId);
        Agent ceo = ceo();
        ceo.setConfigJson("{\"maxToolTurns\":40,\"runtime\":\"api\",\"addressable\":true}");
        agentRepository.save(ceo);

        provisioner.ensureProvisioned(projectId);

        Agent unchanged = ceo();
        assertThat(unchanged.getConfigJson()).contains("\"maxToolTurns\"").contains("40");
    }

    @Test
    void backfillAddsOnlyTrulyMissingConfigKeys() {
        provisioner.ensureProvisioned(projectId);
        Agent ceo = ceo();
        // Simulate a CEO agent seeded before addressable/maxToolTurns existed on this class.
        ceo.setConfigJson("{\"runtime\":\"api\"}");
        agentRepository.save(ceo);

        provisioner.ensureProvisioned(projectId);

        Agent backfilled = ceo();
        assertThat(backfilled.getConfigJson())
                .contains("\"maxToolTurns\"").contains("24")
                .contains("\"addressable\"").contains("true")
                .contains("\"runtime\"").contains("\"api\"");
    }

    @Test
    void operatorEditedPromptIsLeftByteIdentical() {
        provisioner.ensureProvisioned(projectId);
        Agent ceo = ceo();
        String operatorPrompt = "You are our coordinator. Only ever answer engineering questions.";
        ceo.setSystemPrompt(operatorPrompt);
        agentRepository.save(ceo);

        provisioner.ensureProvisioned(projectId);

        assertThat(ceo().getSystemPrompt()).isEqualTo(operatorPrompt);
    }

    /**
     * The load-bearing case: a stored prompt that still matches this agent's own {@code
     * seededPromptHash} stamp (i.e. it's exactly what Conductor previously shipped and stamped, now
     * stale relative to the current classpath resource) gets refreshed and re-stamped -- as opposed to
     * an operator edit, which is left alone. Simulates the "prompt was rewritten after this project was
     * seeded" scenario without needing a second real shipped prompt fixture (this class has none yet --
     * see {@code HISTORICAL_CEO_PROMPT_HASHES}'s javadoc).
     */
    @Test
    void staleSeededPromptMatchingOwnStampIsRefreshedAndReStamped() {
        provisioner.ensureProvisioned(projectId);
        Agent ceo = ceo();
        String currentPrompt = ceo.getSystemPrompt();
        String oldPrompt = "You are John, an earlier version of the coordinator prompt.";

        ceo.setSystemPrompt(oldPrompt);
        ceo.setConfigJson("{\"runtime\":\"api\",\"maxToolTurns\":24,\"addressable\":true,"
                + "\"seededPromptHash\":\"" + sha256Hex(oldPrompt) + "\"}");
        agentRepository.save(ceo);

        provisioner.ensureProvisioned(projectId);

        Agent refreshed = ceo();
        assertThat(refreshed.getSystemPrompt()).isEqualTo(currentPrompt);
        assertThat(refreshed.getConfigJson()).contains(sha256Hex(currentPrompt));
        // Unrelated config keys survive the stamp rewrite.
        assertThat(refreshed.getConfigJson()).contains("maxToolTurns").contains("addressable");
    }

    @Test
    void currentPromptWithoutStampIsStampedNotRewritten() {
        provisioner.ensureProvisioned(projectId);
        Agent ceo = ceo();
        String currentPrompt = ceo.getSystemPrompt();
        ceo.setConfigJson("{\"runtime\":\"api\",\"maxToolTurns\":24,\"addressable\":true}");
        agentRepository.save(ceo);

        provisioner.ensureProvisioned(projectId);

        Agent after = ceo();
        assertThat(after.getSystemPrompt()).isEqualTo(currentPrompt);
        assertThat(after.getConfigJson()).contains(sha256Hex(currentPrompt));
    }
}
