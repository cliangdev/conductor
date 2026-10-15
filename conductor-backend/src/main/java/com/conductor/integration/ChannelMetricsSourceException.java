package com.conductor.integration;

/** An expected failure of a {@link ChannelMetricsSource} pull; the puller turns it into a feed health state. */
public class ChannelMetricsSourceException extends RuntimeException {

    private final ConnectorHealth health;

    public ChannelMetricsSourceException(ConnectorHealth health, String message) {
        super(message);
        this.health = health;
    }

    public static ChannelMetricsSourceException setupRequired(String message) {
        return new ChannelMetricsSourceException(ConnectorHealth.SETUP_REQUIRED, message);
    }

    public static ChannelMetricsSourceException degraded(String message) {
        return new ChannelMetricsSourceException(ConnectorHealth.DEGRADED, message);
    }

    public ConnectorHealth health() {
        return health;
    }
}
