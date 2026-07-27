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

    @Override
    public boolean interestedIn(String signalType) {
        // A6 will narrow this: today every consumer (including delivery) is called unconditionally
        // for every event type and does its own internal filtering -- here that's
        // ChannelGroup.forEventType(...) inside NotificationDeliveryService.deliver.
        return true;
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
