package com.conductor.integration.connector.gsc.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Request body for POST .../searchAnalytics/query. Null fields are omitted from serialization. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GscAnalyticsRequest(
        String startDate,
        String endDate,
        List<String> dimensions,
        String type,
        int rowLimit,
        List<GscDimensionFilterGroup> dimensionFilterGroups) {

    /** Convenience constructor for queries without a type filter or dimension filters. */
    public GscAnalyticsRequest(String startDate, String endDate, List<String> dimensions, int rowLimit) {
        this(startDate, endDate, dimensions, null, rowLimit, null);
    }
}
