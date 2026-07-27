package com.conductor.integration.ingest.digest;

import java.util.List;
import java.util.Map;

/**
 * The output of {@link MetricsAggregator#aggregate}: one rolled-up value per declared
 * {@code MetricSpec} for the requested window, and the raw (unsliced — see the aggregator's javadoc)
 * dimension rows per declared {@code DimensionSpec}, sorted descending by value.
 */
public record MetricsSnapshot(
        Map<String, Double> metricValues,
        Map<String, List<DimensionRow>> dimensionRows) {
}
