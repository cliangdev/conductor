package com.conductor.entity;

public enum WorkflowRunStatus {
    PENDING,
    PENDING_LOCAL_PICKUP,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED,
    LOCAL_PICKUP_TIMEOUT
}
