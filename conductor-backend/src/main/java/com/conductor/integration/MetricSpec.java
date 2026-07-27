package com.conductor.integration;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One tracked metric in a {@link DigestSpec}, describing both how to compute it from the series
 * ({@code agg} over {@code field}, or {@code weightField}-weighted, or a {@code numerator}/
 * {@code denominator} ratio) and when a period-over-period move is material enough to narrate
 * ({@code minAbsolute}/{@code minRelative}/{@code zThreshold} — the later change-detector's
 * thresholds, not evaluated here).
 */
public record MetricSpec(
        @JsonProperty("key") String key,
        @JsonProperty("label") String label,
        @JsonProperty("unit") String unit,
        @JsonProperty("agg") Aggregation agg,
        @JsonProperty("field") String field,
        @JsonProperty("weightField") String weightField,
        @JsonProperty("numerator") String numerator,
        @JsonProperty("denominator") String denominator,
        @JsonProperty("direction") Direction direction,
        @JsonProperty("minAbsolute") Double minAbsolute,
        @JsonProperty("minRelative") Double minRelative,
        @JsonProperty("zThreshold") Double zThreshold) {

    public MetricSpec {
        if (direction == null) direction = Direction.NEUTRAL;
        if (minAbsolute == null) minAbsolute = 0.0;
        if (minRelative == null) minRelative = 0.15;
        if (zThreshold == null) zThreshold = 2.0;

        // Fail at load time, not silently at digest time. Each aggregation reads different fields, and
        // MetricsAggregator coerces a missing one to 0.0 -- so a RATIO declared without a
        // numerator/denominator would emit a flat 0 every period forever, which the novelty gate then
        // correctly judges "not material", making a broken spec look exactly like a quiet metric. Same
        // reasoning as Connector#getToolSpec dropping a WINDOW ingest a connector can't honour: a
        // silently wrong digest is worse than no digest.
        if (agg == Aggregation.RATIO && (isBlank(numerator) || isBlank(denominator))) {
            throw new IllegalArgumentException(
                    "MetricSpec '" + key + "': agg RATIO requires both numerator and denominator");
        }
        if (agg == Aggregation.WEIGHTED_MEAN && (isBlank(field) || isBlank(weightField))) {
            throw new IllegalArgumentException(
                    "MetricSpec '" + key + "': agg WEIGHTED_MEAN requires both field and weightField");
        }
        if ((agg == Aggregation.SUM || agg == Aggregation.MEAN || agg == Aggregation.LAST) && isBlank(field)) {
            throw new IllegalArgumentException(
                    "MetricSpec '" + key + "': agg " + agg + " requires field");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
