package com.conductor.knowledge;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.service.ProjectSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Drives the knowledge ingestion inbox to completion: claims due PENDING sources into batches, per
 * domain lane, and dispatches a {@code knowledge-librarian} run for each ({@link LibrarianDispatchService}),
 * and sweeps PROCESSING sources whose run stalled or failed back to PENDING (with backoff) or DEAD.
 *
 * <p>Concurrency unit is {@code (project, domain lane)}, not the whole project: a lane (a
 * {@code KnowledgeDomain} slug, or {@code null} for the generalist lane) is busy iff it currently has
 * any PROCESSING source, and a busy lane is skipped this tick without affecting any other lane -- so
 * e.g. an in-flight engineering-domain run never blocks a product-domain batch from dispatching in the
 * same tick. The one project-wide block is {@code knowledge-bootstrap}: it writes broadly across the
 * wiki in one large by-hand-triggered run, so no lane dispatches while it's active. (This is a looser
 * concurrency model than the original single global "any active knowledge run blocks everything" --
 * lanes now self-serialize via their own PROCESSING rows instead.)
 *
 * <p>Shape mirrors {@code WebhookRetryScheduler}/{@code ActionInvocationService}'s background sweep:
 * no method-level {@code @Transactional} wraps the whole tick (per-project/per-source failures are
 * isolated with their own try/catch, and DB writes that must be atomic are short
 * {@code REQUIRES_NEW} helpers) -- the librarian dispatch call creates {@link WorkflowRun} rows via
 * {@link LibrarianDispatchService}, which must never happen inside a long-held transaction.
 */
