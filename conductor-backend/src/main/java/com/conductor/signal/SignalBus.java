package com.conductor.signal;

/**
 * The in-process event bus that {@code NotificationDispatcher.dispatch} is being decomposed into.
 * See {@link InProcessSignalBus} for the implementation and its constraints.
 */
public interface SignalBus {

    void publish(Signal signal);
}
