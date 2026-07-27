package com.conductor.notification;

import com.conductor.notification.signal.NotificationSignalMapper;
import com.conductor.signal.SignalBus;
import org.springframework.stereotype.Service;

/**
 * Translates a {@link NotificationEvent} into a {@link com.conductor.signal.Signal} and publishes it on
 * {@link SignalBus}. This class used to BE the event fan-out (four hardcoded, ordered consumers); that
 * fan-out now lives as {@code SignalSubscriber} beans registered on the bus -- see {@code
 * com.conductor.notification.signal.NotificationSignalSink}, {@code
 * com.conductor.workflow.signal.WorkflowAutomationSignalSubscriber}, {@code
 * com.conductor.workflow.signal.LifecycleSignalSubscriber}, and {@code
 * com.conductor.knowledge.signal.KnowledgeSignalSink}.
 *
 * @deprecated kept only as the translator its remaining callers (e.g. {@code WorkItemService}, {@code
 * ReviewService}) still invoke; a later commit moves those callers onto {@link SignalBus} directly, at
 * which point this class is deleted. Publish onto {@link SignalBus} instead of adding new callers here.
 */
@Deprecated
@Service
public class NotificationDispatcher {

    private final SignalBus signalBus;
    private final NotificationSignalMapper mapper;

    public NotificationDispatcher(SignalBus signalBus, NotificationSignalMapper mapper) {
        this.signalBus = signalBus;
        this.mapper = mapper;
    }

    public void dispatch(NotificationEvent event) {
        signalBus.publish(mapper.toSignal(event));
    }
}
