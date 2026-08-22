package com.conductor.workflow;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local/self-hosted-dev stand-in for {@link CloudTasksJobDispatcher}: no Cloud Tasks queue exists in
 * the {@code local} profile, so job dispatch instead calls straight into {@link
 * WorkflowExecutionEngine#claimAndProcessQueuedJob} in-process — same afterCommit deferral, same
 * claim-then-process shape as the real dispatch endpoint, just without the network hop. Fine for local
 * dev (there's no CPU-throttled Cloud Run to worry about there); never used in a real deployment.
 *
 * <p>Dispatch still has to happen off the calling thread. {@code afterCommit} callbacks run
 * synchronously, inline, on whatever thread committed the enqueuing transaction — often an HTTP
 * request thread unrelated to the job itself (e.g. the manual-dispatch endpoint, or another job's own
 * completion propagating to a dependent). Calling {@code claimAndProcessQueuedJob} directly from there
 * would block that unrelated request for the job's full duration and let any exception deep in step
 * execution fail it with a 500 — a virtual thread per dispatch decouples the two exactly as the
 * request-based Cloud Run path decouples them by construction (a fresh HTTP request instead).
 */
@Component
@Profile("local")
public class LocalWorkflowJobDispatcher implements WorkflowJobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LocalWorkflowJobDispatcher.class);

    // @Lazy to break the WorkflowExecutionEngine <-> WorkflowJobDispatcher cycle — same pattern as
    // WorkflowJobOrchestrator's @Lazy WorkflowExecutionEngine dependency.
    private final WorkflowExecutionEngine engine;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public LocalWorkflowJobDispatcher(@Lazy WorkflowExecutionEngine engine) {
        this.engine = engine;
    }

    @PreDestroy
    void shutdownExecutor() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void dispatchAfterCommit(String runId, String jobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(runId, jobId);
                }
            });
        } else {
            dispatch(runId, jobId);
        }
    }

    private void dispatch(String runId, String jobId) {
        executor.execute(() -> {
            try {
                engine.claimAndProcessQueuedJob(runId, jobId);
            } catch (Exception e) {
                log.error("Local dispatch failed for run {} job {}: {}", runId, jobId, e.getMessage(), e);
            }
        });
    }
}
