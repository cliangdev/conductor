package com.conductor.workflow;

import com.conductor.entity.*;
import com.conductor.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
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
                                   WorkflowJobOrchestrator orchestrator) {
        this.queueRepository = queueRepository;
        this.runRepository = runRepository;
        this.jobRunRepository = jobRunRepository;
        this.stepRunRepository = stepRunRepository;
        this.workflowRepository = workflowRepository;
        this.orchestrator = orchestrator;
    }

    /** Poll every 500ms — claim ALL ready jobs and dispatch each asynchronously */
    @Scheduled(fixedDelay = 500)
    @Transactional
    public void pollQueue() {
        try {
            List<WorkflowJobQueue> entries = queueRepository.claimAllReadyJobs();
            if (entries.isEmpty()) return;
            log.info("Claimed {} job(s) from queue", entries.size());
            List<String> ids = entries.stream().map(WorkflowJobQueue::getId).collect(Collectors.toList());
            queueRepository.markAllClaimed(ids);
            for (WorkflowJobQueue queued : entries) {
                String runId = queued.getRun().getId();
                String jobId = queued.getJobId();
                log.info("Dispatching job {} for run {}", jobId, runId);
                CompletableFuture.runAsync(() -> {
                    try {
                        self.processJob(runId, jobId);
                        // After processJob transaction commits, check completion in a fresh transaction
                        // so all concurrent job results are visible.
                        self.checkRunCompletionAfterCommit(runId);
                    } catch (Exception e) {
                        log.error("Error processing job {}: {}", jobId, e.getMessage(), e);
                    }
                });
            }
        } catch (Exception e) {
            log.error("Error polling workflow job queue: {}", e.getMessage(), e);
        }
    }

    /** On startup: re-enqueue any jobs stuck in RUNNING state */
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

    /** Daily: mark runs stuck in RUNNING for >24h as FAILED */
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
            }
        }
    }

    @Transactional
    public void enqueueJob(String runId, String jobId) {
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
     */
    public void processJob(String runId, String jobId) {
        log.info("processJob started: runId={}, jobId={}", runId, jobId);
        orchestrator.executeJob(runId, jobId);
        log.info("processJob finished: runId={}, jobId={}", runId, jobId);
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
        List<WorkflowJobRun> jobRuns = jobRunRepository.findByRunId(run.getId());
        log.info("checkRunCompletion: runId={}, jobRuns={}", run.getId(), jobRuns.stream().map(j -> j.getJobId() + "=" + j.getStatus()).toList());
        WorkflowDefinition workflow = run.getWorkflow();
        Map<String, Object> parsedWorkflow = parseYaml(workflow.getYaml());
        if (parsedWorkflow == null) return;

        @SuppressWarnings("unchecked")
        Map<String, Object> jobs = (Map<String, Object>) parsedWorkflow.get("jobs");
        if (jobs == null) return;

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
                .filter(j -> isTerminal(j.getStatus()))
                .count();

        if (terminalJobs < totalJobs) return;

        boolean anyFailed = latestByJobId.values().stream()
                .anyMatch(j -> j.getStatus() == WorkflowJobStatus.FAILED
                        || j.getStatus() == WorkflowJobStatus.LOOP_EXHAUSTED);
        run.setStatus(anyFailed ? WorkflowRunStatus.FAILED : WorkflowRunStatus.SUCCESS);
        run.setCompletedAt(OffsetDateTime.now());
        runRepository.save(run);
        log.info("Run {} completed with status {}", run.getId(), run.getStatus());
    }

    private boolean isTerminal(WorkflowJobStatus status) {
        return status == WorkflowJobStatus.SUCCESS
                || status == WorkflowJobStatus.FAILED
                || status == WorkflowJobStatus.SKIPPED
                || status == WorkflowJobStatus.LOOP_EXHAUSTED;
    }

    private Map<String, Object> parseYaml(String yaml) {
        try {
            org.yaml.snakeyaml.Yaml snakeYaml = new org.yaml.snakeyaml.Yaml();
            return snakeYaml.load(yaml);
        } catch (Exception e) {
            log.error("Failed to parse workflow YAML: {}", e.getMessage());
            return null;
        }
    }
}
