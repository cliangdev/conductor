package com.conductor.integration;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One daily value. {@code dimensionKey} is empty for a total and names the breakdown member otherwise
 * (a referring domain, a search query, a campaign).
 */
public record ChannelMetricPoint(String metric, String dimensionKey, LocalDate day, BigDecimal value) {

    public ChannelMetricPoint {
        if (dimensionKey == null) dimensionKey = "";
    }

    public static ChannelMetricPoint total(String metric, LocalDate day, double value) {
        return new ChannelMetricPoint(metric, "", day, BigDecimal.valueOf(value));
    }

    public static ChannelMetricPoint of(String metric, String dimensionKey, LocalDate day, double value) {
        return new ChannelMetricPoint(metric, dimensionKey, day, BigDecimal.valueOf(value));
    }
}
