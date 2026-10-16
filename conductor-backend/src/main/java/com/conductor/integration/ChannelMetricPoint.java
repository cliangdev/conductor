package com.conductor.integration;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One daily value. {@code dimensionKey} is empty for a total and names the breakdown member otherwise
 * (a referring domain, a search query, a campaign). {@code sampleSize} is how many users or events the value
 * rests on, for sources that say (null otherwise).
 */
public record ChannelMetricPoint(String metric, String dimensionKey, LocalDate day, BigDecimal value,
                                 Long sampleSize) {

    public ChannelMetricPoint {
        if (dimensionKey == null) dimensionKey = "";
    }

    public ChannelMetricPoint(String metric, String dimensionKey, LocalDate day, BigDecimal value) {
        this(metric, dimensionKey, day, value, null);
    }

    public static ChannelMetricPoint total(String metric, LocalDate day, double value) {
        return new ChannelMetricPoint(metric, "", day, BigDecimal.valueOf(value));
    }

    public static ChannelMetricPoint of(String metric, String dimensionKey, LocalDate day, double value) {
        return new ChannelMetricPoint(metric, dimensionKey, day, BigDecimal.valueOf(value));
    }
}
