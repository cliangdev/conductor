package com.conductor.knowledge.domain;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentAvatarDefaults;
import com.conductor.agent.AgentRepository;
import com.conductor.exception.BusinessException;
import com.conductor.knowledge.Actor;
import com.conductor.knowledge.KnowledgeCurationPaths;
import com.conductor.knowledge.KnowledgeWorkflowProvisioner;
import com.conductor.knowledge.page.KnowledgePageRepository;
import com.conductor.knowledge.page.KnowledgePageService;
import com.conductor.knowledge.page.PageWrite;
import com.conductor.workflow.AgentRuntimeResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * CRUD + owning-agent assignment + gap-report lifecycle for the Knowledge Center's domain registry (see
 * {@code docs/knowledge.md}). Routing (which domain a submitted source lands in) and dispatch (which
 * agent runs against it) are separate concerns owned by Phase 2's {@code KnowledgeDomainResolver} /
 * {@code LibrarianDispatchService} -- this service only manages the registry rows themselves (and, for
 * specialist creation, the specialist {@code Agent} row that gets assigned to one).
 *
 * <p>Owning-agent validation and specialist creation go through {@link AgentRepository} directly rather
 * than {@code AgentService} -- same bean-cycle precedent as {@code KnowledgeWorkflowProvisioner}
 * (AgentService -&gt; AgentToolRegistry -&gt; KnowledgeToolProvider -&gt; ... -&gt; the knowledge package).
 */
