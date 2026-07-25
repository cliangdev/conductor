package com.conductor.entity;

import java.util.Set;

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

    /**
     * Statuses that count as "this workflow already has a run in flight" for every {@code
     * concurrency: single} gate (manual dispatch, cron schedule, the knowledge-ingest lane check) —
     * the single shared definition all three should query against, so they can't drift out of sync
     * the way {@code WorkflowScheduler}'s own ad hoc {@code {RUNNING, PENDING}} check once did.
     * Deliberately excludes {@link #LOCAL_PICKUP_TIMEOUT}: that status means the self-hosted daemon
     * never claimed the job, so the run is effectively dead and shouldn't block a new one.
     */
    public static final Set<WorkflowRunStatus> ACTIVE_RUN_STATUSES =
            Set.of(PENDING, PENDING_LOCAL_PICKUP, RUNNING, CANCELLING);

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }
}
