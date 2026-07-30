package com.conductor.knowledge;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentAvatarDefaults;
import com.conductor.agent.AgentRepository;
import com.conductor.agent.DefaultAgentSlugs;
import com.conductor.entity.Project;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.knowledge.domain.KnowledgeDomain;
import com.conductor.knowledge.domain.KnowledgeDomainRepository;
import com.conductor.knowledge.domain.KnowledgeDomainState;
import com.conductor.knowledge.domain.KnowledgeDomainTemplates;
import com.conductor.knowledge.page.KnowledgePageRepository;
import com.conductor.knowledge.page.KnowledgePageService;
import com.conductor.knowledge.page.PageWrite;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.workflow.AgentRuntimeResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Idempotently provisions the two knowledge-domain system workflows (see
 * {@code src/main/resources/knowledge/*.yaml}), the {@code _schema.md} and {@code _curation.md} root
 * seed pages, and the domain registry (5 {@link KnowledgeDomain} rows --
 * engineering/product/marketing/finance/people -- plus each one's {@code <slug>/_schema.md} and
 * {@code <slug>/_curation.md} pages) for a project. Called from {@code ProjectSettingsService} on
 * every settings save that leaves knowledge enabled
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
    public static final String NARRATOR_WORKFLOW_NAME = "metrics-narrator";
    /** Alias for {@link DefaultAgentSlugs#KNOWLEDGE_LIBRARIAN} -- kept here too since callers/tests
     *  reference the provisioner for it. */
    public static final String LIBRARIAN_AGENT_SLUG = DefaultAgentSlugs.KNOWLEDGE_LIBRARIAN;
    /** Alias for {@link DefaultAgentSlugs#METRICS_ANALYST} -- see {@link #LIBRARIAN_AGENT_SLUG}. */
    public static final String METRICS_ANALYST_AGENT_SLUG = DefaultAgentSlugs.METRICS_ANALYST;

    private static final String SCHEMA_PAGE_PATH = "_schema.md";
    private static final String LIBRARIAN_RESOURCE = "/knowledge/knowledge-librarian.yaml";
    private static final String BOOTSTRAP_RESOURCE = "/knowledge/knowledge-bootstrap.yaml";
    /** Package-visible (not private) so {@link #isSystemWorkflowStale} callers outside this class --
     *  e.g. {@code MetricsNarratorDispatchService} -- can pass it without this class exposing a getter. */
    static final String NARRATOR_RESOURCE = "/knowledge/metrics-narrator.yaml";
    private static final String SCHEMA_RESOURCE = "/knowledge/_schema.md";
    private static final String CURATION_RESOURCE = "/knowledge/_curation.md";
    /** Shared with {@code KnowledgeDomainService}'s approval-time seed -- see
     *  {@link KnowledgeDomainTemplates}. */
    private static final String DOMAIN_CURATION_SKELETON_RESOURCE = "/knowledge/domains/_curation-skeleton.md";
    private static final String LIBRARIAN_SYSTEM_PROMPT_RESOURCE = "/knowledge/librarian-system-prompt.md";
    private static final String METRICS_ANALYST_SYSTEM_PROMPT_RESOURCE = "/knowledge/metrics-analyst-system-prompt.md";
    private static final String LIBRARIAN_AGENT_NAME = "Knowledge Librarian";
    private static final String METRICS_ANALYST_AGENT_NAME = "Metrics Analyst";
    private static final String LIBRARIAN_AGENT_PROVIDER = "claude";
    private static final String LIBRARIAN_AVATAR_EMOJI = "📚";
    private static final String LIBRARIAN_AVATAR_COLOR = "violet";
    private static final String METRICS_ANALYST_AVATAR_EMOJI = "📈";
    private static final String METRICS_ANALYST_AVATAR_COLOR = "teal";
    /** Shared by the librarian seed here and by {@code KnowledgeDomainService#createSpecialist} -- a
     *  specialist agent gets the same 6 tools as the generalist librarian. */
    public static final List<String> LIBRARIAN_TOOL_IDS = List.of(
            "knowledge:read_knowledge_pages", "knowledge:read_knowledge_sources",
            "knowledge:search_knowledge", "knowledge:write_knowledge_pages",
            "knowledge:list_knowledge_domains", "knowledge:suggest_knowledge_domain");
    /** Shared by the librarian seed here and by {@code KnowledgeDomainService#createSpecialist}. */
    public static final int LIBRARIAN_MAX_TOOL_TURNS = 40;
    private static final Actor PROVISIONER_ACTOR = new Actor("system", "knowledge-provisioner", null);

    /** {@code configJson} key stamped onto a librarian or specialist agent with the SHA-256 hex digest
     *  of the exact prompt text it was last (re)seeded with -- see {@link #backfillSystemPromptIfUnmodified}
     *  and {@code KnowledgeDomainService#createSpecialist}. Shared between the two so a maintenance sweep
     *  (or a human reading raw {@code configJson}) doesn't have to remember two different key names for
     *  the same concept. */
    public static final String SEEDED_PROMPT_HASH_CONFIG_KEY = "seededPromptHash";

    /**
     * SHA-256 (lowercase hex) of every {@code librarian-system-prompt.md} Conductor has ever shipped --
     * this, together with the {@value #SEEDED_PROMPT_HASH_CONFIG_KEY} stamp in {@code configJson}, is
     * what lets {@link #backfillSystemPromptIfUnmodified} recognize a pre-existing librarian's stored
     * prompt as "still exactly what Conductor shipped" (safe to refresh in place) even for an agent
     * seeded years before the stamp existed at all -- the stamp alone only ever records the *current*
     * hash going forward, so without this set every project provisioned before a given prompt rewrite
     * would keep that stale prompt forever, silently defeating the point of the rewrite.
     *
     * <p><b>Append the outgoing hash here, in the same commit as every future
     * {@code librarian-system-prompt.md} rewrite, and never remove an entry.</b> Skipping this step is
     * the one way this whole mechanism silently stops working: the next rewrite would then reach only
     * projects seeded (or previously refreshed) after *this* set was last updated, not any project still
     * on an older prompt.
     *
     * <ul>
     *   <li>v1 -- commit {@code ec5dfaa2} ("Knowledge Center Phase 1", #270): the original prompt.</li>
     *   <li>v2 -- commit {@code 8d388838} ("Domain-aware Knowledge Center", #288): superseded by the
     *       current (v3) rewrite that added {@code _curation.md} policy reading and
     *       {@code sourceIds}/{@code skipped} accounting for every source in a batch.</li>
     * </ul>
     *
     * <p>The current (v3) prompt's own hash is deliberately not listed here --
     * {@link #backfillSystemPromptIfUnmodified}'s "already equals the current classpath prompt"
     * short-circuit handles that case before this set is ever consulted.
     */
    private static final Set<String> HISTORICAL_LIBRARIAN_PROMPT_HASHES = Set.of(
            "d829d96221659a8bc71c0e2c11c87366e51fb499743794efdbd55434ee0678da", // v1 (ec5dfaa2)
            "427d5376ecd1191fe5496e17e77ac3ed2ab9d37f79fa0fbaf7264221dcbab286"); // v2 (8d388838)

    /** One registry row + schema page to seed, keyed by slug. {@code patterns} is the
     *  {@code sourceTypePatterns} routing escape hatch (Phase 2); empty for every domain but
     *  engineering, which claims GitHub-sourced material by default. */
    private record DomainSeed(String slug, String displayName, String description, List<String> patterns) {
        String pathPrefix() {
            return slug + "/";
        }

        String schemaPagePath() {
            return slug + "/_schema.md";
        }

        String resource() {
            return "/knowledge/domains/" + slug + ".md";
        }
    }

    private static final List<DomainSeed> DOMAIN_SEEDS = List.of(
            new DomainSeed("engineering", "Engineering",
                    "Architecture, runbooks, postmortems, engineering decisions, integrations.",
                    List.of("github.*")),
            new DomainSeed("product", "Product",
                    "Features, experiments, feedback synthesis.", List.of()),
            new DomainSeed("marketing", "Marketing",
                    "Campaigns, personas, positioning, competitors.", List.of()),
            new DomainSeed("finance", "Finance",
                    "Financial metrics and spend decisions.", List.of()),
            new DomainSeed("people", "People",
                    "Team members and meetings.", List.of()));

    private final WorkflowDefinitionRepository workflowRepository;
    private final ProjectRepository projectRepository;
    private final KnowledgePageRepository pageRepository;
    private final KnowledgePageService pageService;
    private final AgentRepository agentRepository;
    private final KnowledgeDomainRepository domainRepository;
    private final ObjectMapper objectMapper;

    public KnowledgeWorkflowProvisioner(WorkflowDefinitionRepository workflowRepository,
                                        ProjectRepository projectRepository,
                                        KnowledgePageRepository pageRepository,
                                        KnowledgePageService pageService,
                                        AgentRepository agentRepository,
                                        KnowledgeDomainRepository domainRepository,
                                        ObjectMapper objectMapper) {
        this.workflowRepository = workflowRepository;
        this.projectRepository = projectRepository;
        this.pageRepository = pageRepository;
        this.pageService = pageService;
        this.agentRepository = agentRepository;
        this.domainRepository = domainRepository;
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
        seedMetricsAnalystAgent(projectId);
        upsertWorkflow(project, LIBRARIAN_WORKFLOW_NAME, LIBRARIAN_RESOURCE);
        upsertWorkflow(project, BOOTSTRAP_WORKFLOW_NAME, BOOTSTRAP_RESOURCE);
        upsertWorkflow(project, NARRATOR_WORKFLOW_NAME, NARRATOR_RESOURCE);
        seedSchemaPage(projectId);
        seedCurationPage(projectId);
        seedDomainRegistry(projectId);
        seedDomainSchemaPages(projectId);
        seedDomainCurationPages(projectId);
    }

    private void seedLibrarianAgent(String projectId) {
        Optional<Agent> existing = agentRepository.findByProjectIdAndSlug(projectId, LIBRARIAN_AGENT_SLUG);
        if (existing.isPresent()) {
            backfillAvatarIfMissing(existing.get());
            backfillToolIdsIfMissing(existing.get());
            backfillRuntimePinIfMissing(existing.get());
            backfillSystemPromptIfUnmodified(existing.get());
            return;
        }
        Agent agent = new Agent();
        agent.setProjectId(projectId);
        agent.setName(LIBRARIAN_AGENT_NAME);
        agent.setSlug(LIBRARIAN_AGENT_SLUG);
        agent.setDescription("Files knowledge-inbox sources into the wiki.");
        agent.setProvider(LIBRARIAN_AGENT_PROVIDER);
        String systemPrompt = readResource(LIBRARIAN_SYSTEM_PROMPT_RESOURCE);
        agent.setSystemPrompt(systemPrompt);
        // Pinned rather than left to AgentRuntimeResolver auto-detection: the librarian's multi-step
        // filing task (read schema, search, batch-write with conflict retry) is written against the
        // Claude Code tool-calling loop, so which runtime it lands on must not silently flip with a
        // project's credential mix (e.g. an Anthropic API key present but no Claude Code subscription).
        // seededPromptHash records what was just seeded -- see backfillSystemPromptIfUnmodified.
        agent.setConfigJson(writeJson(Map.of(
                "maxToolTurns", LIBRARIAN_MAX_TOOL_TURNS,
                "runtime", AgentRuntimeResolver.RUNTIME_CLAUDE_CODE,
                SEEDED_PROMPT_HASH_CONFIG_KEY, sha256Hex(systemPrompt))));
        agent.setToolIds(writeJson(LIBRARIAN_TOOL_IDS));
        agent.setState("ACTIVE");
        agent.setAvatarEmoji(LIBRARIAN_AVATAR_EMOJI);
        agent.setAvatarColor(LIBRARIAN_AVATAR_COLOR);
        agentRepository.save(agent);
        log.info("Provisioned '{}' agent for project {}", LIBRARIAN_AGENT_SLUG, projectId);
    }

    /**
     * Seeds the {@value #METRICS_ANALYST_AGENT_SLUG} {@code Agent} the metrics-narrator workflow's
     * {@code uses: agent} step resolves at dispatch time -- with {@code toolIds: []}, deliberately.
     * This is a security-relevant design point, not an oversight: the narrator is a pure text function
     * from a pre-computed digest JSON payload to prose. With zero tools it physically cannot write wiki
     * pages, cannot submit knowledge sources, and cannot re-fetch the raw metrics/series the digest
     * pipeline ({@code MetricsAggregator}/{@code MetricsChangeDetector}/{@code DigestPayloadBuilder})
     * deliberately stripped out before handing it this task. Filing the resulting narrative into the
     * Knowledge Center is {@code DigestSubmissionService}'s job, done by the platform reading the run's
     * structured output -- never the agent itself. No backfill counterpart to
     * {@link #backfillToolIdsIfMissing} exists here on purpose: unlike the librarian's tool list, which
     * only ever grows, this agent's tool list must stay empty forever, so there is nothing to backfill.
     */
    private void seedMetricsAnalystAgent(String projectId) {
        if (agentRepository.findByProjectIdAndSlug(projectId, METRICS_ANALYST_AGENT_SLUG).isPresent()) {
            return;
        }
        Agent agent = new Agent();
        agent.setProjectId(projectId);
        agent.setName(METRICS_ANALYST_AGENT_NAME);
        agent.setSlug(METRICS_ANALYST_AGENT_SLUG);
        agent.setDescription("Narrates connector metric digests into short wiki updates. No tools.");
        agent.setProvider(LIBRARIAN_AGENT_PROVIDER);
        agent.setSystemPrompt(readResource(METRICS_ANALYST_SYSTEM_PROMPT_RESOURCE));
        // Pinned for the same reason as the librarian -- see seedLibrarianAgent's comment.
        agent.setConfigJson(writeJson(Map.of("runtime", AgentRuntimeResolver.RUNTIME_CLAUDE_CODE)));
        agent.setToolIds(writeJson(List.of()));
        agent.setState("ACTIVE");
        agent.setAvatarEmoji(METRICS_ANALYST_AVATAR_EMOJI);
        agent.setAvatarColor(METRICS_ANALYST_AVATAR_COLOR);
        agentRepository.save(agent);
        log.info("Provisioned '{}' agent for project {}", METRICS_ANALYST_AGENT_SLUG, projectId);
    }

    /** Backfills the avatar on a pre-existing librarian seeded before {@code avatarEmoji} existed. */
    private void backfillAvatarIfMissing(Agent agent) {
        if (agent.getAvatarEmoji() != null) {
            return;
        }
        agent.setAvatarEmoji(LIBRARIAN_AVATAR_EMOJI);
        agent.setAvatarColor(LIBRARIAN_AVATAR_COLOR);
        agentRepository.save(agent);
        log.info("Backfilled avatar for '{}' agent in project {}", LIBRARIAN_AGENT_SLUG, agent.getProjectId());
    }

    /** Backfills any of {@link #LIBRARIAN_TOOL_IDS} missing from a pre-existing librarian agent (e.g.
     *  one seeded before {@code list_knowledge_domains}/{@code suggest_knowledge_domain} existed) --
     *  adds only what's missing, preserving any custom tool ids an operator added on top. */
    private void backfillToolIdsIfMissing(Agent agent) {
        List<String> current;
        try {
            current = objectMapper.readValue(agent.getToolIds(), new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
        } catch (Exception e) {
            current = new ArrayList<>();
        }
        java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(current);
        boolean changed = merged.addAll(LIBRARIAN_TOOL_IDS);
        if (!changed) {
            return;
        }
        agent.setToolIds(writeJson(new ArrayList<>(merged)));
        agentRepository.save(agent);
        log.info("Backfilled tool ids for '{}' agent in project {}", LIBRARIAN_AGENT_SLUG, agent.getProjectId());
    }

    /** Backfills the {@code claude-code} runtime pin onto a pre-existing librarian agent seeded before
     *  the pin existed -- leaves any other explicit pin an operator may have set untouched. */
    private void backfillRuntimePinIfMissing(Agent agent) {
        Map<String, Object> config = readConfig(agent);
        if (config.containsKey("runtime")) {
            return;
        }
        Map<String, Object> updated = new LinkedHashMap<>(config);
        updated.put("runtime", AgentRuntimeResolver.RUNTIME_CLAUDE_CODE);
        agent.setConfigJson(writeJson(updated));
        agentRepository.save(agent);
        log.info("Backfilled runtime pin for '{}' agent in project {}", LIBRARIAN_AGENT_SLUG, agent.getProjectId());
    }

    /**
     * Refreshes a pre-existing librarian agent's {@code systemPrompt} to the current classpath
     * {@value #LIBRARIAN_SYSTEM_PROMPT_RESOURCE} -- but only while the stored prompt is still exactly
     * what Conductor shipped. This is the mechanism that lets a librarian prompt rewrite actually reach
     * *existing* projects: without it, {@link #seedLibrarianAgent}'s early return for an already-existing
     * agent means every project provisioned before the rewrite keeps the old prompt forever.
     *
     * <p>"Still what Conductor shipped" is checked, in order:
     * <ol>
     *   <li>Byte-identical to the current classpath prompt already -- nothing to refresh; just make sure
     *       the {@value #SEEDED_PROMPT_HASH_CONFIG_KEY} stamp is present (an agent may be at the current
     *       prompt text without ever having been stamped, e.g. seeded before stamping shipped at all, or
     *       stamped then had {@code configJson} replaced wholesale -- see the second limitation below).</li>
     *   <li>Otherwise, hash the *stored* prompt and check it against {@link #HISTORICAL_LIBRARIAN_PROMPT_HASHES}
     *       (covers agents seeded before stamping existed) or against the agent's own
     *       {@value #SEEDED_PROMPT_HASH_CONFIG_KEY} stamp (covers agents seeded after). Either match means
     *       the stored prompt is exactly some version Conductor shipped and it is safe to overwrite with
     *       the current one, re-stamping the hash.</li>
     * </ol>
     * If neither matches, an operator has edited the prompt -- it is now permanently theirs; this method
     * leaves it byte-identical (logged at debug, since "nothing to do" here is the common, expected case,
     * not a noteworthy event).
     *
     * <p><b>Two limitations, deliberately not solved here:</b>
     * <ul>
     *   <li>{@code AgentService#update} replaces {@code configJson} wholesale whenever a request includes
     *       a {@code config} field (see its javadoc), so an operator editing agent config through
     *       Automation -&gt; Agents in the UI (the promise in {@code docs/knowledge.md}) can drop the
     *       {@value #SEEDED_PROMPT_HASH_CONFIG_KEY} stamp even if they never touched the prompt text
     *       itself. This fails *safe*: with the stamp gone, this method falls back to
     *       {@link #HISTORICAL_LIBRARIAN_PROMPT_HASHES}, which still recognizes an unchanged prompt as
     *       ours and simply re-stamps it -- the only loss is a redundant hash computation, never an
     *       incorrect overwrite of prompt text.</li>
     *   <li>This hash-set approach is librarian-specific and cannot generalize to specialist agents (see
     *       {@code KnowledgeDomainService#createSpecialist}): a specialist's stored prompt is
     *       {@code specialist-system-prompt.md} with {@code %DOMAIN_SLUG%}/{@code %DOMAIN_DISPLAY%}
     *       already substituted per domain, so no single shipped-template digest could ever match a
     *       stored value across domains. Specialists stamp and refresh against their own rendered-prompt
     *       hash directly (no historical set); pre-existing, never-stamped specialists get no automatic
     *       backfill at all -- see that method's javadoc for why that's an accepted gap, not an oversight.</li>
     * </ul>
     */
    private void backfillSystemPromptIfUnmodified(Agent agent) {
        String storedPrompt = agent.getSystemPrompt();
        if (storedPrompt == null) {
            return; // Should never happen for a librarian seeded by this class; defensive, not expected.
        }
        String currentPrompt = readResource(LIBRARIAN_SYSTEM_PROMPT_RESOURCE);
        String currentHash = sha256Hex(currentPrompt);
        if (currentPrompt.equals(storedPrompt)) {
            stampSeededPromptHashIfMissing(agent, currentHash);
            return;
        }
        String storedHash = sha256Hex(storedPrompt);
        Map<String, Object> config = readConfig(agent);
        boolean isOurs = HISTORICAL_LIBRARIAN_PROMPT_HASHES.contains(storedHash)
                || storedHash.equals(config.get(SEEDED_PROMPT_HASH_CONFIG_KEY));
        if (!isOurs) {
            log.debug("Leaving operator-edited system prompt alone for '{}' agent in project {}",
                    LIBRARIAN_AGENT_SLUG, agent.getProjectId());
            return;
        }
        agent.setSystemPrompt(currentPrompt);
        Map<String, Object> updated = new LinkedHashMap<>(config);
        updated.put(SEEDED_PROMPT_HASH_CONFIG_KEY, currentHash);
        agent.setConfigJson(writeJson(updated));
        agentRepository.save(agent);
        log.info("Refreshed system prompt for '{}' agent in project {} (stored prompt matched a "
                + "previously-shipped version)", LIBRARIAN_AGENT_SLUG, agent.getProjectId());
    }

    /** Stamps {@value #SEEDED_PROMPT_HASH_CONFIG_KEY} onto {@code agent} if it isn't already set to
     *  {@code currentHash} -- called both when the stored prompt already matches the current classpath
     *  prompt (nothing to refresh, but the bookkeeping stamp may still be missing or stale) and, via
     *  {@link #backfillSystemPromptIfUnmodified}, after an actual refresh. */
    private void stampSeededPromptHashIfMissing(Agent agent, String currentHash) {
        Map<String, Object> config = readConfig(agent);
        if (currentHash.equals(config.get(SEEDED_PROMPT_HASH_CONFIG_KEY))) {
            return;
        }
        Map<String, Object> updated = new LinkedHashMap<>(config);
        updated.put(SEEDED_PROMPT_HASH_CONFIG_KEY, currentHash);
        agent.setConfigJson(writeJson(updated));
        agentRepository.save(agent);
        log.info("Stamped seededPromptHash for '{}' agent in project {}", LIBRARIAN_AGENT_SLUG, agent.getProjectId());
    }

    /** Shared {@code configJson} read used by {@link #backfillRuntimePinIfMissing},
     *  {@link #backfillSystemPromptIfUnmodified}, and {@link #stampSeededPromptHashIfMissing} -- an
     *  unparseable or absent value (e.g. a never-configured agent) reads as an empty map rather than
     *  failing provisioning. */
    private Map<String, Object> readConfig(Agent agent) {
        try {
            return objectMapper.readValue(agent.getConfigJson(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** SHA-256 hex digest of {@code content}, used only to fingerprint librarian/specialist system
     *  prompts against {@link #HISTORICAL_LIBRARIAN_PROMPT_HASHES} / {@value #SEEDED_PROMPT_HASH_CONFIG_KEY}.
     *  {@code KnowledgePageService} and {@code KnowledgeSignalSink} each already have a private helper
     *  doing the same digest for their own unrelated hashing (page content-addressing, event dedup) --
     *  neither is public or otherwise reachable from this package, and extracting a shared three-line
     *  utility for them is disproportionate to this change, so this is a third, deliberately small, copy. */
    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize librarian agent config", e);
        }
    }

    /**
     * Creates the workflow if absent, or -- since these are system-owned, canonical content, unlike the
     * seed-if-absent wiki schema pages below -- refreshes its stored YAML in place if it has drifted
     * from the current classpath resource (e.g. a project enabled before {@code agent: ${{ event.agentSlug }}}
     * shipped). {@code LibrarianDispatchService} treats this drift as incomplete seeding and calls
     * {@link #provision} to trigger the refresh before dispatching into a stale workflow.
     *
     * <p>A refresh here is not synchronized with any already-running librarian job: a run whose engine
     * re-parses the live YAML mid-execution (e.g. it reaches the {@code agent} step after this method
     * updated the row) reads the new template against the *old* dispatch payload -- if that payload
     * predates {@code agentSlug} (a pre-deploy run), {@code with.agent: ${{ event.agentSlug }}} then
     * interpolates to empty and the step fails. This is self-correcting at the cost of one attempt: the
     * scheduler's stale-processing sweep resurrects the batch, and the retry is dispatched with a fresh
     * payload against the now-refreshed YAML.
     */
    private void upsertWorkflow(Project project, String name, String resource) {
        String classpathYaml = readResource(resource);
        Optional<WorkflowDefinition> existing = workflowRepository.findByProjectIdAndName(project.getId(), name);
        if (existing.isPresent()) {
            WorkflowDefinition def = existing.get();
            if (!classpathYaml.equals(def.getYaml())) {
                def.setYaml(classpathYaml);
                workflowRepository.save(def);
                log.info("Refreshed drifted system workflow '{}' YAML for project {}", name, project.getId());
            }
            return;
        }
        WorkflowDefinition def = new WorkflowDefinition();
        def.setProject(project);
        def.setName(name);
        def.setYaml(classpathYaml);
        def.setEnabled(true);
        def.setArea("knowledge");
        workflowRepository.save(def);
        log.info("Provisioned system workflow '{}' for project {}", name, project.getId());
    }

    /**
     * True if the project's stored {@code knowledge-librarian} workflow YAML differs from the current
     * classpath resource -- {@code LibrarianDispatchService} treats drift as "seeding incomplete" and
     * self-heals via {@link #provision} (whose {@link #upsertWorkflow} refreshes drifted YAML in place)
     * before dispatching into a stale target. A missing workflow row entirely is not "stale" -- that's
     * the caller's separate empty-optional check.
     */
    public boolean isLibrarianWorkflowStale(String projectId) {
        return isSystemWorkflowStale(projectId, LIBRARIAN_WORKFLOW_NAME, LIBRARIAN_RESOURCE);
    }

    /**
     * General form of {@link #isLibrarianWorkflowStale}: true if the project's stored {@code name}
     * workflow YAML differs from the current classpath {@code resource} -- any system-workflow
     * dispatch service (e.g. {@code MetricsNarratorDispatchService}) treats drift as "seeding
     * incomplete" and self-heals via {@link #provision} the same way {@code LibrarianDispatchService}
     * does. A missing workflow row entirely is not "stale" -- that's the caller's separate
     * empty-optional check.
     */
    public boolean isSystemWorkflowStale(String projectId, String name, String resource) {
        return workflowRepository.findByProjectIdAndName(projectId, name)
                .map(w -> !readResource(resource).equals(w.getYaml()))
                .orElse(false);
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

    /** Seeds the root {@value KnowledgeCurationPaths#ROOT} page -- seed-if-absent, same as
     *  {@link #seedSchemaPage}: this is a human-owned policy page (the veto list a librarian reads
     *  every batch), not canonical system content, so it is never refreshed or overwritten once it
     *  exists, even if the classpath resource later changes. */
    private void seedCurationPage(String projectId) {
        if (pageRepository.findByProjectIdAndPath(projectId, KnowledgeCurationPaths.ROOT).isPresent()) {
            return;
        }
        String content = readResource(CURATION_RESOURCE);
        pageService.batchWrite(projectId, List.of(new PageWrite(KnowledgeCurationPaths.ROOT, content, null, false)),
                List.of(), PROVISIONER_ACTOR);
        log.info("Seeded {} for project {}", KnowledgeCurationPaths.ROOT, projectId);
    }

    /** Seeds the 5 domain registry rows (by natural key {@code (projectId, slug)}) that don't already
     *  exist -- a surviving row (e.g. one an admin edited) is left untouched, never reset. */
    private void seedDomainRegistry(String projectId) {
        for (DomainSeed seed : DOMAIN_SEEDS) {
            if (domainRepository.findByProjectIdAndSlug(projectId, seed.slug()).isPresent()) {
                continue;
            }
            KnowledgeDomain domain = new KnowledgeDomain();
            domain.setProjectId(projectId);
            domain.setSlug(seed.slug());
            domain.setDisplayName(seed.displayName());
            domain.setDescription(seed.description());
            domain.setPathPrefix(seed.pathPrefix());
            domain.setSchemaPagePath(seed.schemaPagePath());
            domain.setSourceTypePatterns(seed.patterns());
            domain.setState(KnowledgeDomainState.ACTIVE);
            domainRepository.save(domain);
            log.info("Provisioned '{}' knowledge domain for project {}", seed.slug(), projectId);
        }
    }

    /** Seeds whichever domain {@code <slug>/_schema.md} pages are missing, in one batch -- pages are
     *  agent/user-editable, so (unlike the system workflow YAML) this is seed-if-absent only; an
     *  existing page (even a stale one) is never overwritten. */
    private void seedDomainSchemaPages(String projectId) {
        List<PageWrite> writes = new ArrayList<>();
        for (DomainSeed seed : DOMAIN_SEEDS) {
            if (pageRepository.findByProjectIdAndPath(projectId, seed.schemaPagePath()).isPresent()) {
                continue;
            }
            writes.add(new PageWrite(seed.schemaPagePath(), readResource(seed.resource()), null, false));
        }
        if (writes.isEmpty()) {
            return;
        }
        pageService.batchWrite(projectId, writes, List.of(), PROVISIONER_ACTOR);
        log.info("Seeded {} domain schema page(s) for project {}", writes.size(), projectId);
    }

    /**
     * Seeds whichever domain {@code <slug>/_curation.md} pages are missing, in one batch -- seed-if-
     * absent only, same reasoning as {@link #seedDomainSchemaPages}: these are human-owned policy pages,
     * never overwritten once they exist.
     *
     * <p><b>Deliberately iterates the live {@link #domainRepository} registry (ACTIVE rows), not
     * {@link #DOMAIN_SEEDS}</b> -- unlike {@link #seedDomainSchemaPages}, which iterates
     * {@code DOMAIN_SEEDS} and therefore can never reach a domain approved later from a librarian gap
     * report (that domain isn't in the hardcoded list and never will be). If curation seeding copied
     * that pattern, an approved gap-report domain would never get a curation page from any code path --
     * {@link KnowledgeDomainService}'s approval-time seed only fires on the SUGGESTED-&gt;ACTIVE
     * transition itself, so a domain approved before this seeding existed, or whose seed failed
     * transiently, would be stuck without one forever. Driving this off the registry instead means
     * {@link #provision} (called on every settings save that leaves knowledge enabled, and
     * just-in-time before dispatch) self-heals every ACTIVE domain uniformly, regardless of how or when
     * it became ACTIVE. Do not "simplify" this back to {@code DOMAIN_SEEDS} -- that would silently
     * reintroduce the gap.
     *
     * <p>All domains render the same {@link #DOMAIN_CURATION_SKELETON_RESOURCE} template (unlike domain
     * schema pages, where the 5 seeded domains get bespoke per-domain content and only gap-report
     * domains get a generic skeleton) -- curation policy starts identical everywhere and accretes
     * per-domain rules over time via the "Not worth filing" action, so there is no bespoke starting
     * content to write per domain.
     */
    private void seedDomainCurationPages(String projectId) {
        List<KnowledgeDomain> activeDomains =
                domainRepository.findByProjectIdAndStateOrderBySlugAsc(projectId, KnowledgeDomainState.ACTIVE);
        String template = readResource(DOMAIN_CURATION_SKELETON_RESOURCE);
        List<PageWrite> writes = new ArrayList<>();
        for (KnowledgeDomain domain : activeDomains) {
            String path = KnowledgeCurationPaths.forDomain(domain);
            if (pageRepository.findByProjectIdAndPath(projectId, path).isPresent()) {
                continue;
            }
            writes.add(new PageWrite(path, KnowledgeDomainTemplates.render(template, domain), null, false));
        }
        if (writes.isEmpty()) {
            return;
        }
        pageService.batchWrite(projectId, writes, List.of(), PROVISIONER_ACTOR);
        log.info("Seeded {} domain curation page(s) for project {}", writes.size(), projectId);
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
