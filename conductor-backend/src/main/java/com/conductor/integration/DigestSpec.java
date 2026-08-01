package com.conductor.integration;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Declares an {@link IngestSpec} as a metric feed: {@code seriesPath} is the dotted path into the
 * pulled snapshot/window holding the row series (e.g. {@code "trend"}), {@code dateField} the per-row
 * date key, {@code metrics}/{@code dimensions} what to track, and {@code pagePath} the Knowledge Center
 * page the (later) narrator writes to. {@code maxQuietPeriods} caps how many consecutive
 * no-material-change periods a feed tolerates before the (later) scheduler backs it off.
 */
public record DigestSpec(
        @JsonProperty("seriesPath") String seriesPath,
        @JsonProperty("dateField") String dateField,
        @JsonProperty("metrics") List<MetricSpec> metrics,
        @JsonProperty("dimensions") List<DimensionSpec> dimensions,
        @JsonProperty("pagePath") String pagePath,
        @JsonProperty("maxQuietPeriods") Integer maxQuietPeriods) {

    public DigestSpec {
        if (metrics == null) metrics = List.of();
        if (dimensions == null) dimensions = List.of();
        if (maxQuietPeriods == null) maxQuietPeriods = 13;
    }
}
