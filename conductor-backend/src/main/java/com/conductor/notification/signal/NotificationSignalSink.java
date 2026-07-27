package com.conductor.notification.signal;

import com.conductor.notification.NotificationDeliveryService;
import com.conductor.signal.FailureMode;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalDispatchOrder;
import com.conductor.signal.SignalSubscriber;
import org.springframework.stereotype.Component;

/**
 * The former unguarded {@code NotificationDispatcher.dispatch} call to {@code
 * NotificationDeliveryService.deliver}, now a {@link SignalSubscriber} at {@link
 * SignalDispatchOrder#NOTIFICATION} (first in dispatch order) with {@link FailureMode#PROPAGATE}. This
 * reproduces today's asymmetry exactly: delivery runs first, and unlike the swallowing subscribers that
 * follow it, its failure escapes {@code InProcessSignalBus.publish} entirely and prevents every later
 * subscriber from running for this signal. See {@link NotificationDeliveryService}'s javadoc for why
 * that escape is load-bearing -- it's what marks a GitHub {@code webhook_event} FAILED and gets it
 * retried -- rather than an oversight to "fix" here.
 */
@Component
public class NotificationSignalSink implements SignalSubscriber {

    private final NotificationDeliveryService deliveryService;
    private final NotificationSignalMapper mapper;

    public NotificationSignalSink(NotificationDeliveryService deliveryService, NotificationSignalMapper mapper) {
        this.deliveryService = deliveryService;
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "notification-delivery";
    }

    /**
     * Only types that map to an {@link com.conductor.notification.EventType}. This is NOT the same
     * "claim exactly my types" narrowing the other subscribers do -- delivery still handles every event
     * type it CAN express, filtering further by {@code ChannelGroup} inside {@code deliver}. The gate
     * is about expressibility, and it is load-bearing rather than defensive:
     * {@link NotificationSignalMapper#toNotificationEvent} throws for an unmapped type, and because this
     * subscriber is {@link FailureMode#PROPAGATE}, that throw would abort the ENTIRE fan-out. A
     * connector-defined signal such as {@code github.pull_request_merged} — which has no notification
     * counterpart by design — would therefore have silently prevented knowledge ingestion and work-item
     * completion from ever running, and surfaced only as a webhook retried to DEAD.
     */
    @Override
    public boolean interestedIn(String signalType) {
        return mapper.isDeliverable(signalType);
    }

    @Override
    public void onSignal(Signal signal) {
        deliveryService.deliver(mapper.toNotificationEvent(signal));
    }

    @Override
    public int order() {
        return SignalDispatchOrder.NOTIFICATION;
    }

    @Override
    public FailureMode failureMode() {
        return FailureMode.PROPAGATE;
    }
}
