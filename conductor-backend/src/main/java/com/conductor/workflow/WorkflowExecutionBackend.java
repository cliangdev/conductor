package com.conductor.workflow;

/**
 * Abstraction for executing a single workflow step.
 * Phase 1a: HttpStepExecutor. Phase 1b: DockerStepExecutor.
 */
public interface WorkflowExecutionBackend {
    /** Returns the step type this executor handles (e.g., "http") */
    String getStepType();

    /**
     * Execute the step. Returns a StepResult with status, log, output values, and error reason.
     * Must not throw — exceptions are caught by the engine and recorded as FAILED.
     */
    StepResult execute(StepExecutionContext context);
}
