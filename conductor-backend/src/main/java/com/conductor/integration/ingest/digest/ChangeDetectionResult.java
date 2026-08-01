package com.conductor.integration.ingest.digest;

import java.util.List;
import java.util.Map;

/**
 * The full output of one {@link MetricsChangeDetector#detect} call: whether the period is material
 * overall (the digest-level novelty gate — see the detector's class javadoc), the updated baseline to
 * persist regardless of materiality, and the per-metric/per-dimension detail the (later)
 * {@code DigestPayloadBuilder} narrows down to a narrator-safe payload.
 *
 * @param material      true iff ANY metric or dimension mover cleared its gates, OR the
 *                       {@code maxQuietPeriods} escape valve fired ({@code reason == "steady_state"})
 * @param reason         {@code null} normally; {@code "steady_state"} when the escape valve forced an
 *                       emission despite nothing being individually material
 * @param quietPeriods   the updated consecutive-non-material-period counter to persist on
 *                       {@code connector_feed.quiet_periods} — reset to 0 on any material period
 *                       (including an escape-valve emission)
 * @param updatedBaseline the new {@link MetricsBaseline} to persist — updated EVERY period, including
 *                       non-material ones (a quiet week still teaches the detector what quiet looks
 *                       like)
 */
public record ChangeDetectionResult(
        boolean material,
        String reason,
        int quietPeriods,
        MetricsBaseline updatedBaseline,
        List<MetricChange> metricChanges,
        Map<String, List<DimensionMover>> dimensionMovers) {
}
