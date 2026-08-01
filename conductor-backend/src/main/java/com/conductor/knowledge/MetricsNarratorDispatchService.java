package com.conductor.knowledge;

import com.conductor.agent.AgentRepository;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.integration.ingest.ConnectorFeedDigest;
import com.conductor.integration.ingest.ConnectorFeedDigestRepository;
import com.conductor.integration.ingest.DigestStatus;
import com.conductor.knowledge.domain.KnowledgeDomain;
import com.conductor.knowledge.domain.KnowledgeDomainRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.workflow.WorkflowTriggerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Fires a {@code metrics-narrator} run for one {@link ConnectorFeedDigest} and records the resulting
 * run id back onto it ({@code narratingRunId}) so {@code ConnectorFeedScheduler}'s sweep can find it.
 * A near-clone of {@link LibrarianDispatchService} -- same self-heal-then-dispatch shape, same
 * transaction discipline -- with two structural differences: dispatch is one digest at a time (never
 * batched, since each digest is its own distinct metric period and there is no benefit to bundling
 * unrelated narrations into one agent run), and agent resolution reads the domain hint out of the
 * digest's own {@code changeReport} (stamped there by {@code DigestPayloadBuilder} as
 * {@code suggestedDomain}) rather than a caller-supplied domain parameter.
 *
 * <p>Transaction discipline mirrors {@link LibrarianDispatchService}: {@link #dispatch} itself is
 * deliberately NOT wrapped in one long transaction. {@link WorkflowTriggerService#fireTrigger} opens
 * its own, and recording the run id (or releasing the digest back to PENDING) is a separate short
 * {@code REQUIRES_NEW} write.
 */
@Service
public class MetricsNarratorDispatchService {

    private static final Logger log = LoggerFactory.getLogger(MetricsNarratorDispatchService.class);

    private final WorkflowDefinitionRepository workflowRepository;
    private final WorkflowTriggerService workflowTriggerService;
    private final ConnectorFeedDigestRepository digestRepository;
    private final AgentRepository agentRepository;
    private final KnowledgeDomainRepository domainRepository;
    private final KnowledgeWorkflowProvisioner provisioner;
    private final ObjectMapper objectMapper;

    /** Self-reference so {@code @Transactional(REQUIRES_NEW)} helpers run through the Spring proxy --
     *  see {@link LibrarianDispatchService#self}. */
    @Autowired
    @Lazy
    MetricsNarratorDispatchService self;

    public MetricsNarratorDispatchService(WorkflowDefinitionRepository workflowRepository,
                                          WorkflowTriggerService workflowTriggerService,
                                          ConnectorFeedDigestRepository digestRepository,
                                          AgentRepository agentRepository,
                                          KnowledgeDomainRepository domainRepository,
                                          KnowledgeWorkflowProvisioner provisioner,
                                          ObjectMapper objectMapper) {
        this.workflowRepository = workflowRepository;
        this.workflowTriggerService = workflowTriggerService;
        this.digestRepository = digestRepository;
        this.agentRepository = agentRepository;
        this.domainRepository = domainRepository;
        this.provisioner = provisioner;
        this.objectMapper = objectMapper;
    }

    /**
     * Fires the project's {@code metrics-narrator} workflow for one already-claimed (NARRATING)
     * digest. See {@link LibrarianDispatchService#dispatch} for the self-heal rationale -- identical
     * here, just against the narrator workflow/agent instead of the librarian's.
     */
    public void dispatch(ConnectorFeedDigest digest) {
        String projectId = digest.getProjectId();
        Optional<WorkflowDefinition> workflow =
                workflowRepository.findByProjectIdAndName(projectId, KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME);
        boolean agentMissing = !agentRepository.existsByProjectIdAndSlug(
                projectId, KnowledgeWorkflowProvisioner.METRICS_ANALYST_AGENT_SLUG);
        boolean yamlStale = provisioner.isSystemWorkflowStale(
                projectId, KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME, KnowledgeWorkflowProvisioner.NARRATOR_RESOURCE);
        if (workflow.isEmpty() || agentMissing || yamlStale) {
            log.info("Metrics-narrator seeding incomplete for project {} (workflow missing={}, agent missing={}, "
                    + "yaml stale={}) -- self-healing before dispatch", projectId, workflow.isEmpty(), agentMissing, yamlStale);
            try {
                provisioner.provision(projectId);
                workflow = workflowRepository.findByProjectIdAndName(projectId, KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME);
            } catch (Exception e) {
                log.warn("Failed to self-heal metrics-narrator provisioning for project {} -- releasing digest {} "
                        + "back to PENDING", projectId, digest.getId(), e);
                self.releaseInNewTx(digest.getId());
                return;
            }
        }
        if (workflow.isEmpty()) {
            log.warn("No {} workflow provisioned for project {} even after self-heal -- releasing digest {} "
                    + "back to PENDING", KnowledgeWorkflowProvisioner.NARRATOR_WORKFLOW_NAME, projectId, digest.getId());
            self.releaseInNewTx(digest.getId());
            return;
        }

        String agentSlug = resolveAgentSlug(projectId, suggestedDomain(digest));
        String payloadJson = buildPayload(digest, agentSlug);
        WorkflowRun run = workflowTriggerService.fireTrigger(workflow.get().getId(), "workflow_dispatch", payloadJson);
        self.recordRunIdInNewTx(digest.getId(), run.getId());
        log.info("Dispatched metrics-narrator run {} for project {} digest={} agent={}",
                run.getId(), projectId, digest.getId(), agentSlug);
    }

    /** {@code suggestedDomain} is stamped onto every digest's {@code changeReport} by
     *  {@code DigestPayloadBuilder} -- reading it back here avoids re-resolving the feed's
     *  {@code IngestSpec} just to learn what this class already has on hand. */
    private String suggestedDomain(ConnectorFeedDigest digest) {
        Object domain = digest.getChangeReport() != null ? digest.getChangeReport().get("suggestedDomain") : null;
        return domain instanceof String s ? s : null;
    }

    /** The domain's assigned specialist if it has one AND that agent still exists, else the generalist
     *  metrics-analyst -- same fallback ladder as {@link LibrarianDispatchService#resolveAgentSlug}, so
     *  a deleted specialist demotes a digest rather than stranding it. */
    private String resolveAgentSlug(String projectId, String domain) {
        if (domain == null) {
            return KnowledgeWorkflowProvisioner.METRICS_ANALYST_AGENT_SLUG;
        }
        Optional<KnowledgeDomain> domainRow = domainRepository.findByProjectIdAndSlug(projectId, domain);
        String owningAgentSlug = domainRow.map(KnowledgeDomain::getOwningAgentSlug).orElse(null);
        if (owningAgentSlug == null) {
            return KnowledgeWorkflowProvisioner.METRICS_ANALYST_AGENT_SLUG;
        }
        if (agentRepository.existsByProjectIdAndSlug(projectId, owningAgentSlug)) {
            return owningAgentSlug;
        }
        log.info("Domain '{}' owning agent '{}' no longer exists for project {} -- falling back to generalist "
                + "metrics-analyst", domain, owningAgentSlug, projectId);
        return KnowledgeWorkflowProvisioner.METRICS_ANALYST_AGENT_SLUG;
    }

    private String buildPayload(ConnectorFeedDigest digest, String agentSlug) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "workflow_dispatch");
        payload.put("projectId", digest.getProjectId());
        payload.put("digestId", digest.getId());
        payload.put("agentSlug", agentSlug);
        try {
            payload.put("digestJson", objectMapper.writeValueAsString(digest.getChangeReport()));
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            // A "{}" fallback would dispatch a narrator run with no digestJson and strand the digest in
            // NARRATING until the sweep; failing loudly lets the scheduler's per-digest try/catch log it
            // and the sweep retry with the payload intact.
            throw new IllegalStateException("Failed to serialize metrics-narrator dispatch payload", e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRunIdInNewTx(String digestId, String runId) {
        digestRepository.findById(digestId).ifPresent(digest -> {
            digest.setNarratingRunId(runId);
            digestRepository.save(digest);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseInNewTx(String digestId) {
        digestRepository.findById(digestId).ifPresent(digest -> {
            digest.setStatus(DigestStatus.PENDING);
            digestRepository.save(digest);
        });
    }
}
