package com.conductor.knowledge.domain;

import com.conductor.agent.AgentRepository;
import com.conductor.exception.BusinessException;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * CRUD + owning-agent assignment for the Knowledge Center's domain registry (see
 * {@code docs/knowledge.md}). Routing (which domain a submitted source lands in) and dispatch (which
 * agent runs against it) are separate concerns owned by Phase 2's {@code KnowledgeDomainResolver} /
 * {@code LibrarianDispatchService} -- this service only manages the registry rows themselves.
 *
 * <p>Owning-agent validation goes through {@link AgentRepository} directly rather than
 * {@code AgentService} -- same bean-cycle precedent as {@code KnowledgeWorkflowProvisioner}
 * (AgentService -&gt; AgentToolRegistry -&gt; KnowledgeToolProvider -&gt; ... -&gt; the knowledge package).
 */
@Service
public class KnowledgeDomainService {

    private final KnowledgeDomainRepository domainRepository;
    private final AgentRepository agentRepository;

    public KnowledgeDomainService(KnowledgeDomainRepository domainRepository, AgentRepository agentRepository) {
        this.domainRepository = domainRepository;
        this.agentRepository = agentRepository;
    }

    /** All domains for a project, slug-ordered (stable, deterministic display order). */
    @Transactional(readOnly = true)
    public List<KnowledgeDomain> list(String projectId) {
        return domainRepository.findByProjectIdOrderBySlugAsc(projectId);
    }

    /**
     * Updates the editable metadata fields; a null argument leaves that field unchanged. To transition
     * {@code state} (e.g. approving a {@code SUGGESTED} domain), pass the target state. Owning-agent
     * assignment is a separate operation -- see {@link #updateOwningAgent} -- since a null
     * {@code owningAgentSlug} there means "clear it", which would be ambiguous alongside this method's
     * "null means unchanged" convention for every other field.
     */
    @Transactional
    public KnowledgeDomain update(String projectId, String slug, String displayName, String description,
            List<String> sourceTypePatterns, KnowledgeDomainState state) {
        KnowledgeDomain domain = findRequired(projectId, slug);
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
        return domainRepository.save(domain);
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

    private KnowledgeDomain findRequired(String projectId, String slug) {
        return domainRepository.findByProjectIdAndSlug(projectId, slug)
                .orElseThrow(() -> new EntityNotFoundException("Knowledge domain not found: " + slug));
    }
}
