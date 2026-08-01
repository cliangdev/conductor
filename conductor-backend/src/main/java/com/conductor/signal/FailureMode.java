package com.conductor.signal;

/**
 * How {@link InProcessSignalBus} handles an exception thrown out of a single subscriber's
 * {@link SignalSubscriber#onSignal(Signal)}. Today's {@code NotificationDispatcher.dispatch}
 * has two different failure behaviors baked in -- {@code sendNotification} runs unguarded and an
 * exception there aborts the whole dispatch, while the other four consumers are each wrapped in
 * their own try/catch and log-and-continue. This enum makes that distinction an explicit,
 * per-subscriber choice instead of an accident of which call happened to be wrapped.
 */
public enum FailureMode {

    /**
     * Log the failure at warn and continue to the next subscriber in order. This is the default,
     * matching the four log-and-continue consumers in today's dispatcher.
     */
    SWALLOW,

    /**
     * Rethrow the exception immediately out of {@code publish()}. Subscribers later in the order
     * do NOT run. This matches today's unguarded {@code sendNotification} call, which is first in
     * dispatch order and whose exception prevents the other consumers from running.
     */
    PROPAGATE
}
