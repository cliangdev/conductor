package com.conductor.entity;

public enum WorkflowJobStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED,
    LOOP_EXHAUSTED,
    AWAITING_PICKUP,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == SKIPPED
                || this == LOOP_EXHAUSTED || this == CANCELLED;
    }
}
