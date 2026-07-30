package com.conductor.workflow;

import com.conductor.entity.*;
import com.conductor.repository.*;
import com.conductor.workflow.model.JobSpec;
import com.conductor.workflow.model.WorkflowSpec;
import com.conductor.workflow.model.WorkflowYamlException;
import com.conductor.workflow.model.WorkflowYamlParser;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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

    /**
     * Jobs run here rather than on {@code CompletableFuture}'s default pool. That default is only
     * {@code ForkJoinPool.commonPool()} when the pool's parallelism is greater than 1
     * ({@code CompletableFuture.USE_COMMON_POOL}); parallelism is {@code max(1, processors - 1)}, so
     * the common pool is bypassed entirely below **3** available processors and the JDK silently
     * substitutes a thread-per-task executor. Measured, not inferred:
     *
     * <pre>
     *   ActiveProcessorCount=1 → parallelism 1 → Thread-0
     *   ActiveProcessorCount=2 → parallelism 1 → Thread-0
     *   ActiveProcessorCount=3 → parallelism 2 → ForkJoinPool.commonPool-worker-1
     * </pre>
     *
     * Production ran the fallback: every concurrent job spawned a fresh unbounded {@code Thread-NN},
     * all contending for one core, starving the gax executors behind Cloud Run launches and Hikari's
     * housekeeper alike. Note the deploy's {@code --cpu=2} does NOT lift us onto the common pool — this
     * pool is what bounds the fan-out, so don't delete it on the assumption that more vCPUs made the
     * default safe.
     */
    private final ExecutorService jobExecutor;

    /** Pool capacity, so {@link #pollQueueOnce} never claims more than it can start — see {@link #inFlightJobs}. */
    private final int jobPoolSize;

    /**
     * Jobs submitted to {@link #jobExecutor} but not yet finished. Claiming a queue row is a durable
     * side effect that only {@link #recoverOrphanedClaims} can undo, so the poll claims strictly what
     * it has a free thread for rather than letting rows pile up in the executor's unbounded queue where
     * a restart would strand them.
     */
    private final AtomicInteger inFlightJobs = new AtomicInteger();

    // Adaptive poll backoff (in 500ms ticks). Busy (work found last poll) → every tick (500ms).
    // Idle → exponentially back off up to MAX_BACKOFF_TICKS * 500ms = 5s between DB queries.
    private static final int MAX_BACKOFF_TICKS = 10;
    private int currentBackoffTicks = 1;
    private int ticksUntilNextPoll = 0;

    // Self-reference injected lazily to ensure processJob() is called through the Spring proxy,
    // enabling @Transactional to work when invoked from within pollQueue() (self-invocation workaround).
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
                                   @Value("${conductor.workflow.job-executor.pool-size:16}") int jobPoolSize) {
        this.jobExecutor = Executors.newFixedThreadPool(jobPoolSize, namedThreadFactory());
        this.jobPoolSize = jobPoolSize;
        this.queueRepository = queueRepository;
        this.runRepository = runRepository;
        this.jobRunRepository = jobRunRepository;
        this.stepRunRepository = stepRunRepository;
        this.workflowRepository = workflowRepository;
        this.orchestrator = orchestrator;
        this.yamlParser = yamlParser;
        this.circuitBreaker = circuitBreaker;
        this.runFailureNotifier = runFailureNotifier;
    }

    private static ThreadFactory namedThreadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "workflow-job-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    @PreDestroy
    void shutdownJobExecutor() {
        jobExecutor.shutdown();
    }

    /**
     * Tick every 500ms — but only actually query the DB when the adaptive backoff counter
     * allows. Fast (500ms) while jobs are flowing, slow (up to 5s) when the queue is idle.
     * Cuts DB chatter ~10× on an idle system without hurting responsiveness when there's work.
     */
    @Scheduled(fixedDelay = 500)
    public void pollQueue() {
        if (ticksUntilNextPoll > 0) {
            ticksUntilNextPoll--;
            return;
        }

        boolean hadWork;
        try {
            hadWork = self.pollQueueOnce();
        } catch (Exception e) {
            log.error("Error polling workflow job queue: {}", e.getMessage(), e);
            hadWork = false;
        }

        if (hadWork) {
            currentBackoffTicks = 1;
        } else {
            currentBackoffTicks = Math.min(currentBackoffTicks * 2, MAX_BACKOFF_TICKS);
        }
        ticksUntilNextPoll = currentBackoffTicks - 1;
    }

    /**
     * Single-tick DB poll. @Transactional for the short claim + mark-claimed work only;
     * dispatch happens asynchronously after the transaction returns so no connection is
     * held during job processing.
     *
     * @return true if any jobs were claimed and dispatched this tick
     */
    @Transactional
    public boolean pollQueueOnce() {
        int capacity = jobPoolSize - inFlightJobs.get();
        if (capacity <= 0) {
            // Every thread is busy. Leaving the rows unclaimed keeps them visible to the next tick (and
            // to another instance) instead of parking them in an executor queue only a restart-time
            // sweep could rescue.
            return false;
        }
        List<WorkflowJobQueue> entries = queueRepository.claimReadyJobs(capacity);
        if (entries.isEmpty()) return false;
        log.info("Claimed {} job(s) from queue", entries.size());
        List<String> ids = entries.stream().map(WorkflowJobQueue::getId).collect(Collectors.toList());
        queueRepository.markAllClaimed(ids);
        for (WorkflowJobQueue queued : entries) {
            String runId = queued.getRun().getId();
            String jobId = queued.getJobId();
            log.info("Dispatching job {} for run {}", jobId, runId);
            inFlightJobs.incrementAndGet();
            CompletableFuture.runAsync(() -> {
                MDC.put("runId", runId);
                MDC.put("jobId", jobId);
                try {
                    self.processJob(runId, jobId);
                    // After processJob transaction commits, check completion in a fresh transaction
                    // so all concurrent job results are visible.
                    self.checkRunCompletionAfterCommit(runId);
                } catch (Exception e) {
                    // Last-resort net: processJob() now catches and terminalizes job-level failures
                    // itself (see its own try/catch), so this should only ever fire if that recovery
                    // path itself throws (e.g. completeRemoteJob failing) — log loudly since a job is
                    // left stranded in RUNNING with no further automatic recovery at this point.
                    log.error("Error processing job {} — job may be stranded in RUNNING: {}", jobId, e.getMessage(), e);
                } finally {
                    inFlightJobs.decrementAndGet();
                    MDC.remove("runId");
                    MDC.remove("jobId");
                }
            }, jobExecutor);
        }
        return true;
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
     * forever, since the caller ({@link #pollQueueOnce}) only logged it. Reusing {@link
     * WorkflowJobOrchestrator#completeRemoteJob} here — the same idempotent terminalize-and-propagate
     * path already used by the daemon-pickup-timeout sweep — closes that gap without inventing a new
     * failure path.
     */
    public void processJob(String runId, String jobId) {
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
     * {@code WorkflowJobRun}. {@link #recoverStuckJobs} can't see these — it looks for RUNNING job runs,
     * and the whole point is that none was ever written. Without this they are invisible to every query
     * in the system and their runs sit in RUNNING until the 24h {@link #cleanupStuckRuns} sweep fails
     * them.
     *
     * <p>Clearing {@code claimedAt} is enough to make them claimable again; it deliberately does not
     * insert a second queue row, which would risk running the job twice.
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
