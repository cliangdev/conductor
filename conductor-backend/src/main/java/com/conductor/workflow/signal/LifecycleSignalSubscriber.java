package com.conductor.workflow.signal;

import com.conductor.notification.NotificationEvent;
import com.conductor.notification.signal.NotificationSignalMapper;
import com.conductor.service.LifecycleTriggerDispatcher;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.signal.SignalSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Replaces the {@code lifecycleTriggerDispatcher.onConductorEvent} call in today's {@code
 * NotificationDispatcher.dispatch} with a {@link SignalSubscriber} at {@link
 * SignalDispatchOrder#LIFECYCLE}. Translates the {@link Signal} back into a {@link NotificationEvent}
 * and calls the existing, UNMODIFIED {@link LifecycleTriggerDispatcher#onConductorEvent}.
 *
 * <h2>Deliberately NOT {@code @Transactional}</h2>
 * Exactly like {@link LifecycleTriggerDispatcher} itself (see its javadoc): this subscriber -- and the
 * bus it runs on -- must join the caller's own transaction rather than open a boundary of its own.
 * {@code publish()} is invoked from inside {@code WorkItemService}'s own transaction (e.g. {@code
 * patchWorkItem}/{@code completeFromPullRequest}), and a {@code @Transactional(REQUIRED)} here would let
 * a cascade exception be caught by Spring's transaction interceptor and mark that *shared* transaction
 * rollback-only -- silently failing the user's original status change at commit, even though {@code
 * InProcessSignalBus}'s {@code SWALLOW} handling looks like it isolated the failure. Without a boundary
 * here, that isolation is real, matching today's behaviour exactly. Pinned by {@code
 * LifecycleTriggerDispatcherTest#isDeliberatelyNotTransactional} (asserts this class too).
 */
@Component
public class LifecycleSignalSubscriber implements SignalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(LifecycleSignalSubscriber.class);

    private final LifecycleTriggerDispatcher lifecycleTriggerDispatcher;
    private final NotificationSignalMapper mapper;

    public LifecycleSignalSubscriber(LifecycleTriggerDispatcher lifecycleTriggerDispatcher,
                                      NotificationSignalMapper mapper) {
        this.lifecycleTriggerDispatcher = lifecycleTriggerDispatcher;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "lifecycle-trigger";
    }

    @Override
    public boolean interestedIn(String signalType) {
        // A6 will narrow this: today onConductorEvent is called unconditionally for every event type
        // and does its own internal EventType check.
        return true;
    }

    @Override
    public void onSignal(Signal signal) {
        NotificationEvent event = mapper.toNotificationEvent(signal);
        try {
            lifecycleTriggerDispatcher.onConductorEvent(event);
        } catch (Exception e) {
            log.warn("Lifecycle trigger evaluation failed for event {}: {}", event.getEventType(), e.getMessage());
        }
    }

    @Override
    public int order() {
        return SignalDispatchOrder.LIFECYCLE;
    }
}
