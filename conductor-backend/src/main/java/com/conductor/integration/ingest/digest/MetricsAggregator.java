package com.conductor.integration.ingest.digest;

import com.conductor.integration.Aggregation;
import com.conductor.integration.DigestSpec;
import com.conductor.integration.DimensionSpec;
import com.conductor.integration.IngestSpec;
import com.conductor.integration.IngestWindow;
import com.conductor.integration.MetricSpec;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure aggregation over one already-fetched snapshot/window into one value per {@link MetricSpec} and
 * one sorted row list per {@link DimensionSpec} — no Spring, no I/O, safe to {@code new} directly in a
 * unit test.
 *
 * <p>Reads {@link DigestSpec#seriesPath()} (a dotted path, e.g. {@code "trend"}), filters rows into
 * the half-open {@code [window.start(), window.end())} by {@link DigestSpec#dateField()} (an
 * ISO-8601 local date string per row, e.g. {@code "2026-06-01"}), then folds each metric by its
 * {@link Aggregation}. Metric/dimension field lookups are flat map keys only — no JSONPath, no
 * scripting; a connector needing more than that implements {@code IngestConnector} and pre-aggregates
 * before handing data to the (later) digest pipeline.
 *
 * <p>Dimensions are NOT sliced by the window — {@link DigestSpec#dimensions()} come from the snapshot
 * as-is (e.g. GSC's top queries/pages are already a fixed 28-day lookback the source API computed),
 * sorted descending by {@link DimensionSpec#valueField()}. That means a metric (SUM/MEAN/etc.) and a
 * dimension mover in the same digest can legitimately be comparing different lookback windows — the
 * (later) {@code DigestPayloadBuilder} surfaces this as {@code moversComparedTo} so the narrator can't
 * overclaim "this week" for a 28-day figure.
 */
public class MetricsAggregator {

    public MetricsSnapshot aggregate(Map<String, Object> payload, IngestSpec spec, IngestWindow window) {
        DigestSpec digest = spec.digest();
        List<Map<String, Object>> series = extractSeries(payload, digest.seriesPath());
        List<Map<String, Object>> sliced = filterByWindow(series, digest.dateField(), window);

        Map<String, Double> metricValues = new LinkedHashMap<>();
        for (MetricSpec m : digest.metrics()) {
            metricValues.put(m.key(), computeMetric(m, sliced, digest.dateField()));
        }

        Map<String, List<DimensionRow>> dimensionRows = new LinkedHashMap<>();
        for (DimensionSpec d : digest.dimensions()) {
            dimensionRows.put(d.key(), extractDimension(payload, d));
        }

        return new MetricsSnapshot(metricValues, dimensionRows);
    }

    private double computeMetric(MetricSpec m, List<Map<String, Object>> rows, String dateField) {
        if (rows.isEmpty()) {
            return 0.0;
        }
        return switch (m.agg()) {
            case SUM -> rows.stream().mapToDouble(r -> toDouble(r.get(m.field()))).sum();
            case MEAN -> rows.stream().mapToDouble(r -> toDouble(r.get(m.field()))).average().orElse(0.0);
            case LAST -> rows.stream()
                    .max(Comparator.comparing(r -> parseDate(r.get(dateField)),
                            Comparator.nullsFirst(Comparator.naturalOrder())))
                    .map(r -> toDouble(r.get(m.field())))
                    .orElse(0.0);
            // Impression-weighted, not a naive daily mean -- a day with 10 impressions shouldn't move
            // the average position as much as a day with 10,000.
            case WEIGHTED_MEAN -> {
                double weightedSum = rows.stream()
                        .mapToDouble(r -> toDouble(r.get(m.field())) * toDouble(r.get(m.weightField())))
                        .sum();
                double totalWeight = rows.stream().mapToDouble(r -> toDouble(r.get(m.weightField()))).sum();
                yield totalWeight == 0.0 ? 0.0 : weightedSum / totalWeight;
            }
            // Sum-of-numerator over sum-of-denominator, NOT the mean of each row's own ratio -- the
            // classic CTR bug (mean-of-ratios overweights low-impression days).
            case RATIO -> {
                double num = rows.stream().mapToDouble(r -> toDouble(r.get(m.numerator()))).sum();
                double den = rows.stream().mapToDouble(r -> toDouble(r.get(m.denominator()))).sum();
                yield den == 0.0 ? 0.0 : num / den;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private List<DimensionRow> extractDimension(Map<String, Object> payload, DimensionSpec d) {
        Object raw = payload.get(d.key());
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<DimensionRow> rows = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map)) continue;
            Map<String, Object> row = (Map<String, Object>) o;
            Object id = row.get(d.idField());
            if (id == null) continue;
            rows.add(new DimensionRow(String.valueOf(id), toDouble(row.get(d.valueField()))));
        }
        rows.sort(Comparator.comparingDouble(DimensionRow::value).reversed());
        return rows;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractSeries(Map<String, Object> payload, String seriesPath) {
        Object current = payload;
        for (String part : seriesPath.split("\\.")) {
            if (!(current instanceof Map)) {
                return List.of();
            }
            current = ((Map<String, Object>) current).get(part);
        }
        if (!(current instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map) rows.add((Map<String, Object>) o);
        }
        return rows;
    }

    private List<Map<String, Object>> filterByWindow(List<Map<String, Object>> series, String dateField,
                                                      IngestWindow window) {
        if (window == null) {
            return series;
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : series) {
            LocalDate date = parseDate(row.get(dateField));
            if (date == null) continue;
            Instant rowInstant = date.atStartOfDay(ZoneOffset.UTC).toInstant();
            if (!rowInstant.isBefore(window.start()) && rowInstant.isBefore(window.end())) {
                result.add(row);
            }
        }
        return result;
    }

    private LocalDate parseDate(Object value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(String.valueOf(value));
        } catch (Exception e) {
            return null;
        }
    }

    private double toDouble(Object o) {
        if (o == null) return 0.0;
        if (o instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
