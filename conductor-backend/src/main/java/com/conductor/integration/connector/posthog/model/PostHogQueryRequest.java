package com.conductor.integration.connector.posthog.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/** POST body for /api/projects/{id}/query/ supporting both TrendsQuery and HogQLQuery kinds. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostHogQueryRequest(PostHogInnerQuery query) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PostHogInnerQuery(
            String kind,
            String query,
            List<Map<String, Object>> series,
            Map<String, Object> dateRange) {}
}
