package com.conductor.workflow.signal;

import com.conductor.service.LifecycleTriggerDispatcher;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.signal.SignalSubscriber;
import com.conductor.signal.SignalTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A {@link SignalSubscriber} at {@link SignalDispatchOrder#LIFECYCLE} that calls {@link
 * LifecycleTriggerDispatcher#onConductorEvent(Signal)} directly.
 *
 * <h2>Deliberately NOT {@code @Transactional}</h2>
 * Exactly like {@link LifecycleTriggerDispatcher} itself (see its javadoc): this subscriber -- and the
 * bus it runs on -- must join the caller's own transaction rather than open a boundary of its own.
 * {@code publish()} is invoked from inside {@code WorkItemService}'s own transaction (e.g. {@code
 * patchWorkItem}/{@code completeFromPullRequest}), and a {@code @Transactional(REQUIRED)} here would let
 * a cascade exception be caught by Spring's transaction interceptor and mark that *shared* transaction
 * rollback-only -- silently failing the user's original status change at commit, even though {@code
 * InProcessSignalBus}'s {@code SWALLOW} handling looks like it isolated the failure. Without a boundary
 * here, that isolation is real. Pinned by {@code
 * LifecycleTriggerDispatcherTest#isDeliberatelyNotTransactional} (asserts this class too).
 */
@Component
public class LifecycleSignalSubscriber implements SignalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(LifecycleSignalSubscriber.class);

    private final LifecycleTriggerDispatcher lifecycleTriggerDispatcher;

    public LifecycleSignalSubscriber(LifecycleTriggerDispatcher lifecycleTriggerDispatcher) {
        this.lifecycleTriggerDispatcher = lifecycleTriggerDispatcher;
    }

    @Override
    public String name() {
        return "lifecycle-trigger";
    }

    @Override
    public boolean interestedIn(String signalType) {
        return SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED.equals(signalType);
    }

    @Override
    public void onSignal(Signal signal) {
        try {
            lifecycleTriggerDispatcher.onConductorEvent(signal);
        } catch (Exception e) {
            log.warn("Lifecycle trigger evaluation failed for signal {}: {}", signal.type(), e.getMessage());
        }
    }

    @Override
    public int order() {
        return SignalDispatchOrder.LIFECYCLE;
    }
}
