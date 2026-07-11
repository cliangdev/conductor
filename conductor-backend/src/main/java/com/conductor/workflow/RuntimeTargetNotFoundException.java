package com.conductor.workflow;

/**
 * Thrown by {@link RuntimeTargetResolver#resolve} when a job's {@code runs-on} names a runtime
 * target that doesn't exist in the project (never created, or deleted after the workflow was
 * published). Caught by {@link ClaudeCodeStepExecutor}, which fails the step with errorReason
 * {@code RUNTIME_TARGET_NOT_FOUND} — distinct from {@link RuntimeTargetNotReadyException} so the
 * failure message can tell "doesn't exist" apart from "exists but isn't ACTIVE yet".
 */
public class RuntimeTargetNotFoundException extends RuntimeException {

    public RuntimeTargetNotFoundException(String runsOn) {
        super("No runtime target named '" + runsOn + "' exists in this project");
    }
}
