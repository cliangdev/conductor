package com.conductor.integration;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One tracked breakdown in a {@link DigestSpec} (e.g. top queries, top pages) — the later
 * change-detector's top-{@code topN} vs. a {@code baselineN}-wide prior baseline, flagging entries that
 * move by at least {@code minAbsolute}/{@code minRelative} or rank-shift by {@code minRankMove}.
 */
public record DimensionSpec(
        @JsonProperty("key") String key,
        @JsonProperty("label") String label,
        @JsonProperty("idField") String idField,
        @JsonProperty("valueField") String valueField,
        @JsonProperty("topN") Integer topN,
        @JsonProperty("baselineN") Integer baselineN,
        @JsonProperty("minAbsolute") Double minAbsolute,
        @JsonProperty("minRelative") Double minRelative,
        @JsonProperty("minRankMove") Integer minRankMove) {
}
