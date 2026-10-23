package com.conductor.integration;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * What {@link ChannelMetricsSource#pull} returns: the points plus anything the source knows about them.
 *
 * @param provisionalFrom   days on or after this are stored provisional, overriding the generic trailing
 *                          window; null keeps the generic rule
 * @param definitions       the metrics the source declares (replacing the stored set); null leaves them untouched
 * @param trailingDays      how many days back the next pull should re-read; carried in the feed cursor; null
 *                          leaves the previous hint
 * @param snapshotMetrics   metrics whose points are final readings whatever their day (stocks)
 * @param dimensionStates   the current state (bid, status, ...) of dimension members, upserted by key; null/empty
 *                          leaves what is stored
 */
public record ChannelMetricsPull(List<ChannelMetricPoint> points, LocalDate provisionalFrom,
                                 List<MetricDefinition> definitions, Integer trailingDays,
                                 Set<String> snapshotMetrics, List<DimensionState> dimensionStates) {

    public ChannelMetricsPull {
        if (points == null) points = List.of();
        if (snapshotMetrics == null) snapshotMetrics = Set.of();
        if (dimensionStates == null) dimensionStates = List.of();
    }

    public ChannelMetricsPull(List<ChannelMetricPoint> points, LocalDate provisionalFrom,
                              List<MetricDefinition> definitions, Integer trailingDays, Set<String> snapshotMetrics) {
        this(points, provisionalFrom, definitions, trailingDays, snapshotMetrics, List.of());
    }

    public static ChannelMetricsPull of(List<ChannelMetricPoint> points) {
        return new ChannelMetricsPull(points, null, null, null, Set.of());
    }
}
