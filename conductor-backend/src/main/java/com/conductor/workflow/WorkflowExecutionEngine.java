package com.conductor.workflow;

import com.conductor.entity.*;
import com.conductor.repository.*;
import com.conductor.workflow.model.JobSpec;
import com.conductor.workflow.model.WorkflowSpec;
import com.conductor.workflow.model.WorkflowYamlException;
import com.conductor.workflow.model.WorkflowYamlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
public class WorkflowExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionEngine.class);

    private final WorkflowJobQueueRepository queueRepository;
    private final WorkflowRunRepository runRepository;
    private final WorkflowJobRunRepository jobRunRepository;
    private final WorkflowStepRunRepository stepRunRepository;
    private final WorkflowDefinitionRepository workflowRepository;
    private final WorkflowJobOrchestrator orchestrator;
    private final WorkflowYamlParser yamlParser;
    private final WorkflowFailureCircuitBreaker circuitBreaker;
    private final WorkflowRunFailureNotifier runFailureNotifier;
    private final WorkflowJobDispatcher jobDispatcher;

    // Self-reference injected lazily so recoverStuckJobsOnStartup's calls to other @Transactional
    // methods on this class go through the Spring proxy (self-invocation workaround).
    @Lazy
    @Autowired
    private WorkflowExecutionEngine self;

    public WorkflowExecutionEngine(WorkflowJobQueueRepository queueRepository,
                                   WorkflowRunRepository runRepository,
                                   WorkflowJobRunRepository jobRunRepository,
                                   WorkflowStepRunRepository stepRunRepository,
                                   WorkflowDefinitionRepository workflowRepository,
                                   WorkflowJobOrchestrator orchestrator,
                                   WorkflowYamlParser yamlParser,
                                   WorkflowFailureCircuitBreaker circuitBreaker,
                                   WorkflowRunFailureNotifier runFailureNotifier,
                                   WorkflowJobDispatcher jobDispatcher) {
        this.queueRepository = queueRepository;
        this.runRepository = runRepository;
        this.jobRunRepository = jobRunRepository;
        this.stepRunRepository = stepRunRepository;
        this.workflowRepository = workflowRepository;
        this.orchestrator = orchestrator;
        this.yamlParser = yamlParser;
        this.circuitBreaker = circuitBreaker;
        this.runFailureNotifier = runFailureNotifier;
        this.jobDispatcher = jobDispatcher;
    }

    /**
     * On startup: re-enqueue any jobs stuck in RUNNING state. Deliberately queries RUNNING only —
     * AWAITING_PICKUP jobs are owned by the daemon, not this engine, and must not be re-enqueued here
     * (see {@link #cleanupStuckRuns} for their timeout path instead).
     */
    @Transactional
    public void recoverStuckJobs() {
        List<WorkflowJobRun> stuckJobs = jobRunRepository.findByStatus(WorkflowJobStatus.RUNNING);
        for (WorkflowJobRun jobRun : stuckJobs) {
            log.warn("Re-enqueuing stuck job {} for run {}", jobRun.getJobId(), jobRun.getRun().getId());
            jobRun.setStatus(WorkflowJobStatus.PENDING);
            jobRunRepository.save(jobRun);
            enqueueJob(jobRun.getRun().getId(), jobRun.getJobId());
        }
    }

    /**
     * Daily: mark runs stuck in RUNNING for >24h as FAILED, and fail any self-hosted job still
     * AWAITING_PICKUP after 24h — the daemon never claimed it (stopped, never upgraded, etc.).
     */
    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void cleanupStuckRuns() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(24);
        List<WorkflowRun> stuckRuns = runRepository.findByStatusIn(
                List.of(WorkflowRunStatus.RUNNING, WorkflowRunStatus.PENDING));
        for (WorkflowRun run : stuckRuns) {
            if (run.getStartedAt() != null && run.getStartedAt().isBefore(cutoff)) {
                run.setStatus(WorkflowRunStatus.FAILED);
                run.setCompletedAt(OffsetDateTime.now());
                runRepository.save(run);
                log.info("Marked stuck run {} as FAILED", run.getId());
                runFailureNotifier.notifyFailed(run);
            }
        }

        List<WorkflowJobRun> stuckAwaitingPickup =
                jobRunRepository.findByStatusAndStartedAtBefore(WorkflowJobStatus.AWAITING_PICKUP.name(), cutoff);
        for (WorkflowJobRun jobRun : stuckAwaitingPickup) {
            log.warn("Job {} for run {} timed out waiting for daemon pickup", jobRun.getJobId(), jobRun.getRun().getId());
            orchestrator.completeRemoteJob(jobRun.getRun().getId(), jobRun.getJobId(),
                    WorkflowJobStatus.FAILED, "DAEMON_PICKUP_TIMEOUT");
        }
    }

    @Transactional
    public void enqueueJob(String runId, String jobId) {
        // Best-effort de-dup: two upstream jobs completing near-simultaneously (the finalizeJob
        // path and the completeRemoteJob path, e.g. a diamond `needs`) can each try to enqueue the
        // same dependent. Not bulletproof without a DB unique partial index on (run_id, job_id)
        // WHERE claimed_at IS NULL — two concurrent callers can still both pass this check before
        // either inserts — but combined with the run-row lock in WorkflowJobOrchestrator's
        // planJobExecution/completeRemoteJob it closes the realistic window.
        if (!queueRepository.findByRunIdAndJobIdAndClaimedAtIsNull(runId, jobId).isEmpty()) {
            log.info("enqueueJob: unclaimed queue row already exists for run {} job {}, skipping duplicate enqueue",
                    runId, jobId);
            return;
        }

        WorkflowRun run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("Run not found: " + runId));
        WorkflowJobQueue entry = new WorkflowJobQueue();
        entry.setRun(run);
        entry.setJobId(jobId);
        queueRepository.save(entry);
        jobDispatcher.dispatchAfterCommit(runId, jobId);
    }

    /**
     * Entry point for the Cloud-Tasks-triggered dispatch path (the {@code POST
     * /internal/v1/workflow-runs/{runId}/jobs/{jobId}/dispatch} endpoint). Atomically claims the
     * matching unclaimed {@code workflow_job_queue} row, so a duplicate Cloud Tasks delivery
     * (at-least-once — retries can occasionally overlap in flight) is a safe no-op rather than a
     * second execution attempt — {@code findOrCreateLatestJobRun} would otherwise start a fresh job
     * run even though the first dispatch already completed it.
     *
     * @return true if this call claimed the row and the caller should proceed to run the job
     */
    @Transactional
    public boolean claimQueuedJob(String runId, String jobId) {
        return queueRepository.claimUnclaimedByRunIdAndJobId(runId, jobId) > 0;
    }

    /**
     * The full claim-then-run body shared by both {@link WorkflowJobDispatcher} implementations — the
     * {@code /internal/v1} dispatch endpoint calls it after validating the run token (real Cloud Tasks
     * traffic), and {@link LocalWorkflowJobDispatcher} calls it directly in-process (no queue to
     * validate a token for, since nothing left the JVM). A no-op if the row was already claimed.
     *
     * <p>Calls {@code claimQueuedJob}/{@code checkRunCompletionAfterCommit} through {@link #self},
     * not directly — both are {@code @Transactional}, and a plain {@code this.}-call from a method
     * that (like this one) isn't itself proxied bypasses Spring's AOP entirely, silently running the
     * {@code @Modifying} claim query with no active transaction at all.
     */
    public void claimAndProcessQueuedJob(String runId, String jobId) {
        if (self.claimQueuedJob(runId, jobId)) {
            processJob(runId, jobId);
            self.checkRunCompletionAfterCommit(runId);
        }
    }

    /**
     * NOT @Transactional. Step execution makes long-running external calls (HTTP, Docker, Kestra)
     * — holding a DB connection across those would strand "idle in transaction" sessions on
     * Supabase/Supavisor when a Cloud Run instance is killed mid-step. Transactional boundaries
     * are managed inside {@link WorkflowJobOrchestrator} around the discrete units of DB work.
     *
     * <p>Expected step failures (a connector error, a bad credential, a timeout) are handled inside
     * each step executor and returned as a normal {@code StepResult.failed(...)} — those never reach
     * this method as an exception. This catch is a safety net for the unexpected case: any executor
     * that lets an exception escape uncaught (e.g. a raw {@code RuntimeException} from an HTTP client
     * that isn't translated into a {@code StepResult}) used to strand the job in {@code RUNNING}
     * forever if left uncaught here. Reusing {@link WorkflowJobOrchestrator#completeRemoteJob} here —
     * the same idempotent terminalize-and-propagate path already used by the daemon-pickup-timeout
     * sweep — closes that gap without inventing a new failure path.
     *
     * <p>If {@code completeRemoteJob} itself then throws, that propagates out of this method to the
     * dispatch endpoint's caller, which is Cloud Tasks — a non-2xx response is exactly what makes it
     * retry the dispatch per the queue's retry policy, so this doubles as that failure's recovery path
     * rather than needing one of its own.
     */
    public void processJob(String runId, String jobId) {
        MDC.put("runId", runId);
        MDC.put("jobId", jobId);
        try {
            log.info("processJob started: runId={}, jobId={}", runId, jobId);
            try {
                orchestrator.executeJob(runId, jobId);
            } catch (Exception e) {
                log.error("Unhandled exception executing job {} for run {} — marking FAILED: {}",
                        jobId, runId, e.getMessage(), e);
                orchestrator.completeRemoteJob(runId, jobId, WorkflowJobStatus.FAILED,
                        "INTERNAL_ERROR: " + e.getMessage());
            }
            log.info("processJob finished: runId={}, jobId={}", runId, jobId);
        } finally {
            MDC.remove("runId");
            MDC.remove("jobId");
        }
    }

    /**
     * Runs once, shortly after the application is fully up, so a Cloud Run instance that was killed
     * mid-job (deploy, scale-down, crash) doesn't leave jobs stranded in {@code RUNNING} until the next
     * manual intervention — see {@link #recoverStuckJobs}.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recoverStuckJobsOnStartup() {
        self.recoverStuckJobs();
        self.recoverOrphanedClaims();
    }

    /**
     * On startup: re-open queue rows that were claimed but whose job never got as far as creating a
     * {@code WorkflowJobRun} — the instance died between {@link #claimQueuedJob} and finishing
     * {@link #processJob}. {@link #recoverStuckJobs} can't see these — it looks for RUNNING job runs,
     * and the whole point is that none was ever written. Without this they are invisible to every query
     * in the system and their runs sit in RUNNING until the 24h {@link #cleanupStuckRuns} sweep fails
     * them.
     *
     * <p>Clearing {@code claimedAt} is enough to make them claimable again; it deliberately does not
     * insert a second queue row, which would risk running the job twice. What actually re-drives a
     * cleared row is the original Cloud Task's own retry (it never got a 2xx from the crashed instance,
     * so Cloud Tasks keeps retrying regardless of this sweep) landing on a healthy instance after this
     * has run — this sweep just makes sure that retry finds the row claimable instead of still marked
     * claimed by an instance that no longer exists.
     */
    @Transactional
    public void recoverOrphanedClaims() {
        List<WorkflowJobQueue> orphaned = queueRepository.findClaimedWithoutJobRun();
        for (WorkflowJobQueue queued : orphaned) {
            log.warn("Re-opening queue row for job {} of run {} — claimed but no job run was ever created "
                    + "(instance died between claiming and starting it)", queued.getJobId(), queued.getRun().getId());
            queued.setClaimedAt(null);
            queueRepository.save(queued);
        }
    }

    /**
     * Called after processJob's transaction commits, in a fresh transaction.
     * This ensures all completed job runs from concurrent workers are visible
     * when determining if the overall run is complete.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void checkRunCompletionAfterCommit(String runId) {
        WorkflowRun run = runRepository.findById(runId).orElse(null);
        if (run == null) return;
        checkRunCompletion(run);
    }

    @Transactional
    public void checkRunCompletion(WorkflowRun run) {
        // A straggling job completing after the run already settled (notably after a cancellation
        // terminalized everything) must not recompute and clobber the terminal status.
        if (run.getStatus().isTerminal()) return;

        List<WorkflowJobRun> jobRuns = jobRunRepository.findByRunId(run.getId());
        log.info("checkRunCompletion: runId={}, jobRuns={}", run.getId(), jobRuns.stream().map(j -> j.getJobId() + "=" + j.getStatus()).toList());
        WorkflowDefinition workflow = run.getWorkflow();
        WorkflowSpec parsedWorkflow = parseYaml(workflow.getYaml());
        if (parsedWorkflow == null) return;

        Map<String, JobSpec> jobs = parsedWorkflow.jobs();
        int totalJobs = jobs.size();

        // For loop jobs, there may be multiple WorkflowJobRun rows per jobId.
        // Only consider the LATEST iteration (highest iteration number) for each jobId
        // to determine completion — a new PENDING iteration means the job is still in progress.
        Map<String, WorkflowJobRun> latestByJobId = new java.util.HashMap<>();
        for (WorkflowJobRun jr : jobRuns) {
            latestByJobId.merge(jr.getJobId(), jr, (existing, incoming) ->
                    incoming.getIteration() > existing.getIteration() ? incoming : existing);
        }

        int terminalJobs = (int) latestByJobId.values().stream()
                .filter(j -> j.getStatus().isTerminal())
                .count();

        if (run.getStatus() == WorkflowRunStatus.CANCELLING) {
            // A cancelling run only waits on the jobs that actually started: the ones with no row yet
            // never will get one, since planJobExecution no-ops for a cancelling run. Demanding full
            // job coverage like the normal path below would strand the run in CANCELLING forever.
            // It also settles as CANCELLED regardless of how its jobs landed — a job that failed
            // *because* it was torn down isn't evidence the workflow is broken.
            if (terminalJobs < latestByJobId.size()) return;
            run.setStatus(WorkflowRunStatus.CANCELLED);
        } else {
            if (terminalJobs < totalJobs) return;
            boolean anyFailed = latestByJobId.values().stream()
                    .anyMatch(j -> j.getStatus() == WorkflowJobStatus.FAILED
                            || j.getStatus() == WorkflowJobStatus.LOOP_EXHAUSTED);
            run.setStatus(anyFailed ? WorkflowRunStatus.FAILED : WorkflowRunStatus.SUCCESS);
        }
        run.setCompletedAt(OffsetDateTime.now());
        runRepository.save(run);
        log.info("Run {} completed with status {}", run.getId(), run.getStatus());
        circuitBreaker.recordOutcome(run);
        runFailureNotifier.notifyFailed(run);
    }

    private WorkflowSpec parseYaml(String yaml) {
        try {
            return yamlParser.parse(yaml);
        } catch (WorkflowYamlException e) {
            log.error("Failed to parse workflow YAML: {}", e.getMessage());
            return null;
        }
    }
}
