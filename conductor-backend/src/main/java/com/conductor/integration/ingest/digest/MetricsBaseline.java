package com.conductor.integration.ingest.digest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * The rolling change-detection baseline {@link MetricsChangeDetector} reads and writes every period —
 * what {@code connector_feed.last_stats} persists between pulls. {@code dimensions} keeps only
 * {@code DimensionSpec#baselineN()} rows per dimension (see {@link MetricsChangeDetector}), which is
 * what keeps this bounded regardless of how wide the source snapshot's own top-N lists are.
 *
 * <p>{@link #toJson()} is a plain Jackson round-trip (no Spring {@code ObjectMapper} bean) — used both
 * by whoever persists this into {@code connector_feed.last_stats} and by tests asserting the
 * serialized size stays bounded.
 */
public record MetricsBaseline(
        String periodKey,
        Map<String, MetricStat> metrics,
        Map<String, List<DimensionRow>> dimensions) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize MetricsBaseline", e);
        }
    }
}
