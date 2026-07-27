package com.conductor.knowledge.signal;

import com.conductor.knowledge.KnowledgeEventTap;
import com.conductor.notification.NotificationEvent;
import com.conductor.notification.signal.NotificationSignalMapper;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.signal.SignalSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Replaces the {@code knowledgeEventTap.onConductorEvent} call in today's {@code
 * NotificationDispatcher.dispatch} with a {@link SignalSubscriber} at {@link
 * SignalDispatchOrder#KNOWLEDGE} (last in dispatch order). Translates the {@link Signal} back into a
 * {@link NotificationEvent} and calls the existing, UNMODIFIED {@link KnowledgeEventTap#onConductorEvent}.
 */
@Component
public class KnowledgeSignalSink implements SignalSubscriber {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSignalSink.class);

    private final KnowledgeEventTap knowledgeEventTap;
    private final NotificationSignalMapper mapper;

    public KnowledgeSignalSink(KnowledgeEventTap knowledgeEventTap, NotificationSignalMapper mapper) {
        this.knowledgeEventTap = knowledgeEventTap;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "knowledge-ingestion";
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
            knowledgeEventTap.onConductorEvent(event);
        } catch (Exception e) {
            log.warn("Knowledge ingestion tap failed for event {}: {}", event.getEventType(), e.getMessage());
        }
    }

    @Override
    public int order() {
        return SignalDispatchOrder.KNOWLEDGE;
    }
}
