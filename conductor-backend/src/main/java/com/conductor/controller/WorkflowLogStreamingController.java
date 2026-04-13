package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.entity.WorkflowJobRun;
import com.conductor.entity.WorkflowJobStatus;
import com.conductor.entity.WorkflowRun;
import com.conductor.entity.WorkflowRunStatus;
import com.conductor.entity.WorkflowStepRun;
import com.conductor.repository.WorkflowJobRunRepository;
import com.conductor.repository.WorkflowRunRepository;
import com.conductor.repository.WorkflowStepRunRepository;
import com.conductor.service.ProjectSecurityService;
import com.conductor.workflow.RunTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@RestController
public class WorkflowLogStreamingController {

    private static final Logger log = LoggerFactory.getLogger(WorkflowLogStreamingController.class);
    private static final long SSE_TIMEOUT_MS = 10 * 60 * 1000L;

    private final WorkflowRunRepository runRepository;
    private final WorkflowJobRunRepository jobRunRepository;
    private final WorkflowStepRunRepository stepRunRepository;
    private final ProjectSecurityService projectSecurityService;
    private final RunTokenService runTokenService;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, SseEmitter> activeEmitters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> runLogs = new ConcurrentHashMap<>();

    public WorkflowLogStreamingController(WorkflowRunRepository runRepository,
                                           WorkflowJobRunRepository jobRunRepository,
                                           WorkflowStepRunRepository stepRunRepository,
                                           ProjectSecurityService projectSecurityService,
                                           RunTokenService runTokenService,
                                           ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.jobRunRepository = jobRunRepository;
        this.stepRunRepository = stepRunRepository;
        this.projectSecurityService = projectSecurityService;
        this.runTokenService = runTokenService;
        this.objectMapper = objectMapper;
    }

    @GetMapping(
            value = "/api/v1/workflow-runs/{runId}/logs/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter streamLogs(@PathVariable String runId) {
        String userId = currentUserId();

        WorkflowRun run = runRepository.findByIdWithWorkflow(runId)
                .orElseThrow(() -> new EntityNotFoundException("Run not found: " + runId));

        String projectId = run.getWorkflow().getProject().getId();
        if (!projectSecurityService.isProjectMember(projectId, userId)) {
            throw new com.conductor.exception.ForbiddenException("Not a project member");
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        WorkflowRunStatus status = run.getStatus();
        boolean isTerminal = isTerminalStatus(status);

        if (isTerminal) {
            sendHistoricalLogsAndClose(emitter, run, status);
            return emitter;
        }

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

    @PostMapping("/internal/workflow-runs/{runId}/log-chunk")
    public ResponseEntity<Void> receiveLogChunk(
            @PathVariable String runId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody LogChunkRequest body) {

        if (!validateRunToken(authHeader, runId)) {
            return ResponseEntity.status(401).build();
        }

        List<String> lines = body.lines() != null ? body.lines() : Collections.emptyList();
        runLogs.computeIfAbsent(runId, k -> Collections.synchronizedList(new ArrayList<>())).addAll(lines);

        SseEmitter emitter = activeEmitters.get(runId);
        if (emitter != null) {
            sendLogChunk(emitter, lines);
        }

        return ResponseEntity.ok().build();
    }

    @PostMapping("/internal/workflow-runs/{runId}/outputs")
    public ResponseEntity<Void> receiveOutputs(
            @PathVariable String runId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody OutputsRequest body) {

        if (!validateRunToken(authHeader, runId)) {
            return ResponseEntity.status(401).build();
        }

        String workerJobId = body.workerJobId();
        Map<String, String> outputs = body.outputs();
        if (workerJobId == null || outputs == null) {
            return ResponseEntity.ok().build();
        }

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
                    return ResponseEntity.ok().build();
                }
            }
        }

        return ResponseEntity.ok().build();
    }

    @PostMapping("/internal/workflow-runs/{runId}/job-failed")
    public ResponseEntity<Void> receiveJobFailed(
            @PathVariable String runId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody JobFailedRequest body) {

        if (!validateRunToken(authHeader, runId)) {
            return ResponseEntity.status(401).build();
        }

        String workerJobId = body.jobId();
        if (workerJobId == null) {
            return ResponseEntity.badRequest().build();
        }

        List<WorkflowJobRun> jobRuns = jobRunRepository.findByRunId(runId);
        for (WorkflowJobRun jobRun : jobRuns) {
            List<WorkflowStepRun> steps = stepRunRepository.findByJobRunId(jobRun.getId());
            for (WorkflowStepRun step : steps) {
                if (workerJobId.equals(step.getWorkerJobId())) {
                    step.setStatus(com.conductor.entity.WorkflowStepStatus.FAILED);
                    step.setErrorReason(body.reason());
                    step.setCompletedAt(OffsetDateTime.now());
                    stepRunRepository.save(step);

                    jobRun.setStatus(WorkflowJobStatus.FAILED);
                    jobRun.setCompletedAt(OffsetDateTime.now());
                    jobRunRepository.save(jobRun);

                    checkAndCompleteRun(runId);
                    return ResponseEntity.ok().build();
                }
            }
        }

        return ResponseEntity.ok().build();
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

    private boolean validateRunToken(String authHeader, String runId) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return false;
        String token = authHeader.substring(7);
        return runTokenService.validateRunToken(token, runId);
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

    private String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (!(principal instanceof User user)) {
            throw new ClassCastException("Expected User principal but got: " +
                    (principal == null ? "null" : principal.getClass().getName()));
        }
        return user.getId();
    }

    public record LogChunkRequest(String workerJobId, List<String> lines, String timestamp) {}
    public record OutputsRequest(String workerJobId, Map<String, String> outputs) {}
    public record JobFailedRequest(String jobId, Integer exitCode, String reason) {}
}
