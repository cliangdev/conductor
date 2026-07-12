package com.conductor.knowledge;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
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
 * Fires a {@code knowledge-librarian} run for a batch of claimed {@link KnowledgeSource} ids, and
 * records the resulting run id back onto them ({@code processing_run_id}) so
 * {@link KnowledgeIngestScheduler}'s active-run guard and stale-processing sweep can find it.
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
    private final ObjectMapper objectMapper;

    /** Self-reference so {@code @Transactional(REQUIRES_NEW)} helpers run through the Spring proxy --
     *  see {@code KnowledgeIngestionService#self}. */
    @Autowired
    @Lazy
    LibrarianDispatchService self;

    public LibrarianDispatchService(WorkflowDefinitionRepository workflowRepository,
                                    WorkflowTriggerService workflowTriggerService,
                                    KnowledgeSourceRepository sourceRepository,
                                    ObjectMapper objectMapper) {
        this.workflowRepository = workflowRepository;
        this.workflowTriggerService = workflowTriggerService;
        this.sourceRepository = sourceRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Fires the project's {@code knowledge-librarian} workflow with {@code sourceIds} (comma-joined)
     * and {@code projectId} as top-level event-payload fields -- readable in the step prompt as
     * {@code ${{ event.sourceIds }}} / {@code ${{ event.projectId }}} (see docs/workflows.md's
     * interpolation table: {@code event.FIELD} resolves any trigger's stored payload, not just
     * webhook). Deliberately NOT passed as {@code workflow_dispatch} {@code inputs} -- those are for a
     * human-authored dispatch form; this is a programmatic fan-out of an arbitrary-length id batch, and
     * a flat top-level field avoids the publish-time "undeclared input" lint entirely.
     *
     * <p>If the workflow isn't provisioned yet (e.g. a race between the enable-settings transaction and
     * this scheduler tick), the batch is released back to PENDING so the next tick retries it once
     * provisioning has landed, rather than leaving it stuck PROCESSING with no run to track.
     */
    public void dispatch(String projectId, List<String> sourceIds) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return;
        }
        Optional<WorkflowDefinition> workflow =
                workflowRepository.findByProjectIdAndName(projectId, KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME);
        if (workflow.isEmpty()) {
            log.warn("No {} workflow provisioned for project {} yet -- releasing {} claimed source(s) back to PENDING",
                    KnowledgeWorkflowProvisioner.LIBRARIAN_WORKFLOW_NAME, projectId, sourceIds.size());
            self.releaseBatchInNewTx(sourceIds);
            return;
        }

        String payloadJson = buildPayload(projectId, sourceIds);
        WorkflowRun run = workflowTriggerService.fireTrigger(workflow.get().getId(), "workflow_dispatch", payloadJson);
        self.recordRunIdInNewTx(sourceIds, run.getId());
        log.info("Dispatched knowledge-librarian run {} for project {} ({} source(s))",
                run.getId(), projectId, sourceIds.size());
    }

    private String buildPayload(String projectId, List<String> sourceIds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "workflow_dispatch");
        payload.put("projectId", projectId);
        payload.put("sourceIds", String.join(",", sourceIds));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
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
