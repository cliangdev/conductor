package com.conductor.knowledge.domain;

import com.conductor.agent.Agent;
import com.conductor.agent.AgentAvatarDefaults;
import com.conductor.agent.AgentRepository;
import com.conductor.exception.BusinessException;
import com.conductor.knowledge.Actor;
import com.conductor.knowledge.KnowledgeWorkflowProvisioner;
import com.conductor.knowledge.page.KnowledgePageRepository;
import com.conductor.knowledge.page.KnowledgePageService;
import com.conductor.knowledge.page.PageWrite;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    private static final String SKELETON_RESOURCE = "/knowledge/domains/_suggested-skeleton.md";
    private static final String SPECIALIST_SYSTEM_PROMPT_RESOURCE = "/knowledge/specialist-system-prompt.md";
    private static final String SPECIALIST_AGENT_PROVIDER = "claude";
    private static final Actor SYSTEM_ACTOR = new Actor("system", "knowledge-domain-service", null);

    private final KnowledgeDomainRepository domainRepository;
    private final AgentRepository agentRepository;
    private final KnowledgePageRepository pageRepository;
    private final KnowledgePageService pageService;
    private final ObjectMapper objectMapper;

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
     * {@code state} (e.g. approving a {@code SUGGESTED} domain), pass the target state -- a
     * {@code SUGGESTED -> ACTIVE} transition also seeds a generic skeleton {@code <slug>/_schema.md}
     * page if one isn't already there (an admin approving a gap report shouldn't be left with a domain
     * that has nowhere for the librarian to file anything). Owning-agent assignment is a separate
     * operation -- see {@link #updateOwningAgent} -- since a null {@code owningAgentSlug} there means
     * "clear it", which would be ambiguous alongside this method's "null means unchanged" convention for
     * every other field.
     */
    @Transactional
    public KnowledgeDomain update(String projectId, String slug, String displayName, String description,
            List<String> sourceTypePatterns, KnowledgeDomainState state) {
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
        if (previousState == KnowledgeDomainState.SUGGESTED && saved.getState() == KnowledgeDomainState.ACTIVE) {
            seedSkeletonSchemaPageIfAbsent(projectId, saved);
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
     * Claim-or-return gap report: the first caller for a {@code (projectId, slug)} pair inserts a
     * {@code SUGGESTED} row; any later caller (regardless of the row's current state -- {@code ACTIVE},
     * still {@code SUGGESTED}, or {@code DISMISSED}) gets the existing row back with {@code created =
     * false} instead of erroring or resetting it. A {@code DISMISSED} return is the signal to the caller
     * (an agent calling {@code suggest_knowledge_domain}) that this was already declined and shouldn't
     * be re-suggested.
     */
    @Transactional
    public SuggestResult suggest(String projectId, String slug, String displayName, String description,
            String reason, List<String> sourceTypePatterns, String suggestedBy) {
        if (slug == null || !SLUG_PATTERN.matcher(slug).matches()) {
            throw new BusinessException(
                    "Invalid domain slug '" + slug + "' -- must match ^[a-z0-9][a-z0-9-]*$ (it becomes a wiki path segment)");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new BusinessException("displayName is required");
        }
        Optional<KnowledgeDomain> existing = domainRepository.findByProjectIdAndSlug(projectId, slug);
        if (existing.isPresent()) {
            return new SuggestResult(existing.get(), false);
        }

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
        try {
            return new SuggestResult(domainRepository.save(domain), true);
        } catch (DataIntegrityViolationException e) {
            // Lost a race on the (project_id, slug) unique constraint -- the winning row exists.
            return domainRepository.findByProjectIdAndSlug(projectId, slug)
                    .map(d -> new SuggestResult(d, false))
                    .orElseThrow(() -> e);
        }
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
        String agentSlug = specialistAgentSlug(slug);
        if (!agentRepository.existsByProjectIdAndSlug(projectId, agentSlug)) {
            Agent agent = new Agent();
            agent.setProjectId(projectId);
            agent.setName("Knowledge Specialist — " + domain.getDisplayName());
            agent.setSlug(agentSlug);
            agent.setDescription("Files knowledge-inbox sources for the " + domain.getDisplayName() + " domain.");
            agent.setProvider(SPECIALIST_AGENT_PROVIDER);
            agent.setSystemPrompt(readSpecialistSystemPrompt(domain));
            // No "runtime" key -- resolved at execution time, same as the generalist librarian.
            agent.setConfigJson(writeJson(Map.of("maxToolTurns", 40)));
            agent.setToolIds(writeJson(KnowledgeWorkflowProvisioner.LIBRARIAN_TOOL_IDS));
            agent.setState("ACTIVE");
            agent.setAvatarEmoji(AgentAvatarDefaults.defaultEmoji(agentSlug));
            agent.setAvatarColor(AgentAvatarDefaults.defaultColor(agentSlug));
            agentRepository.save(agent);
            log.info("Created specialist agent '{}' for domain '{}' in project {}", agentSlug, slug, projectId);
        }
        return updateOwningAgent(projectId, slug, agentSlug);
    }

    private String specialistAgentSlug(String slug) {
        return "knowledge-" + slug;
    }

    private String readSpecialistSystemPrompt(KnowledgeDomain domain) {
        return readResource(SPECIALIST_SYSTEM_PROMPT_RESOURCE)
                .replace("%DOMAIN_SLUG%", domain.getSlug())
                .replace("%DOMAIN_DISPLAY%", domain.getDisplayName());
    }

    private void seedSkeletonSchemaPageIfAbsent(String projectId, KnowledgeDomain domain) {
        if (pageRepository.findByProjectIdAndPath(projectId, domain.getSchemaPagePath()).isPresent()) {
            return;
        }
        String content = readResource(SKELETON_RESOURCE)
                .replace("%DOMAIN_SLUG%", domain.getSlug())
                .replace("%DOMAIN_DISPLAY%", domain.getDisplayName());
        pageService.batchWrite(projectId, List.of(new PageWrite(domain.getSchemaPagePath(), content, null, false)),
                List.of(), SYSTEM_ACTOR);
        log.info("Seeded skeleton {} for approved domain '{}' in project {}", domain.getSchemaPagePath(),
                domain.getSlug(), projectId);
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
