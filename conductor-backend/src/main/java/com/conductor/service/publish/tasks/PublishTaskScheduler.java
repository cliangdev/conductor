package com.conductor.service.publish.tasks;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Delivers a {@link PublishTask} to {@link PublishTaskHandler} at its {@code notBefore} as a genuine inbound
 * HTTP request, rather than counting on an always-warm background thread.
 *
 * <p>Production runs the backend on Cloud Run at {@code min-instances=0} with CPU throttling: a background
 * thread only gets CPU while a request is being served, and with no traffic the instance is gone entirely.
 * The publishing pollers ({@code PostPublishScheduler}, {@code NativeHandoffService},
 * {@code NativePublishConfirmationPoller}) were written for an always-on instance and cannot meet the
 * 60-second publishing SLO there. This is the same problem {@code CloudTasksJobDispatcher} solved for
 * workflow jobs, and the same answer: two implementations, selected by Spring profile.
 *
 * <ul>
 *   <li>{@link CloudTasksPublishTaskScheduler} ({@code !local}) — a real Cloud Task with a
 *       {@code scheduleTime}, hitting the {@code /internal/v1/publish-targets} endpoints.</li>
 *   <li>{@link LocalPublishTaskScheduler} ({@code local}) — an in-process timer calling the handler
 *       directly, so a laptop exercises the same path without a queue.</li>
 * </ul>
 *
 * <p>The pollers stay on as a sweep behind this: every task runs the same conditional claim the poller
 * runs, so whichever of the two reaches a row first wins and the other updates nothing.
 */
public interface PublishTaskScheduler {

    /** Deliver {@code task} at its {@code notBefore}. Must never throw: arming is best-effort behind the sweep. */
    void schedule(PublishTask task);

    /**
     * {@link #schedule} once the caller's transaction commits — the row the task is about has to be durable
     * before a request can arrive to claim it. Immediate when no transaction is active (a re-arm from the
     * handler itself, for instance).
     */
    default void scheduleAfterCommit(PublishTask task) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    schedule(task);
                }
            });
        } else {
            schedule(task);
        }
    }
}
