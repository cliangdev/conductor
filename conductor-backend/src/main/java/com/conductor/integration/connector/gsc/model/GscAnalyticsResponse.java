package com.conductor.integration.connector.gsc.model;

import java.util.List;

/** Response from POST .../searchAnalytics/query. */
public record GscAnalyticsResponse(List<GscAnalyticsRow> rows) {
    public List<GscAnalyticsRow> rowsOrEmpty() {
        return rows != null ? rows : List.of();
    }
}
