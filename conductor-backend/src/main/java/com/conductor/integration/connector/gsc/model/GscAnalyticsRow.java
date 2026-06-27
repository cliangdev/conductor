package com.conductor.integration.connector.gsc.model;

import java.util.List;

/** One row in a searchAnalytics/query response. */
public record GscAnalyticsRow(
        List<String> keys,
        double clicks,
        double impressions,
        double ctr,
        double position) {

    public String firstKey() {
        return keys != null && !keys.isEmpty() ? keys.get(0) : "";
    }
}
