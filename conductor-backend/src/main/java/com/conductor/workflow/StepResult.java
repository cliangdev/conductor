package com.conductor.workflow;

import com.conductor.entity.WorkflowStepStatus;

import java.util.Map;

public class StepResult {
    private final WorkflowStepStatus status;
    private final String log;
    private final Map<String, String> outputs;
    private final String errorReason;
    private final String workerJobId;

    private StepResult(WorkflowStepStatus status, String log,
                       Map<String, String> outputs, String errorReason, String workerJobId) {
        this.status = status;
        this.log = log;
        this.outputs = outputs != null ? outputs : Map.of();
        this.errorReason = errorReason;
        this.workerJobId = workerJobId;
    }

    public static StepResult success(String log, Map<String, String> outputs) {
        return new StepResult(WorkflowStepStatus.SUCCESS, log, outputs, null, null);
    }

    public static StepResult failed(String log, String errorReason) {
        return new StepResult(WorkflowStepStatus.FAILED, log, Map.of(), errorReason, null);
    }

    public static StepResult skipped() {
        return new StepResult(WorkflowStepStatus.SKIPPED, null, Map.of(), null, null);
    }

    /**
     * Tags this result with the workerJobId of a pre-created {@code WorkflowStepRun} (e.g. the Cloud
     * Run executor's UUID row) so the orchestrator updates that row in place instead of inserting a
     * duplicate.
     */
    public StepResult withWorkerJobId(String workerJobId) {
        return new StepResult(status, log, outputs, errorReason, workerJobId);
    }

    public WorkflowStepStatus getStatus() { return status; }
    public String getLog() { return log; }
    public Map<String, String> getOutputs() { return outputs; }
    public String getErrorReason() { return errorReason; }
    public String getWorkerJobId() { return workerJobId; }
}