@Service
public class KnowledgeDomainService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDomainService.class);

    /** Must form a valid page-path segment (see {@code KnowledgePageService#normalizePath}) since it
     *  becomes both {@code pathPrefix} and part of {@code schemaPagePath}. */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    /** Match the {@code knowledge_domains} column widths -- guarded here so an oversized value is a
     *  400 (BusinessException) at the service boundary, not a commit-time DB truncation/constraint error. */
    private static final int SLUG_MAX_LENGTH = 64;
    private static final int DISPLAY_NAME_MAX_LENGTH = 255;

    private static final String SKELETON_RESOURCE = "/knowledge/domains/_suggested-skeleton.md";
    /** Shared with {@code KnowledgeWorkflowProvisioner}'s registry-driven seed -- see
     *  {@link KnowledgeDomainTemplates}. */
    private static final String CURATION_SKELETON_RESOURCE = "/knowledge/domains/_curation-skeleton.md";
    private static final String SPECIALIST_SYSTEM_PROMPT_RESOURCE = "/knowledge/specialist-system-prompt.md";
    private static final String SPECIALIST_AGENT_PROVIDER = "claude";
    private static final Actor SYSTEM_ACTOR = new Actor("system", "knowledge-domain-service", null);

    private final KnowledgeDomainRepository domainRepository;
    private final AgentRepository agentRepository;
    private final KnowledgePageRepository pageRepository;
    private final KnowledgePageService pageService;
    private final ObjectMapper objectMapper;

    /** Self-reference so the {@code REQUIRES_NEW} claim insert in {@link #suggest} runs through the
     *  Spring proxy -- mirrors {@code KnowledgeIngestionService#self}. */
    @Autowired
    @Lazy
    KnowledgeDomainService self;

    public KnowledgeDomainService(KnowledgeDomainRepository domainRepository, AgentRepository agentRepository,
            KnowledgePageRepository pageRepository, KnowledgePageService pageService, ObjectMapper objectMapper) {
        this.domainRepository = domainRepository;
        this.agentRepository = agentRepository;
        this.pageRepository = pageRepository;
        this.pageService = pageService;
        this.objectMapper = objectMapper;
    }

    /** All domains for a project, slug-ordered (stable, deterministic display order). */
    @Transactional(readOnly = true)
    public List<KnowledgeDomain> list(String projectId) {
        return domainRepository.findByProjectIdOrderBySlugAsc(projectId);
    }

    /**
     * Updates the editable metadata fields; a null argument leaves that field unchanged. To transition
     * {@code state} (e.g. approving a {@code SUGGESTED} or re-approving a {@code DISMISSED} domain),
     * pass the target state -- a transition to {@code ACTIVE} from any other state also seeds a generic
     * skeleton {@code <slug>/_schema.md} page and a generic skeleton {@code <slug>/_curation.md} page if
     * either isn't already there (an admin approving a gap report shouldn't be left with a domain that
     * has nowhere for the librarian to file anything, or no area-specific curation policy; both seeds
     * are if-absent, so re-approving a domain that already has either page is a no-op for that page).
     * Owning-agent assignment is a separate operation -- see {@link #updateOwningAgent} -- since a null
     * {@code owningAgentSlug} there means "clear it", which would be ambiguous alongside this method's
     * "null means unchanged" convention for every other field. Called directly, this method's own
     * {@code @Transactional} is the atomicity boundary; called via {@link #applyPatch} (the PATCH
     * endpoint's entry point), it simply executes inside that method's already-open transaction.
     */
    @Transactional
    public KnowledgeDomain update(String projectId, String slug, String displayName, String description,
            List<String> sourceTypePatterns, KnowledgeDomainState state) {
        validateDisplayNameLength(displayName);
        validateSourceTypePatterns(sourceTypePatterns);
        KnowledgeDomain domain = findRequired(projectId, slug);
        KnowledgeDomainState previousState = domain.getState();
        if (displayName != null) {
            domain.setDisplayName(displayName);
        }
        if (description != null) {
            domain.setDescription(description);
        }
        if (sourceTypePatterns != null) {
            domain.setSourceTypePatterns(sourceTypePatterns);
        }
        if (state != null) {
            domain.setState(state);
        }
        KnowledgeDomain saved = domainRepository.save(domain);
        if (previousState != KnowledgeDomainState.ACTIVE && saved.getState() == KnowledgeDomainState.ACTIVE) {
            seedSkeletonSchemaPageIfAbsent(projectId, saved);
            seedSkeletonCurationPageIfAbsent(projectId, saved);
        }
        return saved;
    }

    /**
     * Assigns or clears the domain's owning specialist agent. {@code owningAgentSlug == null} clears the
     * assignment (dispatch falls back to the generalist librarian); a non-null value must be an existing
     * agent slug in this project, since a dangling reference would otherwise silently never fire.
     */
    @Transactional
    public KnowledgeDomain updateOwningAgent(String projectId, String slug, String owningAgentSlug) {
        KnowledgeDomain domain = findRequired(projectId, slug);
        if (owningAgentSlug != null && !agentRepository.existsByProjectIdAndSlug(projectId, owningAgentSlug)) {
            throw new BusinessException("No agent with slug '" + owningAgentSlug + "' in this project");
        }
        domain.setOwningAgentSlug(owningAgentSlug);
        return domainRepository.save(domain);
    }

    /**
     * Composes {@link #update} and {@link #updateOwningAgent} in a single transaction -- the PATCH
     * endpoint's entry point, so a request carrying both a metadata change and an invalid
     * {@code owningAgentSlug} rolls back entirely instead of leaving the metadata change committed
     * while the agent assignment 400s. {@code clearOwningAgent} takes precedence over
     * {@code owningAgentSlug} (mirrors {@code updateOwningAgent}'s nullable-to-clear contract); neither
     * set leaves the owning-agent assignment untouched.
     */
    @Transactional
    public KnowledgeDomain applyPatch(String projectId, String slug, String displayName, String description,
            List<String> sourceTypePatterns, KnowledgeDomainState state, boolean clearOwningAgent, String owningAgentSlug) {
        KnowledgeDomain domain = update(projectId, slug, displayName, description, sourceTypePatterns, state);
        if (clearOwningAgent) {
            domain = updateOwningAgent(projectId, slug, null);
        } else if (owningAgentSlug != null) {
            domain = updateOwningAgent(projectId, slug, owningAgentSlug);
        }
        return domain;
    }

    /**
     * Claim-or-return gap report. The insert itself runs in {@link #insertSuggestedInNewTx}, a nested
     * {@code REQUIRES_NEW} transaction that {@code saveAndFlush}es -- forcing the INSERT to execute (and
     * any unique-constraint violation to surface) synchronously, inside this method's try block, rather
     * than only at the enclosing transaction's commit, well after this method has already returned a
     * result to the caller. The first caller for a {@code (projectId, slug)} pair gets a new
     * {@code SUGGESTED} row; any later caller (regardless of the row's current state -- {@code ACTIVE},
     * still {@code SUGGESTED}, or {@code DISMISSED}) gets the existing row back with {@code created =
     * false} instead of erroring or resetting it. A {@code DISMISSED} return is the signal to the caller
     * (an agent calling {@code suggest_knowledge_domain}) that this was already declined and shouldn't
     * be re-suggested.
     */
    public SuggestResult suggest(String projectId, String slug, String displayName, String description,
            String reason, List<String> sourceTypePatterns, String suggestedBy) {
        validateSlug(slug);
        validateDisplayName(displayName, true);
        validateSourceTypePatterns(sourceTypePatterns);

        Optional<KnowledgeDomain> existing = domainRepository.findByProjectIdAndSlug(projectId, slug);
        if (existing.isPresent()) {
            return new SuggestResult(existing.get(), false);
        }
        try {
            KnowledgeDomain created = self.insertSuggestedInNewTx(projectId, slug, displayName, description,
                    reason, sourceTypePatterns, suggestedBy);
            return new SuggestResult(created, true);
        } catch (DataIntegrityViolationException e) {
            // Lost a race on the (project_id, slug) unique constraint -- the winning row exists.
            return domainRepository.findByProjectIdAndSlug(projectId, slug)
                    .map(d -> new SuggestResult(d, false))
                    .orElseThrow(() -> e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public KnowledgeDomain insertSuggestedInNewTx(String projectId, String slug, String displayName, String description,
            String reason, List<String> sourceTypePatterns, String suggestedBy) {
        KnowledgeDomain domain = new KnowledgeDomain();
        domain.setProjectId(projectId);
        domain.setSlug(slug);
        domain.setDisplayName(displayName);
        domain.setDescription(description);
        domain.setPathPrefix(slug + "/");
        domain.setSchemaPagePath(slug + "/_schema.md");
        domain.setSourceTypePatterns(sourceTypePatterns != null ? sourceTypePatterns : List.of());
        domain.setState(KnowledgeDomainState.SUGGESTED);
        domain.setSuggestedBy(suggestedBy);
        domain.setSuggestionReason(reason);
        return domainRepository.saveAndFlush(domain);
    }

    /** Result of {@link #suggest}: the claimed-or-existing row, and whether this call is the one that created it. */
    public record SuggestResult(KnowledgeDomain domain, boolean created) {
    }

    /**
     * Creates (if absent) a specialist {@code Agent} for this domain -- slug {@code knowledge-<slug>},
     * the same knowledge tools and {@code maxToolTurns} as the generalist librarian, a domain-focused
     * system prompt (see {@code specialist-system-prompt.md}), and a deterministic avatar -- then assigns
     * it as the domain's {@code owningAgentSlug} via {@link #updateOwningAgent}, in one transaction.
     * Idempotent: if the agent already exists (e.g. this was called before and the agent survived, or an
     * operator pre-created it under this exact slug), this just (re-)assigns it -- it never duplicates
     * the Agent row. Deliberately not added to {@code DefaultAgentSlugs}: specialists are user-initiated,
     * not self-healing -- deleting one simply clears the assignment and dispatch falls back to the
     * generalist librarian (see {@code LibrarianDispatchService}).
     */
    @Transactional
    public KnowledgeDomain createSpecialist(String projectId, String slug) {
        KnowledgeDomain domain = findRequired(projectId, slug);
        // No schema/curation page seeding here (unlike the SUGGESTED->ACTIVE branch in update()): this
        // method never transitions state and requires an already-ACTIVE domain (findRequired below would
        // still succeed for a non-ACTIVE domain, but nothing routes to or dispatches against one -- see
        // KnowledgeDomainResolver/LibrarianDispatchService), so any domain reachable here already went
        // ACTIVE through provision() or the approval branch and already has both pages.
        String agentSlug = specialistAgentSlug(slug);
        Optional<Agent> existing = agentRepository.findByProjectIdAndSlug(projectId, agentSlug);
        if (existing.isPresent()) {
            refreshSpecialistPromptIfUnmodified(existing.get(), domain);
        } else {
            Agent agent = new Agent();
            agent.setProjectId(projectId);
            agent.setName("Knowledge Specialist — " + domain.getDisplayName());
            agent.setSlug(agentSlug);
            agent.setDescription("Files knowledge-inbox sources for the " + domain.getDisplayName() + " domain.");
            agent.setProvider(SPECIALIST_AGENT_PROVIDER);
            String systemPrompt = readSpecialistSystemPrompt(domain);
            agent.setSystemPrompt(systemPrompt);
            // Pinned to claude-code, same as the generalist librarian (see KnowledgeWorkflowProvisioner)
            // -- a specialist's filing task is the same Claude Code tool-calling loop, just domain-scoped.
            // seededPromptHash fingerprints the *rendered* prompt -- see refreshSpecialistPromptIfUnmodified.
            agent.setConfigJson(writeJson(Map.of(
                    "maxToolTurns", KnowledgeWorkflowProvisioner.LIBRARIAN_MAX_TOOL_TURNS,
                    "runtime", AgentRuntimeResolver.RUNTIME_CLAUDE_CODE,
                    KnowledgeWorkflowProvisioner.SEEDED_PROMPT_HASH_CONFIG_KEY, sha256Hex(systemPrompt))));
            agent.setToolIds(writeJson(KnowledgeWorkflowProvisioner.LIBRARIAN_TOOL_IDS));
            agent.setState("ACTIVE");
            agent.setAvatarEmoji(AgentAvatarDefaults.defaultEmoji(agentSlug));
            agent.setAvatarColor(AgentAvatarDefaults.defaultColor(agentSlug));
            agentRepository.save(agent);
            log.info("Created specialist agent '{}' for domain '{}' in project {}", agentSlug, slug, projectId);
        }
        return updateOwningAgent(projectId, slug, agentSlug);
    }

    /**
     * Refreshes an existing specialist's {@code systemPrompt} to the currently-rendered
     * {@code specialist-system-prompt.md}, but only while the stored prompt still matches the
     * {@value KnowledgeWorkflowProvisioner#SEEDED_PROMPT_HASH_CONFIG_KEY} this method (or
     * {@link #createSpecialist}) last stamped -- i.e. only while nobody has edited it. An operator edit
     * makes the prompt permanently theirs, same contract as the librarian's
     * {@code backfillSystemPromptIfUnmodified}.
     *
     * <p><b>Stamp-forward only, deliberately.</b> Unlike the librarian, a specialist cannot fall back to a
     * set of historical shipped digests, because its stored prompt is the shared template with
     * {@code %DOMAIN_SLUG%}/{@code %DOMAIN_DISPLAY%} already substituted per domain -- one shipped
     * template hashes to a different value for every domain, so no fixed set could recognize it. A
     * specialist created before stamping existed therefore has no stamp and is left alone forever, which
     * is an accepted gap rather than an oversight: specialists are user-initiated and few, their prompt is
     * editable under Automation -&gt; Agents, and re-running this admin-triggered endpoint is the documented
     * way to get the current template. Building an archived-rendered-template mechanism to cover them
     * would cost far more than the gap is worth.
     *
     * <p>Runs on every {@link #createSpecialist} call, which is safe because that endpoint is already
     * idempotent by design (it re-assigns rather than duplicating).
     */
    private void refreshSpecialistPromptIfUnmodified(Agent agent, KnowledgeDomain domain) {
        String storedPrompt = agent.getSystemPrompt();
        if (storedPrompt == null) {
            return;
        }
        String currentPrompt = readSpecialistSystemPrompt(domain);
        String currentHash = sha256Hex(currentPrompt);
        Map<String, Object> config = readConfig(agent);
        Object stamp = config.get(KnowledgeWorkflowProvisioner.SEEDED_PROMPT_HASH_CONFIG_KEY);
        if (currentPrompt.equals(storedPrompt)) {
            if (!currentHash.equals(stamp)) {
                agent.setConfigJson(writeJson(withPromptHash(config, currentHash)));
                agentRepository.save(agent);
            }
            return;
        }
        if (!sha256Hex(storedPrompt).equals(stamp)) {
            log.debug("Leaving operator-edited system prompt alone for specialist '{}' in project {}",
                    agent.getSlug(), agent.getProjectId());
            return;
        }
        agent.setSystemPrompt(currentPrompt);
        agent.setConfigJson(writeJson(withPromptHash(config, currentHash)));
        agentRepository.save(agent);
        log.info("Refreshed system prompt for specialist '{}' in project {} (stored prompt matched the "
                + "hash this service last seeded)", agent.getSlug(), agent.getProjectId());
    }

    private Map<String, Object> withPromptHash(Map<String, Object> config, String hash) {
        Map<String, Object> updated = new LinkedHashMap<>(config);
        updated.put(KnowledgeWorkflowProvisioner.SEEDED_PROMPT_HASH_CONFIG_KEY, hash);
        return updated;
    }

    private Map<String, Object> readConfig(Agent agent) {
        try {
            return objectMapper.readValue(agent.getConfigJson(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** Fingerprints a rendered specialist prompt -- see {@link #refreshSpecialistPromptIfUnmodified}.
     *  Same three-line digest as {@code KnowledgeWorkflowProvisioner}'s private helper; neither is
     *  reachable from the other's package and extracting a shared utility for two callers is
     *  disproportionate. */
    private static String sha256Hex(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String specialistAgentSlug(String slug) {
        return "knowledge-" + slug;
    }

    private String readSpecialistSystemPrompt(KnowledgeDomain domain) {
        return KnowledgeDomainTemplates.render(readResource(SPECIALIST_SYSTEM_PROMPT_RESOURCE), domain);
    }

    private void seedSkeletonSchemaPageIfAbsent(String projectId, KnowledgeDomain domain) {
        if (pageRepository.findByProjectIdAndPath(projectId, domain.getSchemaPagePath()).isPresent()) {
            return;
        }
        String content = KnowledgeDomainTemplates.render(readResource(SKELETON_RESOURCE), domain);
        pageService.batchWrite(projectId, List.of(new PageWrite(domain.getSchemaPagePath(), content, null, false)),
                List.of(), SYSTEM_ACTOR);
        log.info("Seeded skeleton {} for approved domain '{}' in project {}", domain.getSchemaPagePath(),
                domain.getSlug(), projectId);
    }

    /** Curation counterpart to {@link #seedSkeletonSchemaPageIfAbsent}, fired from the same
     *  SUGGESTED/DISMISSED -&gt; ACTIVE approval branch in {@link #update} -- an approved gap-report
     *  domain gets both a place to file pages and a policy for whether to file them at all. Seed-if-
     *  absent, same as the schema counterpart: this is a human-owned page, never overwritten. */
    private void seedSkeletonCurationPageIfAbsent(String projectId, KnowledgeDomain domain) {
        String path = KnowledgeCurationPaths.forDomain(domain);
        if (pageRepository.findByProjectIdAndPath(projectId, path).isPresent()) {
            return;
        }
        String content = KnowledgeDomainTemplates.render(readResource(CURATION_SKELETON_RESOURCE), domain);
        pageService.batchWrite(projectId, List.of(new PageWrite(path, content, null, false)),
                List.of(), SYSTEM_ACTOR);
        log.info("Seeded skeleton {} for approved domain '{}' in project {}", path, domain.getSlug(), projectId);
    }

    private void validateSlug(String slug) {
        if (slug == null || !SLUG_PATTERN.matcher(slug).matches()) {
            throw new BusinessException(
                    "Invalid domain slug '" + slug + "' -- must match ^[a-z0-9][a-z0-9-]*$ (it becomes a wiki path segment)");
        }
        if (slug.length() > SLUG_MAX_LENGTH) {
            throw new BusinessException("Domain slug exceeds " + SLUG_MAX_LENGTH + " characters");
        }
    }

    private void validateDisplayName(String displayName, boolean required) {
        if (displayName == null || displayName.isBlank()) {
            if (required) {
                throw new BusinessException("displayName is required");
            }
            return;
        }
        validateDisplayNameLength(displayName);
    }

    private void validateDisplayNameLength(String displayName) {
        if (displayName != null && displayName.length() > DISPLAY_NAME_MAX_LENGTH) {
            throw new BusinessException("displayName exceeds " + DISPLAY_NAME_MAX_LENGTH + " characters");
        }
    }

    /** A {@code [null]} or blank-element pattern would NPE {@code KnowledgeDomainResolver}'s glob match
     *  on every pattern-routed submission once this domain is ACTIVE -- reject it at the source instead. */
    private void validateSourceTypePatterns(List<String> patterns) {
        if (patterns == null) {
            return;
        }
        for (String pattern : patterns) {
            if (pattern == null || pattern.isBlank()) {
                throw new BusinessException("sourceTypePatterns must not contain null or blank entries");
            }
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

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize specialist agent config", e);
        }
    }

    private KnowledgeDomain findRequired(String projectId, String slug) {
        return domainRepository.findByProjectIdAndSlug(projectId, slug)
                .orElseThrow(() -> new EntityNotFoundException("Knowledge domain not found: " + slug));
    }
}
