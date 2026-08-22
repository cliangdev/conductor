package com.conductor.workflow;

/**
 * Pushes a queued workflow job toward execution as a genuine inbound HTTP request, rather than an
 * always-on background thread. Two implementations, selected by Spring profile:
 *
 * <ul>
 *   <li>{@link CloudTasksJobDispatcher} ({@code !local}) — a real Cloud Task hitting the
 *       {@code /internal/v1} dispatch endpoint over the network.</li>
 *   <li>{@link LocalWorkflowJobDispatcher} ({@code local}) — no Cloud Tasks queue exists locally, so
 *       it calls straight into {@link WorkflowExecutionEngine#claimAndProcessQueuedJob} in-process.</li>
 * </ul>
 */
public interface WorkflowJobDispatcher {

    /**
     * Called from {@link WorkflowExecutionEngine#enqueueJob}, itself always {@code @Transactional}.
     * Implementations must defer the actual dispatch until the caller's transaction commits — the
     * {@code workflow_job_queue} insert has to be durable before anything can claim it.
     */
    void dispatchAfterCommit(String runId, String jobId);
}
