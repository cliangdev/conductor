package com.conductor.notification;

import java.util.List;

/**
 * What {@link NotificationRollupService#rollup} did with a batch of events, as seen by the caller.
 *
 * @param fallbackToPlain the events that definitely did NOT reach Discord and may safely be sent the
 *                        ordinary way (one message each, in this order). Empty when everything was edited
 *                        into or posted as the rolling message, or deliberately dropped (the channel was
 *                        deleted, disabled or no longer wants them; Discord is rate-limiting; or the outcome
 *                        of a post is unknown and sending again could duplicate it). Never contains an event
 *                        that might already be on Discord.
 */
public record RollupResult(List<NotificationMessage> fallbackToPlain) {

    static RollupResult handled() {
        return new RollupResult(List.of());
    }

    static RollupResult fallback(List<NotificationMessage> events) {
        return new RollupResult(List.copyOf(events));
    }
}
