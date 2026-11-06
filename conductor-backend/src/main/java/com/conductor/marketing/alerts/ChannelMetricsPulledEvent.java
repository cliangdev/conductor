package com.conductor.marketing.alerts;

/**
 * A channel-metrics pull for one connection has finished and its days are stored: the moment to ask whether
 * anything now needs a person's attention. Published by {@code ChannelMetricsFeedPuller} only for a pull that
 * succeeded and reached today; {@link MarketingAlertEvaluator} is the listener.
 *
 * @param channel the {@code MetricChannel} name (ADS, STORE, ...) the connection feeds
 */
public record ChannelMetricsPulledEvent(String projectId, String channel, String connectionId) {
}
