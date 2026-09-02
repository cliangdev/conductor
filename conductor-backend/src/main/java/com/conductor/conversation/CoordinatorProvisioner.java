package com.conductor.conversation;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentAvatarDefaults;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.DefaultAgentSlugs;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Idempotently provisions the {@value DefaultAgentSlugs#CEO} {@link Agent} -- the addressable
 * coordinator every {@link Conversation} in a project defaults to (see {@link
 * ConversationService#create}/{@link ConversationService#findOrCreateByChannelKey}, which call {@link
 * #ensureProvisioned} first). Mirrors {@code KnowledgeWorkflowProvisioner}'s seeding discipline for its
 * librarian agent -- self-heal on every call, additive backfill, hash-gated prompt refresh -- but seeds
 * no workflow: unlike the librarian, the CEO is conversation-driven, never dispatched by a trigger, so
 * there is nothing else to provision.
 */
@Service
public class CoordinatorProvisioner {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorProvisioner.class);

    private static final String CEO_AGENT_NAME = "John";
    private static final String CEO_AGENT_DESCRIPTION = "Project coordinator — ask me anything about this project";
    private static final String CEO_AGENT_PROVIDER = "claude";
    private static final int CEO_MAX_TOOL_TURNS = 24;
    private static final String SYSTEM_PROMPT_RESOURCE = "/conversation/ceo-system-prompt.md";

    /** Same key name/convention as {@code KnowledgeWorkflowProvisioner#SEEDED_PROMPT_HASH_CONFIG_KEY}. */
    private static final String SEEDED_PROMPT_HASH_CONFIG_KEY = "seededPromptHash";

    /**
     * SHA-256 (lowercase hex) of every {@code ceo-system-prompt.md} Conductor has ever shipped -- see
     * {@code KnowledgeWorkflowProvisioner#HISTORICAL_LIBRARIAN_PROMPT_HASHES} for the full mechanism
     * this mirrors. Empty today: this is the CEO agent's first shipped prompt, so there is no prior
     * version yet. <b>Append the outgoing hash here, in the same commit as every future
     * {@code ceo-system-prompt.md} rewrite, and never remove an entry</b> -- skipping this step is the
     * one way this mechanism silently stops working for projects seeded on an older prompt.
     */
    private static final Set<String> HISTORICAL_CEO_PROMPT_HASHES = Set.of();

    /** The Knowledge Center's read-only tools -- the CEO can search and read the wiki, but (unlike the
     *  librarian) never writes to it; filing knowledge is the librarian's job, not the coordinator's. */
    private static final List<String> KNOWLEDGE_TOOL_IDS = List.of(
            "knowledge:search_knowledge", "knowledge:read_knowledge_pages", "knowledge:list_knowledge_domains");

    /** All ten {@code coordinator:*} tools (see {@code CoordinatorToolProvider}) -- the CEO is the
     *  reference addressable agent for the full hub-and-spoke surface. */
    private static final List<String> COORDINATOR_TOOL_IDS = List.of(
            "coordinator:create_work_item", "coordinator:list_work_items", "coordinator:get_work_item",
            "coordinator:list_workflows", "coordinator:dispatch_workflow", "coordinator:get_workflow_run",
            "coordinator:list_agents", "coordinator:search_project_docs", "coordinator:read_project_doc",
            "coordinator:ask_agent");

    /** {@code memory:*} tools (see {@code MemoryToolProvider}) -- read-only, same rationale as {@link
     *  #KNOWLEDGE_TOOL_IDS}: the CEO can search long-term memory, but extraction/consolidation is a
     *  background process's job, not the coordinator's. */
    private static final List<String> MEMORY_TOOL_IDS = List.of("memory:search_memory");

    /** Seeded/backfilled tool ids, knowledge tools first for readability -- order has no functional
     *  meaning (the merge in {@link #backfillToolIdsIfMissing} is order-preserving but set-based). */
    private static final List<String> CEO_TOOL_IDS = Stream.of(KNOWLEDGE_TOOL_IDS, COORDINATOR_TOOL_IDS, MEMORY_TOOL_IDS)
            .flatMap(List::stream).toList();

    private final AgentRepository agentRepository;
    private final ObjectMapper objectMapper;

    /** Self-reference so the {@code REQUIRES_NEW} insert in {@link #ensureProvisioned} runs through the
     *  Spring proxy -- see {@code ConversationService#findOrCreateByChannelKey} for the same pattern. */
    @Autowired
    @Lazy
    CoordinatorProvisioner self;

    public CoordinatorProvisioner(AgentRepository agentRepository, ObjectMapper objectMapper) {
        this.agentRepository = agentRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Seeds the {@value DefaultAgentSlugs#CEO} agent for {@code projectId} if it doesn't exist; if it
     * does, additively backfills anything a shipped update has added since it was last seeded, without
     * ever clobbering an operator's edits (a stale seeded value is refreshed, an edited one is left
     * alone -- see {@link #backfillSystemPromptIfUnmodified}). Cheap on the common (already-provisioned,
     * fully up to date) path: one indexed lookup by {@code (project_id, slug)}, no writes.
     */
    @Transactional
    public void ensureProvisioned(String projectId) {
        Optional<Agent> existing = agentRepository.findByProjectIdAndSlug(projectId, DefaultAgentSlugs.CEO);
        if (existing.isPresent()) {
            backfillToolIdsIfMissing(existing.get());
            backfillConfigDefaultsIfMissing(existing.get());
            backfillSystemPromptIfUnmodified(existing.get());
            return;
        }
        try {
            self.insertInNewTx(projectId);
        } catch (DataIntegrityViolationException e) {
            // Lost the insert race to a concurrent ensureProvisioned (unique (project_id, slug) on
            // agents is the real guard) -- the winner's row already exists, which is all this method
            // promises. Re-read only to surface a genuine anomaly (the constraint fired but no row is
            // actually there) rather than silently swallowing it.
            agentRepository.findByProjectIdAndSlug(projectId, DefaultAgentSlugs.CEO).orElseThrow(() -> e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insertInNewTx(String projectId) {
        Agent agent = new Agent();
        agent.setProjectId(projectId);
        agent.setName(CEO_AGENT_NAME);
        agent.setSlug(DefaultAgentSlugs.CEO);
        agent.setDescription(CEO_AGENT_DESCRIPTION);
        agent.setProvider(CEO_AGENT_PROVIDER);
        String systemPrompt = readResource(SYSTEM_PROMPT_RESOURCE);
        agent.setSystemPrompt(systemPrompt);
        agent.setConfigJson(writeJson(Map.of(
                "runtime", "api",
                "maxToolTurns", CEO_MAX_TOOL_TURNS,
                "addressable", true,
                SEEDED_PROMPT_HASH_CONFIG_KEY, sha256Hex(systemPrompt))));
        agent.setToolIds(writeJson(CEO_TOOL_IDS));
        agent.setState("ACTIVE");
        agent.setAvatarEmoji(AgentAvatarDefaults.defaultEmoji(DefaultAgentSlugs.CEO));
        agent.setAvatarColor(AgentAvatarDefaults.defaultColor(DefaultAgentSlugs.CEO));
        agentRepository.save(agent);
        log.info("Provisioned '{}' agent for project {}", DefaultAgentSlugs.CEO, projectId);
    }

    /** Additively backfills any of {@link #CEO_TOOL_IDS} missing from a pre-existing CEO agent (e.g. one
     *  seeded before a coordinator tool existed) -- adds only what's missing, preserving any custom tool
     *  ids an operator added on top. Mirrors {@code KnowledgeWorkflowProvisioner#backfillToolIdsIfMissing}
     *  exactly, including its limitation: a deliberate operator removal of a default tool id is
     *  indistinguishable from "never had it" and gets silently re-added on the next call. */
    private void backfillToolIdsIfMissing(Agent agent) {
        List<String> current;
        try {
            current = objectMapper.readValue(agent.getToolIds(), new TypeReference<List<String>>() { });
        } catch (Exception e) {
            current = new ArrayList<>();
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>(current);
        boolean changed = merged.addAll(CEO_TOOL_IDS);
        if (!changed) {
            return;
        }
        agent.setToolIds(writeJson(new ArrayList<>(merged)));
        agentRepository.save(agent);
        log.info("Backfilled tool ids for '{}' agent in project {}", DefaultAgentSlugs.CEO, agent.getProjectId());
    }

    /** Backfills {@code runtime}/{@code maxToolTurns}/{@code addressable} onto a pre-existing CEO agent's
     *  {@code configJson}, one key at a time -- only a key that is entirely absent is added; an operator
     *  who changed a seeded value (e.g. {@code maxToolTurns: 40}) keeps it untouched forever. */
    private void backfillConfigDefaultsIfMissing(Agent agent) {
        Map<String, Object> config = readConfig(agent);
        Map<String, Object> updated = new LinkedHashMap<>(config);
        boolean changed = false;
        if (!updated.containsKey("runtime")) {
            updated.put("runtime", "api");
            changed = true;
        }
        if (!updated.containsKey("maxToolTurns")) {
            updated.put("maxToolTurns", CEO_MAX_TOOL_TURNS);
            changed = true;
        }
        if (!updated.containsKey("addressable")) {
            updated.put("addressable", true);
            changed = true;
        }
        if (!changed) {
            return;
        }
        agent.setConfigJson(writeJson(updated));
        agentRepository.save(agent);
        log.info("Backfilled config defaults for '{}' agent in project {}", DefaultAgentSlugs.CEO, agent.getProjectId());
    }

    /**
     * Refreshes a pre-existing CEO agent's {@code systemPrompt} to the current classpath {@value
     * #SYSTEM_PROMPT_RESOURCE} -- but only while the stored prompt is still exactly what Conductor
     * shipped (byte-identical to the current resource, in {@link #HISTORICAL_CEO_PROMPT_HASHES}, or
     * matching the agent's own {@value #SEEDED_PROMPT_HASH_CONFIG_KEY} stamp). An operator edit makes
     * the prompt permanently theirs. Exact mirror of {@code
     * KnowledgeWorkflowProvisioner#backfillSystemPromptIfUnmodified} -- see its javadoc for the full
     * reasoning (including the "config replaced wholesale drops the stamp" fail-safe case).
     */
    private void backfillSystemPromptIfUnmodified(Agent agent) {
        String storedPrompt = agent.getSystemPrompt();
        if (storedPrompt == null) {
            return; // Should never happen for a CEO agent seeded by this class; defensive, not expected.
        }
        String currentPrompt = readResource(SYSTEM_PROMPT_RESOURCE);
        String currentHash = sha256Hex(currentPrompt);
        if (currentPrompt.equals(storedPrompt)) {
            stampSeededPromptHashIfMissing(agent, currentHash);
            return;
        }
        String storedHash = sha256Hex(storedPrompt);
        Map<String, Object> config = readConfig(agent);
        boolean isOurs = HISTORICAL_CEO_PROMPT_HASHES.contains(storedHash)
                || storedHash.equals(config.get(SEEDED_PROMPT_HASH_CONFIG_KEY));
        if (!isOurs) {
            log.debug("Leaving operator-edited system prompt alone for '{}' agent in project {}",
                    DefaultAgentSlugs.CEO, agent.getProjectId());
            return;
        }
        agent.setSystemPrompt(currentPrompt);
        Map<String, Object> updated = new LinkedHashMap<>(config);
        updated.put(SEEDED_PROMPT_HASH_CONFIG_KEY, currentHash);
        agent.setConfigJson(writeJson(updated));
        agentRepository.save(agent);
        log.info("Refreshed system prompt for '{}' agent in project {} (stored prompt matched a "
                + "previously-shipped version)", DefaultAgentSlugs.CEO, agent.getProjectId());
    }

    private void stampSeededPromptHashIfMissing(Agent agent, String currentHash) {
        Map<String, Object> config = readConfig(agent);
        if (currentHash.equals(config.get(SEEDED_PROMPT_HASH_CONFIG_KEY))) {
            return;
        }
        Map<String, Object> updated = new LinkedHashMap<>(config);
        updated.put(SEEDED_PROMPT_HASH_CONFIG_KEY, currentHash);
        agent.setConfigJson(writeJson(updated));
        agentRepository.save(agent);
        log.info("Stamped seededPromptHash for '{}' agent in project {}", DefaultAgentSlugs.CEO, agent.getProjectId());
    }

    private Map<String, Object> readConfig(Agent agent) {
        try {
            return objectMapper.readValue(agent.getConfigJson(), new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize CEO agent config", e);
        }
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
