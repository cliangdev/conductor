package com.conductor.integration.ingest.digest;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One metric's rolling EWMA baseline (see {@link MetricsChangeDetector}): {@code last} is the raw
 * value from the most recent period (what materiality gates diff against), {@code ewma}/{@code ewmVar}
 * are the exponentially-weighted mean/variance a "normal" move is judged against, and {@code n} is how
 * many periods have updated this stat (gates the statistical materiality gate below
 * {@code MIN_HISTORY}).
 */
public record MetricStat(
        @JsonProperty("last") double last,
        @JsonProperty("ewma") double ewma,
        @JsonProperty("ewmVar") double ewmVar,
        @JsonProperty("n") int n) {
}
