package com.conductor.workflow;

import com.conductor.signal.Signal;
import com.conductor.signal.SignalBus;
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SafeSignalPublishTest {

    private static final Logger log = LoggerFactory.getLogger(SafeSignalPublishTest.class);

    @Mock SignalBus signalBus;

    private final Signal signal = Signal.of(SignalTypes.CONDUCTOR_WORKFLOW_RUN_FAILED, "proj-1", "run-1",
            Instant.now(), Map.of("runId", "run-1"), new SignalOrigin("test", "run-1"));

    @AfterEach
    void clearAnySynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void publishesImmediately_whenNoTransactionIsActive() {
        SafeSignalPublish.afterCommit(signalBus, signal, log);

        verify(signalBus).publish(signal);
    }

    @Test
    void defersUntilAfterCommit_whenATransactionIsActive() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            SafeSignalPublish.afterCommit(signalBus, signal, log);

            // Not published yet -- only registered to fire once the transaction actually commits.
            verify(signalBus, never()).publish(any());

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }

            verify(signalBus).publish(signal);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void doesNotPublish_ifRolledBack_whenATransactionIsActive() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            SafeSignalPublish.afterCommit(signalBus, signal, log);

            // Rollback never invokes afterCommit() -- simulated here by simply never triggering it and
            // discarding the registered synchronizations, mirroring what Spring's transaction
            // interceptor does on a rollback.
            TransactionSynchronizationManager.clearSynchronization();

            verify(signalBus, never()).publish(any());
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
        }
    }

    @Test
    void swallowsSignalBusFailure_whenPublishingImmediately() {
        doThrow(new RuntimeException("Discord webhook unreachable")).when(signalBus).publish(any());

        assertThatNoException().isThrownBy(() -> SafeSignalPublish.afterCommit(signalBus, signal, log));
    }

    @Test
    void swallowsSignalBusFailure_whenPublishingAfterCommit() {
        doThrow(new RuntimeException("Discord webhook unreachable")).when(signalBus).publish(any());

        TransactionSynchronizationManager.initSynchronization();
        try {
            SafeSignalPublish.afterCommit(signalBus, signal, log);

            assertThatNoException().isThrownBy(() -> {
                for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                    synchronization.afterCommit();
                }
            });
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
