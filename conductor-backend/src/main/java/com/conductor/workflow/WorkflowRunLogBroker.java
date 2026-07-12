package com.conductor.workflow;

import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.entity.WorkflowStepStatus;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.service.LogRedactionService;
import com.conductor.workflow.model.JobSpec;
import com.conductor.workflow.model.StepSpec;
import com.conductor.workflow.model.WorkflowSpec;
import com.conductor.workflow.model.WorkflowYamlException;
import com.conductor.workflow.model.WorkflowYamlParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared coordination state for workflow run logs — the single owner of the in-memory SSE emitters and
 * buffered log lines. Both API surfaces delegate here so the public stream and the internal worker
 * callbacks can stay in separate controllers (and separate URL spaces) without fracturing this state:
 *
 * <ul>
 *   <li>{@code WorkflowLogStreamController} (external, {@code /api/v1}) registers SSE subscribers.</li>
 *   <li>{@code WorkflowInternalCallbackController} (internal, {@code /internal/v1}) pushes log chunks,
 *       outputs, and job-failure signals from the worker.</li>
 * </ul>
 *
 * Sharing a <i>service</i> across the internal/external boundary is intentional; what stays separated is
 * the API surface (specs, packages, controllers, prefixes).
 */
@Component
public class WorkflowRunLogBroker {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunLogBroker.class);
    private static final long SSE_TIMEOUT_MS = 10 * 60 * 1000L;

    private final WorkflowRunRepository runRepository;
    private final WorkflowJobRunRepository jobRunRepository;
    private final WorkflowStepRunRepository stepRunRepository;
    private final ObjectMapper objectMapper;
    private final LogRedactionService logRedactionService;
    private final WorkflowYamlParser yamlParser;

    private final ConcurrentHashMap<String, SseEmitter> activeEmitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> runLogs = new ConcurrentHashMap<>();

    public WorkflowRunLogBroker(WorkflowRunRepository runRepository,
                                WorkflowJobRunRepository jobRunRepository,
                                WorkflowStepRunRepository stepRunRepository,
                                ObjectMapper objectMapper,
                                LogRedactionService logRedactionService,
                                WorkflowYamlParser yamlParser) {
        this.runRepository = runRepository;
        this.jobRunRepository = jobRunRepository;
        this.stepRunRepository = stepRunRepository;
        this.objectMapper = objectMapper;
        this.logRedactionService = logRedactionService;
        this.yamlParser = yamlParser;
    }

    /**
     * Open an SSE stream for an already-authorized run. Terminal runs get their historical logs and
     * are closed immediately; active runs are registered and replayed any buffered lines.
     */
    public SseEmitter register(WorkflowRun run) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        WorkflowRunStatus status = run.getStatus();

        if (isTerminalStatus(status)) {
            sendHistoricalLogsAndClose(emitter, run, status);
            return emitter;
        }

        String runId = run.getId();
        activeEmitters.put(runId, emitter);

        List<String> existingLines = runLogs.getOrDefault(runId, Collections.emptyList());
        if (!existingLines.isEmpty()) {
            sendLogChunk(emitter, new ArrayList<>(existingLines));
        }

        emitter.onCompletion(() -> activeEmitters.remove(runId));
        emitter.onTimeout(() -> activeEmitters.remove(runId));
        emitter.onError(e -> activeEmitters.remove(runId));

        return emitter;
    }

    /** Buffer log lines for a run and fan them out to any live subscriber. No step-row persistence. */
    public void appendLogChunk(String runId, List<String> lines) {
        appendLogChunk(runId, null, lines);
    }

    /**
     * Buffer log lines for a run (SSE fan-out + run-level buffer, unconditional — same as the
     * 2-arg overload) and, when {@code workerJobId} is present, ALSO append them to that step's
     * persisted {@code log} column so the run-detail REST API and the polling UI show live
     * container output instead of only the terminal launcher summary. {@code workerJobId} is
     * optional for backward compat with runner images that don't send it yet; an unresolvable
     * workerJobId (already-cleaned-up run, race with row creation) degrades to run-level-only
     * buffering rather than failing the callback.
     */
    public void appendLogChunk(String runId, String workerJobId, List<String> lines) {
        runLogs.computeIfAbsent(runId, k -> Collections.synchronizedList(new ArrayList<>())).addAll(lines);
        SseEmitter emitter = activeEmitters.get(runId);
        if (emitter != null) {
            sendLogChunk(emitter, lines);
        }
        if (workerJobId == null || lines.isEmpty()) {
            return;
        }
        Optional<WorkflowStepRun> step = findStepRunByWorkerJobId(runId, workerJobId);
        if (step.isPresent()) {
            appendToStepLog(step.get(), lines, resolveProjectId(runId));
        } else {
            log.debug("appendLogChunk: no step run found for workerJobId {} in run {}, buffered at "
                    + "run-level only", workerJobId, runId);
        }
    }

    private Optional<WorkflowStepRun> findStepRunByWorkerJobId(String runId, String workerJobId) {
        List<WorkflowJobRun> jobRuns = jobRunRepository.findByRunId(runId);
        for (WorkflowJobRun jobRun : jobRuns) {
            for (WorkflowStepRun step : stepRunRepository.findByJobRunId(jobRun.getId())) {
                if (workerJobId.equals(step.getWorkerJobId())) {
                    return Optional.of(step);
                }
            }
        }
        return Optional.empty();
    }

    private String resolveProjectId(String runId) {
        return runRepository.findByIdWithWorkflow(runId)
                .map(r -> r.getWorkflow().getProject().getId())
                .orElse(null);
    }

    /**
     * Appends log lines to a step run's persisted {@code log} column (redacting secrets first) and
     * saves. Shared by the streamed log-chunk callback above and {@link ClaudeCodeStepExecutor}'s
     * launcher-side status lines, so both land on the row in arrival order.
     *
     * <p>The row is ALWAYS re-read fresh by id before appending: callers (the executor in
     * particular) hold their entity across the whole step execution, so appending to the caller's
     * in-memory copy and saving it would overwrite every container-streamed chunk that landed on
     * the row in the meantime with the caller's stale snapshot — seen live as "the claude logs
     * vanish when the step completes". Plain fresh read-modify-write beyond that: concurrent chunk
     * posts for one step are unlikely (single container, ~2s batches) so no sequencing/locking is
     * attempted — a rare interleaving could drop a line, an acceptable tradeoff for a display-only
     * log.
     */
    void appendToStepLog(WorkflowStepRun step, List<String> lines, String projectId) {
        if (lines.isEmpty()) {
            return;
        }
        String chunk = String.join("\n", lines) + "\n";
        String redacted = projectId != null ? logRedactionService.redact(projectId, chunk) : chunk;
        WorkflowStepRun fresh = step.getId() != null
                ? stepRunRepository.findById(step.getId()).orElse(step)
                : step;
        String existing = fresh.getLog();
        fresh.setLog(existing != null ? existing + redacted : redacted);
        stepRunRepository.save(fresh);
        // Keep the caller's copy consistent so a later append through the same reference doesn't
        // resurrect a stale prefix if the row lookup above ever misses.
        step.setLog(fresh.getLog());
    }

    /** Record step outputs for a worker job (idempotent; unknown job is a no-op). */
    public void recordOutputs(String runId, String workerJobId, Map<String, String> outputs) {
        List<WorkflowJobRun> jobRuns = jobRunRepository.findByRunId(runId);
        for (WorkflowJobRun jobRun : jobRuns) {
            List<WorkflowStepRun> steps = stepRunRepository.findByJobRunId(jobRun.getId());
            for (WorkflowStepRun step : steps) {
                if (workerJobId.equals(step.getWorkerJobId())) {
                    try {
                        step.setOutputJson(objectMapper.writeValueAsString(outputs));
                        stepRunRepository.save(step);
                    } catch (Exception e) {
                        log.warn("Failed to serialize outputs for step {}", step.getId(), e);
                    }
                    return;
                }
            }
        }
    }

    /**
     * Record a worker step's terminal result on the pre-created step run (idempotent; unknown
     * workerJobId is a no-op — the run may have been cleaned up). exitCode is accepted but not
     * persisted (no column on {@code workflow_step_runs}); rolling the result up to the job/run is
     * owned elsewhere (the daemon's job-complete callback for self-hosted, the Cloud Run executor's
     * poll loop for cloud-run) — not here.
     */
    public void recordStepCompleted(String runId, String workerJobId, WorkflowStepStatus status,
                                    Integer exitCode, String errorReason, Map<String, String> outputs) {
        List<WorkflowJobRun> jobRuns = jobRunRepository.findByRunId(runId);
        for (WorkflowJobRun jobRun : jobRuns) {
            List<WorkflowStepRun> steps = stepRunRepository.findByJobRunId(jobRun.getId());
            for (WorkflowStepRun step : steps) {
                if (workerJobId.equals(step.getWorkerJobId())) {
                    if (isTerminal(step.getStatus())) {
                        // A late daemon backstop post (e.g. after the container already self-reported)
                        // must not flip an already-terminal, container-reported result.
                        log.info("recordStepCompleted: step {} (workerJobId={}) already terminal ({}), "
                                + "ignoring late report", step.getId(), workerJobId, step.getStatus());
                        return;
                    }
                    step.setStatus(status);
                    step.setErrorReason(errorReason);
                    Map<String, String> mappedOutputs = outputs != null && !outputs.isEmpty()
                            ? applyDeclaredOutputs(runId, jobRun, step, outputs)
                            : outputs;
                    if (mappedOutputs != null) {
                        try {
                            step.setOutputJson(objectMapper.writeValueAsString(mappedOutputs));
                        } catch (Exception e) {
                            log.warn("Failed to serialize outputs for step {}", step.getId(), e);
                        }
                    }
                    if (step.getStartedAt() == null) {
                        step.setStartedAt(OffsetDateTime.now());
                    }
                    step.setCompletedAt(OffsetDateTime.now());
                    stepRunRepository.save(step);
                    return;
                }
            }
        }
        log.warn("recordStepCompleted: no step run found for workerJobId {} in run {}", workerJobId, runId);
    }

    /**
     * Applies the step's declared {@code outputs:} dot-path mapping (same as
     * {@link ClaudeCodeStepExecutor#resultFromRow}) so self-hosted and cloud-run runtimes produce
     * identical step outputs. Resolves the step's YAML definition by {@code stepId} when the
     * pre-created row has one, else by the numeric index suffix of {@code workerJobId}
     * ({@code jobRunId:index}). Falls back to the outputs unmapped (never throws) if the step
     * definition can't be resolved.
     */
    private Map<String, String> applyDeclaredOutputs(String runId, WorkflowJobRun jobRun,
                                                      WorkflowStepRun step, Map<String, String> outputs) {
        Optional<WorkflowRun> runOpt = runRepository.findByIdWithWorkflow(runId);
        if (runOpt.isEmpty()) {
            log.warn("recordStepCompleted: run {} not found while resolving declared outputs for step {}",
                    runId, step.getId());
            return outputs;
        }
        StepSpec stepDef = resolveStepDefinition(runOpt.get(), jobRun, step);
        if (stepDef == null) {
            log.warn("recordStepCompleted: could not resolve step definition for workerJobId {} in run {}, "
                    + "persisting outputs unmapped", step.getWorkerJobId(), runId);
            return outputs;
        }
        Map<String, String> mappedOutputs = new HashMap<>(outputs);
        StepOutputMapper.applyDeclaredOutputs(stepDef.raw(),
                StepOutputMapper.outputsTree(objectMapper, mappedOutputs), mappedOutputs);
        return mappedOutputs;
    }

    private StepSpec resolveStepDefinition(WorkflowRun run, WorkflowJobRun jobRun, WorkflowStepRun step) {
        WorkflowSpec parsedWorkflow = parseYaml(run.getWorkflow().getYaml());
        if (parsedWorkflow == null) return null;
        JobSpec jobDef = parsedWorkflow.jobs().get(jobRun.getJobId());
        if (jobDef == null) return null;
        List<StepSpec> steps = jobDef.executableSteps();

        String stepId = step.getStepId();
        if (stepId != null) {
            return steps.stream().filter(s -> stepId.equals(s.id())).findFirst().orElse(null);
        }

        Integer index = parseIndexSuffix(step.getWorkerJobId());
        if (index == null || index < 0 || index >= steps.size()) return null;
        return steps.get(index);
    }

    /** Extracts the trailing {@code :N} index from a {@code jobRunId:N} workerJobId. */
    private Integer parseIndexSuffix(String workerJobId) {
        if (workerJobId == null) return null;
        int colonIdx = workerJobId.lastIndexOf(':');
        if (colonIdx < 0 || colonIdx == workerJobId.length() - 1) return null;
        try {
            return Integer.parseInt(workerJobId.substring(colonIdx + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private WorkflowSpec parseYaml(String yaml) {
        try {
            return yamlParser.parse(yaml);
        } catch (WorkflowYamlException e) {
            log.error("Failed to parse workflow YAML: {}", e.getMessage());
            return null;
        }
    }

    /** Mark a worker job's step failed and roll the failure up to the job and (if needed) the run. */
    public void recordJobFailed(String runId, String workerJobId, String reason) {
        List<WorkflowJobRun> jobRuns = jobRunRepository.findByRunId(runId);
        for (WorkflowJobRun jobRun : jobRuns) {
            List<WorkflowStepRun> steps = stepRunRepository.findByJobRunId(jobRun.getId());
            for (WorkflowStepRun step : steps) {
                if (workerJobId.equals(step.getWorkerJobId())) {
                    step.setStatus(WorkflowStepStatus.FAILED);
                    step.setErrorReason(reason);
                    step.setCompletedAt(OffsetDateTime.now());
                    stepRunRepository.save(step);

                    jobRun.setStatus(WorkflowJobStatus.FAILED);
                    jobRun.setCompletedAt(OffsetDateTime.now());
                    jobRunRepository.save(jobRun);

                    checkAndCompleteRun(runId);
                    return;
                }
            }
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void closeTerminalRunEmitters() {
        activeEmitters.forEach((runId, emitter) -> {
            Optional<WorkflowRun> runOpt = runRepository.findByIdWithWorkflow(runId);
            if (runOpt.isEmpty() || isTerminalStatus(runOpt.get().getStatus())) {
                WorkflowRunStatus status = runOpt.map(WorkflowRun::getStatus).orElse(WorkflowRunStatus.FAILED);
                sendRunCompleteAndClose(emitter, status);
                activeEmitters.remove(runId);
                runLogs.remove(runId);
            }
        });
    }

    private void checkAndCompleteRun(String runId) {
        WorkflowRun run = runRepository.findByIdWithWorkflow(runId).orElse(null);
        if (run == null || isTerminalStatus(run.getStatus())) return;

        List<WorkflowJobRun> jobRuns = jobRunRepository.findByRunId(runId);
        boolean anyFailed = jobRuns.stream().anyMatch(j -> j.getStatus() == WorkflowJobStatus.FAILED);
        boolean allDone = jobRuns.stream().allMatch(j -> isTerminalJobStatus(j.getStatus()));

        if (anyFailed || allDone) {
            run.setStatus(anyFailed ? WorkflowRunStatus.FAILED : WorkflowRunStatus.SUCCESS);
            run.setCompletedAt(OffsetDateTime.now());
            runRepository.save(run);

            SseEmitter emitter = activeEmitters.remove(runId);
            if (emitter != null) {
                sendRunCompleteAndClose(emitter, run.getStatus());
            }
            runLogs.remove(runId);
        }
    }

    private void sendHistoricalLogsAndClose(SseEmitter emitter, WorkflowRun run, WorkflowRunStatus status) {
        try {
            List<WorkflowJobRun> jobRuns = jobRunRepository.findByRunId(run.getId());
            List<String> lines = new ArrayList<>();
            for (WorkflowJobRun jobRun : jobRuns) {
                List<WorkflowStepRun> steps = stepRunRepository.findByJobRunId(jobRun.getId());
                for (WorkflowStepRun step : steps) {
                    if (step.getLog() != null && !step.getLog().isBlank()) {
                        Collections.addAll(lines, step.getLog().split("\n"));
                    }
                }
            }
            if (!lines.isEmpty()) {
                sendLogChunk(emitter, lines);
            }
            sendRunCompleteAndClose(emitter, status);
        } catch (Exception e) {
            log.warn("Error sending historical logs for run {}", run.getId(), e);
            emitter.completeWithError(e);
        }
    }

    private void sendLogChunk(SseEmitter emitter, List<String> lines) {
        try {
            Map<String, Object> payload = Map.of(
                    "lines", lines,
                    "timestamp", OffsetDateTime.now().toString()
            );
            emitter.send(SseEmitter.event()
                    .name("log-chunk")
                    .data(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            log.debug("SSE client disconnected while sending log chunk", e);
        }
    }

    private void sendRunCompleteAndClose(SseEmitter emitter, WorkflowRunStatus status) {
        try {
            Map<String, String> payload = Map.of("status", status.name());
            emitter.send(SseEmitter.event()
                    .name("run-complete")
                    .data(objectMapper.writeValueAsString(payload)));
        } catch (IOException e) {
            log.debug("SSE client disconnected while sending run-complete", e);
        } finally {
            emitter.complete();
        }
    }

    private boolean isTerminalStatus(WorkflowRunStatus status) {
        return status == WorkflowRunStatus.SUCCESS
                || status == WorkflowRunStatus.FAILED
                || status == WorkflowRunStatus.CANCELLED;
    }

    private boolean isTerminal(WorkflowStepStatus status) {
        return status == WorkflowStepStatus.SUCCESS || status == WorkflowStepStatus.FAILED;
    }

    private boolean isTerminalJobStatus(WorkflowJobStatus status) {
        return status == WorkflowJobStatus.SUCCESS
                || status == WorkflowJobStatus.FAILED
                || status == WorkflowJobStatus.SKIPPED
                || status == WorkflowJobStatus.LOOP_EXHAUSTED;
    }
}
