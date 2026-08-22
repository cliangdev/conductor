package com.conductor.workflow;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * {@link LocalWorkflowJobDispatcher} is what dispatches jobs in the {@code local} profile — no Cloud
 * Tasks queue exists there — so it must call straight into {@code claimAndProcessQueuedJob} in-process,
 * with the same afterCommit deferral {@link CloudTasksJobDispatcher} uses. Dispatch runs on a separate
 * thread (see the class javadoc for why), so assertions await it rather than checking immediately.
 */
@ExtendWith(MockitoExtension.class)
class LocalWorkflowJobDispatcherTest {

    @Mock WorkflowExecutionEngine engine;

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void dispatchAfterCommit_callsEngine_whenNoActiveTransaction() {
        LocalWorkflowJobDispatcher dispatcher = new LocalWorkflowJobDispatcher(engine);

        dispatcher.dispatchAfterCommit("run-1", "job-1");

        verify(engine, timeout(1000)).claimAndProcessQueuedJob("run-1", "job-1");
    }

    @Test
    void dispatchAfterCommit_defersUntilTransactionCommits() {
        LocalWorkflowJobDispatcher dispatcher = new LocalWorkflowJobDispatcher(engine);

        TransactionSynchronizationManager.initSynchronization();
        dispatcher.dispatchAfterCommit("run-1", "job-1");
        // No race to worry about here: dispatch only ever runs from inside the synchronization's own
        // afterCommit() callback below, which hasn't been invoked yet — not a timing assertion.
        verify(engine, never()).claimAndProcessQueuedJob("run-1", "job-1");

        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());

        verify(engine, timeout(1000)).claimAndProcessQueuedJob("run-1", "job-1");
    }

    @Test
    void dispatchAfterCommit_isolatesCallerFromDispatchFailures() {
        // The whole point of dispatching off-thread: a job that blows up must not propagate back into
        // whatever unrelated request/thread happened to trigger the enqueue (see class javadoc).
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
                .when(engine).claimAndProcessQueuedJob("run-1", "job-1");
        LocalWorkflowJobDispatcher dispatcher = new LocalWorkflowJobDispatcher(engine);

        org.assertj.core.api.Assertions.assertThatNoException()
                .isThrownBy(() -> dispatcher.dispatchAfterCommit("run-1", "job-1"));

        verify(engine, timeout(1000)).claimAndProcessQueuedJob("run-1", "job-1");
    }
}
