package com.conductor.workflow;

import com.conductor.entity.RuntimeTargetStatus;

/**
 * Thrown by {@link RuntimeTargetResolver#resolve} when a job's {@code runs-on} names a runtime
 * target that exists but isn't {@link RuntimeTargetStatus#ACTIVE} (still {@code PROVISIONING}, or
 * stuck in {@code ERROR}). Caught by {@link ClaudeCodeStepExecutor}, which fails the step with
 * errorReason {@code RUNTIME_TARGET_NOT_READY}.
 */
public class RuntimeTargetNotReadyException extends RuntimeException {

    public RuntimeTargetNotReadyException(String runsOn, RuntimeTargetStatus status) {
        super("Runtime target '" + runsOn + "' is not ready (status: " + status + ")");
    }
}
