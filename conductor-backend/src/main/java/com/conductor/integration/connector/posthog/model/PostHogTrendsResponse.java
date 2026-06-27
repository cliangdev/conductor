package com.conductor.integration.connector.posthog.model;

import java.util.List;

/** Response from a TrendsQuery (kind=TrendsQuery). */
public record PostHogTrendsResponse(List<PostHogTrendsResult> results) {
    public record PostHogTrendsResult(List<Number> data, List<String> labels) {}
}
