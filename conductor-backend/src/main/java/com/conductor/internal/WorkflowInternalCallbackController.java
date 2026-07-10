package com.conductor.internal;

import com.conductor.entity.WorkflowStepStatus;
import com.conductor.generated.internal.api.WorkflowInternalApi;
import com.conductor.generated.internal.model.JobFailedRequest;
import com.conductor.generated.internal.model.LogChunkRequest;
import com.conductor.generated.internal.model.OutputsRequest;
import com.conductor.generated.internal.model.StepCompleteRequest;
import com.conductor.workflow.RunTokenService;
import com.conductor.workflow.WorkflowRunLogBroker;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Internal control-plane for the workflow engine: worker/daemon callbacks that stream logs and report
 * job outcomes. Implements the generated {@link WorkflowInternalApi} (OpenAPI-first; defined in
 * {@code openapi-internal.yaml}, NOT the public {@code openapi.yaml}). Served under {@code /internal/v1}
 * — the prefix is supplied by {@link com.conductor.config.ApiPathConfig} because this controller lives
 * in the {@code com.conductor.internal} package; mappings here are bare.
 *
 * Auth is a per-run bearer token ({@link RunTokenService}), not the app JWT — {@code /internal/**} is
 * {@code permitAll} in security config and each call validates its own token. Persistence/emitter
 * mechanics are delegated to the shared {@link WorkflowRunLogBroker}.
 */
@RestController
public class WorkflowInternalCallbackController implements WorkflowInternalApi {

    private final RunTokenService runTokenService;
    private final WorkflowRunLogBroker broker;

    public WorkflowInternalCallbackController(RunTokenService runTokenService, WorkflowRunLogBroker broker) {
        this.runTokenService = runTokenService;
        this.broker = broker;
    }

    @Override
    public ResponseEntity<Void> appendWorkflowRunLogChunk(String runId, LogChunkRequest body) {
        if (!validateRunToken(runId)) {
            return ResponseEntity.status(401).build();
        }
        List<String> lines = body.getLines() != null ? body.getLines() : Collections.emptyList();
        broker.appendLogChunk(runId, lines);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> reportWorkflowRunOutputs(String runId, OutputsRequest body) {
        if (!validateRunToken(runId)) {
            return ResponseEntity.status(401).build();
        }
        String workerJobId = body.getWorkerJobId();
        Map<String, String> outputs = body.getOutputs();
        if (workerJobId == null || outputs == null) {
            return ResponseEntity.ok().build();
        }
        broker.recordOutputs(runId, workerJobId, outputs);
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> reportWorkflowRunJobFailed(String runId, JobFailedRequest body) {
        if (!validateRunToken(runId)) {
            return ResponseEntity.status(401).build();
        }
        String workerJobId = body.getJobId();
        if (workerJobId == null) {
            return ResponseEntity.badRequest().build();
        }
        broker.recordJobFailed(runId, workerJobId, body.getReason());
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> completeWorkflowRunStep(String runId, String workerJobId, StepCompleteRequest body) {
        if (!validateRunToken(runId)) {
            return ResponseEntity.status(401).build();
        }
        broker.recordStepCompleted(runId, workerJobId, WorkflowStepStatus.valueOf(body.getStatus()),
                body.getExitCode(), body.getErrorReason(), body.getOutputs());
        return ResponseEntity.ok().build();
    }

    private boolean validateRunToken(String runId) {
        HttpServletRequest request =
                ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return false;
        }
        String token = authHeader.substring(7);
        return runTokenService.validateRunToken(token, runId);
    }
}
