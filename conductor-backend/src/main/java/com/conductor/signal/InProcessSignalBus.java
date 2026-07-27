package com.conductor.signal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The only {@link SignalBus} implementation. Strictly in-process, synchronous, and non-durable:
 * there is no backing table, {@code publish()} runs subscribers on the calling thread before
 * returning, and nothing here is retried or replayed. {@code webhook_event} remains the one and
 * only durable event store in this codebase -- giving {@code Signal} its own persistence would
 * create a second, competing source of truth for "did this event happen," which is exactly the
 * duplication this bus exists to avoid by centralizing in-process fan-out instead.
 *
 * <p>This class is deliberately never {@code @Transactional}. {@code publish()} is called from
 * inside callers' own transactions (e.g. {@code WorkItemService} methods), so wrapping it in a
 * transaction of its own would either nest pointlessly or, worse, let a subscriber's DB writes
 * outlive or diverge from the caller's transaction boundary. Subscribers that need transactional
 * behavior are responsible for their own boundaries.
 *
 * <h2>Lazy, memoized subscriber resolution</h2>
 * The constructor takes {@code ObjectProvider<List<SignalSubscriber>>} rather than
 * {@code List<SignalSubscriber>} directly. Constructor injection of a {@code List<T>} is resolved
 * *eagerly* by Spring, before this bean finishes constructing -- which matters because a future
 * subscriber is expected to close a real cycle: {@code WorkItemService -> SignalBus ->
 * LifecycleSubscriber -> LifecycleTriggerDispatcher -> WorkItemService}. Eager resolution of that
 * list would throw {@code BeanCurrentlyInCreationException} at context startup. Resolving via the
 * {@code ObjectProvider} on first {@code publish()} instead defers the lookup until after the
 * context has finished constructing every bean, breaking the cycle. The resolved, order-sorted
 * list is memoized after the first resolution so later publishes don't re-query the context.
 *
 * <h2>Depth guard</h2>
 * A signal handler can itself trigger another {@code publish()} on the same thread. There is
 * already a latent re-entrant path in this codebase: {@code WorkflowTriggerService.createRun}
 * dispatching zero jobs can trip {@code WorkflowFailureCircuitBreaker}, which itself publishes
 * another event -- today this terminates only because no consumer happens to handle that second
 * event type. Once real subscribers exist, an accidental cycle between them would recurse
 * unbounded on the calling thread. The guard caps re-entrant depth per thread at {@link
 * #MAX_DEPTH}; on breach it logs at error naming the offending signal type and drops the publish
 * silently rather than throwing -- throwing would turn a recursion bug in some unrelated
 * subscriber into a user-visible 500 on whatever request happened to trigger it.
 */
@Service
public class InProcessSignalBus implements SignalBus {

    private static final Logger log = LoggerFactory.getLogger(InProcessSignalBus.class);

    private static final int MAX_DEPTH = 8;

    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private final ObjectProvider<List<SignalSubscriber>> subscribersProvider;
    private final Object resolutionLock = new Object();

    private volatile List<SignalSubscriber> subscribers;

    public InProcessSignalBus(ObjectProvider<List<SignalSubscriber>> subscribersProvider) {
        this.subscribersProvider = subscribersProvider;
    }

    @Override
    public void publish(Signal signal) {
        int depth = DEPTH.get();
        if (depth >= MAX_DEPTH) {
            log.error("Signal depth guard tripped at depth {} publishing signal type '{}' -- dropping "
                    + "the publish instead of recursing further. This usually means two subscribers "
                    + "are publishing back into each other.", depth, signal.type());
            return;
        }

        DEPTH.set(depth + 1);
        try {
            for (SignalSubscriber subscriber : resolveSubscribers()) {
                if (!subscriber.interestedIn(signal.type())) {
                    continue;
                }
                try {
                    subscriber.onSignal(signal);
                } catch (RuntimeException e) {
                    if (subscriber.failureMode() == FailureMode.PROPAGATE) {
                        throw e;
                    }
                    log.warn("Signal subscriber '{}' failed for signal '{}': {}",
                            subscriber.name(), signal.type(), e.getMessage(), e);
                }
            }
        } finally {
            // Clear rather than set-back at the outermost frame: publish() runs on pooled request
            // threads, so leaving a ThreadLocal entry behind on every thread that ever published is
            // avoidable residue. Mirrors LifecycleTriggerDispatcher's IN_CASCADE.remove().
            if (depth == 0) {
                DEPTH.remove();
            } else {
                DEPTH.set(depth);
            }
        }
    }

    private List<SignalSubscriber> resolveSubscribers() {
        List<SignalSubscriber> resolved = subscribers;
        if (resolved != null) {
            return resolved;
        }
        synchronized (resolutionLock) {
            if (subscribers == null) {
                List<SignalSubscriber> sorted = new ArrayList<>(subscribersProvider.getIfAvailable(List::of));
                sorted.sort(Comparator.comparingInt(SignalSubscriber::order));
                warnOnDuplicateOrders(sorted);
                subscribers = sorted;
            }
            return subscribers;
        }
    }

    /** Same {@code order()} on two subscribers means their relative dispatch order is undefined. */
    private void warnOnDuplicateOrders(List<SignalSubscriber> sortedByOrder) {
        for (int i = 1; i < sortedByOrder.size(); i++) {
            SignalSubscriber previous = sortedByOrder.get(i - 1);
            SignalSubscriber current = sortedByOrder.get(i);
            if (previous.order() == current.order()) {
                log.warn("Signal subscribers '{}' and '{}' both declare order {} -- their relative "
                        + "dispatch order is nondeterministic across JVMs. Give them distinct orders.",
                        previous.name(), current.name(), current.order());
            }
        }
    }
}
