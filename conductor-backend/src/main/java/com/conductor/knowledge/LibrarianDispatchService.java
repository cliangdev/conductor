package com.conductor.knowledge;

import com.conductor.agent.AgentRepository;
import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fires a {@code knowledge-librarian} run for a batch of claimed {@link KnowledgeSource} ids in one
 * domain lane, and records the resulting run id back onto them ({@code processing_run_id}) so
 * {@link KnowledgeIngestScheduler}'s per-lane busy check and stale-processing sweep can find it.
 *
 * <p>Agent resolution: {@code domain}'s {@link KnowledgeDomain#getOwningAgentSlug()} if the domain has
 * one assigned AND that agent still exists, otherwise falls back to the generalist
 * {@value KnowledgeWorkflowProvisioner#LIBRARIAN_AGENT_SLUG} -- so a deleted specialist agent never
 * strands its lane, it just demotes to the generalist on the next dispatch. The resolved slug and the
 * domain (empty string for the null/generalist lane -- workflow-YAML event payloads don't carry true
 * null) are always in the fired run's payload, readable in {@code knowledge-librarian.yaml}'s task as
 * {@code ${{ event.agentSlug }}} / {@code ${{ event.domain }}}.
 *
 * <p>Transaction discipline mirrors {@code ActionInvocationService}/{@code WorkflowExecutionEngine}:
 * {@link #dispatch} itself is deliberately NOT wrapped in one long transaction. {@link
 * WorkflowTriggerService#fireTrigger} opens its own (it inserts the {@link WorkflowRun} row and
 * enqueues jobs), and recording the run id back onto the sources is a separate short
 * {@code REQUIRES_NEW} write -- so a scheduler tick never holds a single transaction open across a
 * workflow-run creation plus a bulk update.
 */
@Service
public class LibrarianDispatchService {

    private static final Logger log = LoggerFactory.getLogger(LibrarianDispatchService.class);

    private final WorkflowDefinitionRepository workflowRepository;
    private final WorkflowTriggerService workflowTriggerService;
    private final KnowledgeSourceRepository sourceRepository;
    private final AgentRepository agentRepository;
    private final KnowledgeDomainRepository domainRepository;
    private final KnowledgeWorkflowProvisioner provisioner;
    private final ObjectMapper objectMapper;

    /** Self-reference so {@code @Transactional(REQUIRES_NEW)} helpers run through the Spring proxy --
     *  see {@code KnowledgeIngestionService#self}. */
    @Autowired
    @Lazy
    LibrarianDispatchService self;

    public LibrarianDispatchService(WorkflowDefinitionRepository workflowRepository,
                                    WorkflowTriggerService workflowTriggerService,
                                    KnowledgeSourceRepository sourceRepository,
                                    AgentRepository agentRepository,
                                    KnowledgeDomainRepository domainRepository,
                                    KnowledgeWorkflowProvisioner provisioner,
                                    ObjectMapper objectMapper) {
        this.workflowRepository = workflowRepository;
        this.workflowTriggerService = workflowTriggerService;
        this.sourceRepository = sourceRepository;
        this.agentRepository = agentRepository;
        this.domainRepository = domainRepository;
        this.provisioner = provisioner;
        this.objectMapper = objectMapper;
    }

    /**
     * Fires the project's {@code knowledge-librarian} workflow with {@code sourceIds} (comma-joined),
     * {@code projectId}, the resolved {@code agentSlug}, and {@code domain} as top-level event-payload
     * fields -- readable in the step prompt as {@code ${{ event.sourceIds }}} etc. (see docs/workflows.md's
     * interpolation table: {@code event.FIELD} resolves any trigger's stored payload, not just
     * webhook). Deliberately NOT passed as {@code workflow_dispatch} {@code inputs} -- those are for a
     * human-authored dispatch form; this is a programmatic fan-out of an arbitrary-length id batch, and
     * a flat top-level field avoids the publish-time "undeclared input" lint entirely.
     *
     * <p>If seeding turns out to be incomplete -- the {@code knowledge-librarian} workflow or its Agent
     * row is missing (e.g. a race between the enable-settings transaction and this scheduler tick, an
     * operator deleted the librarian Agent), or the stored workflow YAML has drifted from the current
     * classpath resource (e.g. this project was enabled before {@code agent: ${{ event.agentSlug }}}
     * shipped) -- this self-heals by calling {@link KnowledgeWorkflowProvisioner#provision} (which
     * refreshes drifted system-workflow YAML in place) and re-looking-up the workflow, rather than
     * dispatching into a stale or missing target. If provisioning itself throws, or the workflow is
     * still missing afterward, the batch falls back to being released back to PENDING so the next tick
     * retries it once provisioning has landed.
     */
    public void dispatch(String projectId, String domain, List<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return;
        }
        Optional<WorkflowDefinition> workflow =
                workflowRepository.findByProjectIdAndName(projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME);
        boolean agentMissing = !agentRepository.existsByProjectIdAndSlug(projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG);
        boolean yamlStale = provisioner.isLibrarianWorkflowStale(projectId);
        if (workflow.isEmpty() || agentMissing || yamlStale) {
            log.info("Knowledge-librarian seeding incomplete for project {} (workflow missing={}, agent missing={}, "
                    + "yaml stale={}) -- self-healing before dispatch", projectId, workflow.isEmpty(), agentMissing, yamlStale);
            try {
                provisioner.provision(projectId);
                workflow = workflowRepository.findByProjectIdAndName(projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME);
            } catch (Exception e) {
                log.warn("Failed to self-heal knowledge-librarian provisioning for project {} -- releasing {} "
                        + "claimed source(s) back to PENDING", projectId, sourceIds.size(), e);
                self.releaseBatchInNewTx(sourceIds);
                return;
            }
        }
        if (workflow.isEmpty()) {
            log.warn("No {} workflow provisioned for project {} even after self-heal -- releasing {} claimed "
                    + "source(s) back to PENDING",
                    KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME, projectId, sourceIds.size());
            self.releaseBatchInNewTx(sourceIds);
            return;
        }

        String agentSlug = resolveAgentSlug(projectId, domain);
        String payloadJson = buildPayload(projectId, domain, agentSlug, sourceIds);
        WorkflowRun run = workflowTriggerService.fireTrigger(workflow.get().getId(), "workflow_dispatch", payloadJson);
        self.recordRunIdInNewTx(sourceIds, run.getId());
        log.info("Dispatched knowledge-librarian run {} for project {} domain={} agent={} ({} source(s))",
                run.getId(), projectId, domain, agentSlug, sourceIds.size());
    }

    /** The domain's assigned specialist if it has one AND that agent still exists, else the generalist
     *  librarian -- a deleted specialist demotes its lane back to the generalist rather than stranding it. */
    private String resolveAgentSlug(String projectId, String domain) {
        if (domain == null) {
            return KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG;
        }
        Optional<KnowledgeDomain> domainRow = domainRepository.findByProjectIdAndSlug(projectId, domain);
        String owningAgentSlug = domainRow.map(KnowledgeDomain::getOwningAgentSlug).orElse(null);
        if (owningAgentSlug == null) {
            return KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG;
        }
        if (agentRepository.existsByProjectIdAndSlug(projectId, owningAgentSlug)) {
            return owningAgentSlug;
        }
        log.info("Domain '{}' owning agent '{}' no longer exists for project {} -- falling back to generalist librarian",
                domain, owningAgentSlug, projectId);
        return KnowledgeWorkflowProvisioner.LIBRARIAN_AGENT_SLUG;
    }

    private String buildPayload(String projectId, String domain, String agentSlug, List<String> sourceIds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "workflow_dispatch");
        payload.put("projectId", projectId);
        payload.put("sourceIds", String.join(",", sourceIds));
        payload.put("agentSlug", agentSlug);
        payload.put("domain", domain != null ? domain : "");
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            // A "{}" fallback would dispatch a librarian run with no sourceIds and strand the batch
            // in PROCESSING until the sweep; failing loudly lets the scheduler's per-project
            // try/catch log it and the sweep retry with the payload intact.
            throw new IllegalStateException("Failed to serialize knowledge-librarian dispatch payload", e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRunIdInNewTx(List<String> sourceIds, String runId) {
        List<KnowledgeSource> sources = sourceRepository.findAllById(sourceIds);
        for (KnowledgeSource source : sources) {
            source.setProcessingRunId(runId);
        }
        sourceRepository.saveAll(sources);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseBatchInNewTx(List<String> sourceIds) {
        List<KnowledgeSource> sources = sourceRepository.findAllById(sourceIds);
        for (KnowledgeSource source : sources) {
            source.setStatus(KnowledgeSourceStatus.PENDING);
        }
        sourceRepository.saveAll(sources);
    }
}
