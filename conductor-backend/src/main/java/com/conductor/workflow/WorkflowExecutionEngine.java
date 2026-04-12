package com.conductor.workflow;

import com.conductor.entity.*;
import com.conductor.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class WorkflowExecutionEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowExecutionEngine.class);

    private final WorkflowJobQueueRepository queueRepository;
    private final WorkflowRunRepository runRepository;
    private final WorkflowJobRunRepository jobRunRepository;
    private final WorkflowStepRunRepository stepRunRepository;
    private final WorkflowDefinitionRepository workflowRepository;
    private final WorkflowJobOrchestrator orchestrator;

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

    /** Poll every 500ms for queued jobs */
    @Scheduled(fixedDelay = 500)
    public void pollQueue() {
        try {
            Optional<WorkflowJobQueue> entry = queueRepository.claimNextJob();
            if (entry.isEmpty()) return;
            WorkflowJobQueue queued = entry.get();
            queueRepository.markClaimed(queued.getId());
            processJob(queued.getRun().getId(), queued.getJobId());
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

    @Transactional
    public void processJob(String runId, String jobId) {
        WorkflowRun run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            log.warn("Run {} not found, skipping job {}", runId, jobId);
            return;
        }

        orchestrator.executeJob(run, jobId);

        checkRunCompletion(run);
    }

    private void checkRunCompletion(WorkflowRun run) {
        List<WorkflowJobRun> jobRuns = jobRunRepository.findByRunId(run.getId());
        WorkflowDefinition workflow = run.getWorkflow();
        Map<String, Object> parsedWorkflow = parseYaml(workflow.getYaml());
        if (parsedWorkflow == null) return;

        @SuppressWarnings("unchecked")
        Map<String, Object> jobs = (Map<String, Object>) parsedWorkflow.get("jobs");
        if (jobs == null) return;

        int totalJobs = jobs.size();
        int terminalJobs = (int) jobRuns.stream()
                .filter(j -> isTerminal(j.getStatus()))
                .count();

        if (terminalJobs < totalJobs) return;

        boolean anyFailed = jobRuns.stream()
                .anyMatch(j -> j.getStatus() == WorkflowJobStatus.FAILED);
        run.setStatus(anyFailed ? WorkflowRunStatus.FAILED : WorkflowRunStatus.SUCCESS);
        run.setCompletedAt(OffsetDateTime.now());
        runRepository.save(run);
        log.info("Run {} completed with status {}", run.getId(), run.getStatus());
    }

    private boolean isTerminal(WorkflowJobStatus status) {
        return status == WorkflowJobStatus.SUCCESS
                || status == WorkflowJobStatus.FAILED
                || status == WorkflowJobStatus.SKIPPED;
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
