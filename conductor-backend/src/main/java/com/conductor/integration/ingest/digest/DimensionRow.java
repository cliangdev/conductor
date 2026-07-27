package com.conductor.integration.ingest.digest;

import com.fasterxml.jackson.annotation.JsonProperty;

/** One row of a {@link MetricsAggregator}-extracted dimension breakdown (e.g. one top query), or one
 *  member of a persisted {@link MetricsBaseline} dimension list — the same shape serves both. */
public record DimensionRow(
        @JsonProperty("id") String id,
        @JsonProperty("value") double value) {
}
