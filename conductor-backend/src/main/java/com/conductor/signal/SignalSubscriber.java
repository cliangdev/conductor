package com.conductor.signal;

/**
 * A consumer registered on the {@link SignalBus}. Replaces the four hardcoded
 * {@code @Lazy @Autowired} fields on {@code NotificationDispatcher} with a bean-discovered list,
 * so adding a new consumer is a matter of registering a new {@code SignalSubscriber} bean rather
 * than editing the dispatcher.
 */
public interface SignalSubscriber {

    /** A short, stable, human-readable name for logging (e.g. in depth-guard or ordering warnings). */
    String name();

    /**
     * Whether this subscriber wants {@code signalType}. Implementations must use exact string
     * equality, never prefix matching -- in particular {@link SignalTypes#GITHUB_PULL_REQUEST} and
     * {@link SignalTypes#GITHUB_PULL_REQUEST_MERGED} must never both match the same
     * {@code startsWith}/prefix check. A subscriber that wants glob-style matching across many
     * types should use {@link SignalGlob#matches(String, String)} explicitly, which is itself
     * segment-bounded and does not treat one of that pair as a prefix of the other.
     */
    boolean interestedIn(String signalType);

    /** Handle the signal. Only called when {@link #interestedIn(String)} returned true. */
    void onSignal(Signal signal);

    /** Position in the global dispatch order; see {@link SignalDispatchOrder}. Lower runs first. */
    int order();

    /**
     * How {@link InProcessSignalBus} should treat an exception from {@link #onSignal(Signal)}.
     * Defaults to {@link FailureMode#SWALLOW} (log and continue), matching the majority of
     * today's dispatcher consumers; override to {@link FailureMode#PROPAGATE} only for a
     * subscriber that must behave like today's unguarded {@code sendNotification} call.
     */
    default FailureMode failureMode() {
        return FailureMode.SWALLOW;
    }
}
