package com.conductor.workflow;

import com.conductor.entity.WorkflowStepStatus;

import java.util.Map;

public class StepResult {
    private final WorkflowStepStatus status;
    private final String log;
    private final Map<String, String> outputs;
    private final String errorReason;

    private StepResult(WorkflowStepStatus status, String log,
                       Map<String, String> outputs, String errorReason) {
        this.status = status;
        this.log = log;
        this.outputs = outputs != null ? outputs : Map.of();
        this.errorReason = errorReason;
    }

    public static StepResult success(String log, Map<String, String> outputs) {
        return new StepResult(WorkflowStepStatus.SUCCESS, log, outputs, null);
    }

    public static StepResult failed(String log, String errorReason) {
        return new StepResult(WorkflowStepStatus.FAILED, log, Map.of(), errorReason);
    }

    public static StepResult skipped() {
        return new StepResult(WorkflowStepStatus.SKIPPED, null, Map.of(), null);
    }

    public WorkflowStepStatus getStatus() { return status; }
    public String getLog() { return log; }
    public Map<String, String> getOutputs() { return outputs; }
    public String getErrorReason() { return errorReason; }
}
