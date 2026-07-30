package com.conductor.workflow;

import com.conductor.signal.Signal;
import com.conductor.signal.SignalBus;
import org.slf4j.Logger;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Shared safety wrapper for the run-settlement signals that fire from inside the very transaction that
 * durably records the fact they describe -- {@link WorkflowRunFailureNotifier#notifyFailed}'s {@code
 * CONDUCTOR_WORKFLOW_RUN_FAILED} and {@link WorkflowFailureCircuitBreaker#recordOutcome}'s {@code
 * CONDUCTOR_WORKFLOW_AUTO_PAUSED}, both reachable from {@code WorkflowExecutionEngine#checkRunCompletion}'s
 * single @Transactional method that just marked a run FAILED. {@code InProcessSignalBus.publish} is
 * synchronous, and {@code NotificationSignalSink} runs with {@code FailureMode.PROPAGATE} -- so publishing
 * inline would let a notification-delivery hiccup (e.g. the deliberately-unguarded {@code
 * notification_group_config} lookup in {@code NotificationDeliveryService.deliver}) roll back the run's
 * FAILED status along with it. The run really did fail regardless of whether anyone could be told about
 * it, so:
 *
 * <ul>
 *   <li>the publish is deferred to {@code afterCommit} (mirroring {@code
 *       DispositionPolicyCache#invalidate}) when a transaction is active, so it only fires once the
 *       FAILED write is durable and can no longer be rolled back by it; and</li>
 *   <li>belt-and-braces, the publish itself is wrapped in a catch, so even a caller with no active
 *       transaction (a plain unit test, or a future caller outside a @Transactional boundary) can't have
 *       a delivery failure propagate back into it.</li>
 * </ul>
 */
final class SafeSignalPublish {

    private SafeSignalPublish() {
    }

    static void afterCommit(SignalBus signalBus, Signal signal, Logger log) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishSafely(signalBus, signal, log);
                }
            });
        } else {
            publishSafely(signalBus, signal, log);
        }
    }

    private static void publishSafely(SignalBus signalBus, Signal signal, Logger log) {
        try {
            signalBus.publish(signal);
        } catch (RuntimeException e) {
            log.warn("Failed to publish signal '{}': {}", signal.type(), e.getMessage(), e);
        }
    }
}
