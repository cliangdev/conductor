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
 * @param contractVersion   the source's schema/contract version for this response; a change from the one in the
 *                          feed cursor schedules a history backfill; null when the source has none
 * @param dimensionStates   the current state (bid, status, ...) of dimension members, upserted by key; null/empty
 *                          leaves what is stored
 * @param skippedCampaignIds campaigns whose dimension members (keywords, ads) the source could not read this pull
 *                          (e.g. a 4xx on their report); the store keeps those campaigns' stored dimension rows
 *                          instead of pruning them as missing, and prunes everything else as usual
 */
public record ChannelMetricsPull(List<ChannelMetricPoint> points, LocalDate provisionalFrom,
                                 List<MetricDefinition> definitions, Integer trailingDays,
                                 Set<String> snapshotMetrics, List<DimensionState> dimensionStates,
                                 Integer contractVersion, Set<String> skippedCampaignIds) {

    public ChannelMetricsPull {
        if (points == null) points = List.of();
        if (snapshotMetrics == null) snapshotMetrics = Set.of();
        if (dimensionStates == null) dimensionStates = List.of();
        if (skippedCampaignIds == null) skippedCampaignIds = Set.of();
    }

    public ChannelMetricsPull(List<ChannelMetricPoint> points, LocalDate provisionalFrom,
                              List<MetricDefinition> definitions, Integer trailingDays, Set<String> snapshotMetrics,
                              List<DimensionState> dimensionStates, Integer contractVersion) {
        this(points, provisionalFrom, definitions, trailingDays, snapshotMetrics, dimensionStates, contractVersion,
                Set.of());
    }

    public ChannelMetricsPull(List<ChannelMetricPoint> points, LocalDate provisionalFrom,
                              List<MetricDefinition> definitions, Integer trailingDays, Set<String> snapshotMetrics,
                              List<DimensionState> dimensionStates) {
        this(points, provisionalFrom, definitions, trailingDays, snapshotMetrics, dimensionStates, null);
    }

    public ChannelMetricsPull(List<ChannelMetricPoint> points, LocalDate provisionalFrom,
                              List<MetricDefinition> definitions, Integer trailingDays, Set<String> snapshotMetrics) {
        this(points, provisionalFrom, definitions, trailingDays, snapshotMetrics, List.of(), null);
    }

    public static ChannelMetricsPull of(List<ChannelMetricPoint> points) {
        return new ChannelMetricsPull(points, null, null, null, Set.of());
    }
}
