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
    }
}