@Component
public class KnowledgeIngestScheduler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestScheduler.class);

    /** Package-private (not final, not static) so tests can shrink the batch without waiting for 10 rows. */
    int batchSize = 10;
    /** Total attempts before a stuck PROCESSING source is dead-lettered. */
    static final int MAX_ATTEMPTS = 5;
    private static final Set<WorkflowRunStatus> ACTIVE_RUN_STATUSES =
            Set.of(WorkflowRunStatus.PENDING, WorkflowRunStatus.PENDING_LOCAL_PICKUP, WorkflowRunStatus.RUNNING);
    private static final Set<WorkflowRunStatus> TERMINAL_FAILED_STATUSES =
            Set.of(WorkflowRunStatus.FAILED, WorkflowRunStatus.CANCELLED, WorkflowRunStatus.LOCAL_PICKUP_TIMEOUT);

    /** Package-private (not final) so tests can shrink the stale-processing window instead of waiting
     *  out the real 30-minute production duration -- same pattern as {@code GcpStorageService.retryDelays}. */
    long staleProcessingMinutes = 30;

    private final KnowledgeSourceRepository sourceRepository;
    private final ProjectSettingsService projectSettingsService;
    private final WorkflowDefinitionRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final LibrarianDispatchService dispatchService;

    /** Self-reference so {@code @Transactional(REQUIRES_NEW)} helpers run through the Spring proxy. */
    @Autowired
    @Lazy
    KnowledgeIngestScheduler self;

    public KnowledgeIngestScheduler(KnowledgeSourceRepository sourceRepository,
                                    ProjectSettingsService projectSettingsService,
                                    WorkflowDefinitionRepository workflowRepository,
                                    WorkflowRunRepository workflowRunRepository,
                                    LibrarianDispatchService dispatchService) {
        this.sourceRepository = sourceRepository;
        this.projectSettingsService = projectSettingsService;
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.dispatchService = dispatchService;
    }

    @Scheduled(fixedDelay = 30_000)
    public void poll() {
        dispatchDueProjects();
        sweepStaleProcessing();
    }

    // ---- dispatch: PENDING -> PROCESSING + librarian run ----

    private void dispatchDueProjects() {
        OffsetDateTime now = OffsetDateTime.now();
        for (String projectId : sourceRepository.findProjectIdsWithDuePending(now)) {
            try {
                dispatchProject(projectId, now);
            } catch (Exception e) {
                log.error("Knowledge ingest dispatch failed for project {}: {}", projectId, e.getMessage(), e);
            }
        }
    }

    private void dispatchProject(String projectId, OffsetDateTime now) {
        if (!projectSettingsService.isKnowledgeEnabled(projectId)) {
            return;
        }
        if (hasActiveBootstrapRun(projectId)) {
            return;
        }
        for (String domain : sourceRepository.findLanesWithDuePending(projectId, now)) {
            if (sourceRepository.existsProcessingInLane(projectId, domain)) {
                continue; // this lane is busy; other lanes are unaffected
            }
            List<String> claimedIds = self.claimBatchInNewTx(projectId, domain, now);
            if (claimedIds.isEmpty()) {
                continue;
            }
            dispatchService.dispatch(projectId, domain, claimedIds);
        }
    }

    /** True if {@code knowledge-bootstrap} already has a non-terminal run for this project -- the one
     *  project-wide dispatch block (see class javadoc); the librarian workflow no longer blocks, since
     *  lanes now self-serialize via their own PROCESSING rows. */
    private boolean hasActiveBootstrapRun(String projectId) {
        Optional<WorkflowDefinition> workflow = workflowRepository.findByProjectIdAndName(
                projectId, KnowledgeWorkflowProvisioner.BOOTSTRAP_WORKFLOW_NAME);
        if (workflow.isEmpty()) {
            return false;
        }
        return !workflowRunRepository.findByWorkflowIdAndStatusIn(workflow.get().getId(), ACTIVE_RUN_STATUSES).isEmpty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<String> claimBatchInNewTx(String projectId, String domain, OffsetDateTime now) {
        List<KnowledgeSource> due = sourceRepository.findDuePendingForProjectAndDomain(projectId, domain, now, batchSize);
        if (due.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>(due.size());
        for (KnowledgeSource source : due) {
            source.setStatus(KnowledgeSourceStatus.PROCESSING);
            ids.add(source.getId());
        }
        sourceRepository.saveAll(due);
        return ids;
    }

    // ---- sweep: resurrect or dead-letter stuck PROCESSING sources ----

    private void sweepStaleProcessing() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(staleProcessingMinutes);
        for (KnowledgeSource source : sourceRepository.findByStatus(KnowledgeSourceStatus.PROCESSING)) {
            try {
                if (isStale(source, cutoff)) {
                    self.resurrectOrDeadInNewTx(source.getId());
                }
            } catch (Exception e) {
                log.error("Knowledge ingest sweep failed for source {}: {}", source.getId(), e.getMessage(), e);
            }
        }
    }

    /** Stale if there's no run to track it, the run ended in a failed/cancelled/timeout state, or it's
     *  simply been running longer than the stale window (a safety net for a run that silently wedged). */
    private boolean isStale(KnowledgeSource source, OffsetDateTime cutoff) {
        String runId = source.getProcessingRunId();
        if (runId == null) {
            return true;
        }
        WorkflowRun run = workflowRunRepository.findById(runId).orElse(null);
        if (run == null) {
            return true;
        }
        return TERMINAL_FAILED_STATUSES.contains(run.getStatus()) || run.getStartedAt().isBefore(cutoff);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void resurrectOrDeadInNewTx(String sourceId) {
        sourceRepository.findById(sourceId).ifPresent(source -> {
            if (source.getStatus() != KnowledgeSourceStatus.PROCESSING) {
                return; // already moved on (raced with a librarian write in the meantime)
            }
            int attempts = source.getAttempts() + 1;
            source.setAttempts(attempts);
            if (attempts >= MAX_ATTEMPTS) {
                source.setStatus(KnowledgeSourceStatus.DEAD);
                source.setErrorMessage("Exceeded " + MAX_ATTEMPTS + " processing attempts (last run: "
                        + source.getProcessingRunId() + ")");
            } else {
                source.setStatus(KnowledgeSourceStatus.PENDING);
                long backoffSeconds = 60L * (1L << attempts);
                source.setNextAttemptAt(OffsetDateTime.now().plusSeconds(backoffSeconds));
            }
            sourceRepository.save(source);
        });
    }
}
