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
        @JsonProperty("minRankMove") Integer minRankMove,
        @JsonProperty("sourceOrder") Boolean sourceOrder) {

    /** Nine-argument form: rows are ranked by {@code valueField}, highest first. */
    public DimensionSpec(String key, String label, String idField, String valueField, Integer topN,
                         Integer baselineN, Double minAbsolute, Double minRelative, Integer minRankMove) {
        this(key, label, idField, valueField, topN, baselineN, minAbsolute, minRelative, minRankMove, null);
    }

    /**
     * True when the connector already ranked the rows (e.g. byHookType orders by verdict tier, then verdict
     * metric, which one numeric field cannot express); the aggregator then keeps the source order instead of
     * re-sorting on {@code valueField}.
     */
    public boolean keepsSourceOrder() {
        return Boolean.TRUE.equals(sourceOrder);
    }
}
