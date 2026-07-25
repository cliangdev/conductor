package com.conductor.entity;

public enum WorkflowRunStatus {
    PENDING,
    PENDING_LOCAL_PICKUP,
    RUNNING,
    /** Cancel requested; still tearing down in-flight work. Settles to {@link #CANCELLED}. */
    CANCELLING,
    SUCCESS,
    FAILED,
    CANCELLED,
    LOCAL_PICKUP_TIMEOUT;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }
}
