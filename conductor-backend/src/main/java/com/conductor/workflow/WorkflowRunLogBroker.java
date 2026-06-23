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

    private final ConcurrentHashMap<String, SseEmitter> activeEmitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> runLogs = new ConcurrentHashMap<>();

    public WorkflowRunLogBroker(WorkflowRunRepository runRepository,
                                WorkflowJobRunRepository jobRunRepository,
                                WorkflowStepRunRepository stepRunRepository,
                                ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.jobRunRepository = jobRunRepository;
        this.stepRunRepository = stepRunRepository;
        this.objectMapper = objectMapper;
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

    /** Buffer log lines for a run and fan them out to any live subscriber. */
    public void appendLogChunk(String runId, List<String> lines) {
        runLogs.computeIfAbsent(runId, k -> Collections.synchronizedList(new ArrayList<>())).addAll(lines);
        SseEmitter emitter = activeEmitters.get(runId);
        if (emitter != null) {
            sendLogChunk(emitter, lines);
        }
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

    private boolean isTerminalJobStatus(WorkflowJobStatus status) {
        return status == WorkflowJobStatus.SUCCESS
                || status == WorkflowJobStatus.FAILED
                || status == WorkflowJobStatus.SKIPPED
                || status == WorkflowJobStatus.LOOP_EXHAUSTED;
    }
}
