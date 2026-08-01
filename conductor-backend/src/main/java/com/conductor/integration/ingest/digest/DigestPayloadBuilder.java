package com.conductor.integration.ingest.digest;

import com.conductor.integration.IngestSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the (later) narrator's ONLY view of a digest period, from a {@link ChangeDetectionResult} —
 * never from the raw {@link MetricsSnapshot}/{@link MetricsBaseline}. This is what makes "the narrator
 * physically cannot dump raw numbers" true rather than aspirational: the daily series, the full top-50
 * dimension lists, and the raw snapshot never travel through this builder at all, because it never
 * receives them — only already-computed {@link MetricChange}s and {@link DimensionMover}s.
 *
 * <p>Non-material metrics are included with {@code "material": false} — the narrator needs "position
 * was flat" as context it must not lead with, not silence. Dimension movers are exactly what
 * {@link MetricsChangeDetector} found material (bounded by each spec's {@code topN}); nothing wider is
 * ever added here.
 */
public class DigestPayloadBuilder {

    public Map<String, Object> build(IngestSpec spec, String periodKey, ChangeDetectionResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();

        Map<String, Object> period = new LinkedHashMap<>();
        period.put("key", periodKey);
        period.put("comparedTo", "rolling EWMA baseline");
        payload.put("period", period);

        payload.put("moversComparedTo", "dimension movers compare the source snapshot's own lookback "
                + "(e.g. 28d top-N) — not necessarily the same window as the scalar metrics above");
        payload.put("lowConfidence", result.metricChanges().stream().anyMatch(MetricChange::lowConfidence));
        payload.put("reason", result.reason());
        payload.put("suggestedDomain", spec.suggestedDomain());
        payload.put("pagePath", spec.digest() != null ? spec.digest().pagePath() : null);

        List<Map<String, Object>> metrics = new ArrayList<>();
        for (MetricChange c : result.metricChanges()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", c.key());
            m.put("value", c.value());
            m.put("previous", c.previousLast());
            m.put("delta", c.delta());
            m.put("material", c.material());
            m.put("lowConfidence", c.lowConfidence());
            metrics.add(m);
        }
        payload.put("metrics", metrics);

        Map<String, Object> dimensions = new LinkedHashMap<>();
        result.dimensionMovers().forEach((key, movers) -> {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (DimensionMover mv : movers) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", mv.id());
                row.put("kind", mv.kind().name());
                row.put("previousRank", mv.previousRank());
                row.put("currentRank", mv.currentRank());
                row.put("previousValue", mv.previousValue());
                row.put("currentValue", mv.currentValue());
                rows.add(row);
            }
            dimensions.put(key, rows);
        });
        payload.put("dimensions", dimensions);

        return payload;
    }
}
