package com.conductor.integration.ingest.digest;

import com.conductor.integration.Aggregation;
import com.conductor.integration.Direction;
import com.conductor.integration.DigestSpec;
import com.conductor.integration.DimensionSpec;
import com.conductor.integration.IngestMode;
import com.conductor.integration.IngestSpec;
import com.conductor.integration.IngestWindow;
import com.conductor.integration.MetricSpec;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MetricsAggregatorTest {

    private final MetricsAggregator aggregator = new MetricsAggregator();

    private static Map<String, Object> row(String date, double clicks, double impressions, double position) {
        return Map.of("date", date, "clicks", clicks, "impressions", impressions, "position", position);
    }

    private static IngestWindow windowOf(LocalDate start, LocalDate end) {
        return new IngestWindow(
                start.atStartOfDay(ZoneOffset.UTC).toInstant(),
                end.atStartOfDay(ZoneOffset.UTC).toInstant());
    }

    private static IngestSpec specWith(MetricSpec... metrics) {
        DigestSpec digest = new DigestSpec("trend", "date", List.of(metrics), List.of(), "marketing/x.md", null);
        return new IngestSpec("weekly", "label", "desc", IngestMode.SNAPSHOT, "search_analytics",
                "metrics.digest.{connector}.{ingest}", null, null, null, null, digest);
    }

    @Test
    void ratioIsSumOfNumeratorOverSumOfDenominator_notMeanOfRatios() {
        // day1 ratio = 1/1000 = 0.001; day2 ratio = 100/100 = 1.0 -- mean-of-ratios would be ~0.5005.
        Map<String, Object> payload = Map.of("trend", List.of(
                row("2026-06-01", 1, 1000, 10.0),
                row("2026-06-02", 100, 100, 10.0)));
        MetricSpec ctr = new MetricSpec("ctr", "CTR", "%", Aggregation.RATIO, null, null,
                "clicks", "impressions", Direction.UP_IS_GOOD, null, null, null);
        IngestSpec spec = specWith(ctr);
        IngestWindow window = windowOf(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3));

        MetricsSnapshot snapshot = aggregator.aggregate(payload, spec, window);

        assertThat(snapshot.metricValues().get("ctr")).isCloseTo(101.0 / 1100.0, within(1e-9));
        assertThat(snapshot.metricValues().get("ctr")).isNotCloseTo(0.5005, within(1e-6));
    }

    @Test
    void weightedMeanWeightsPositionByImpressions_notNaiveMean() {
        // Naive mean of position = (10+20)/2 = 15; impression-weighted = (10*10+20*1000)/1010 ~= 19.9.
        Map<String, Object> payload = Map.of("trend", List.of(
                row("2026-06-01", 5, 10, 10.0),
                row("2026-06-02", 5, 1000, 20.0)));
        MetricSpec position = new MetricSpec("position", "Position", null, Aggregation.WEIGHTED_MEAN,
                "position", "impressions", null, null, Direction.DOWN_IS_GOOD, null, null, null);
        IngestSpec spec = specWith(position);
        IngestWindow window = windowOf(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3));

        MetricsSnapshot snapshot = aggregator.aggregate(payload, spec, window);

        double expected = (10.0 * 10 + 20.0 * 1000) / 1010.0;
        assertThat(snapshot.metricValues().get("position")).isCloseTo(expected, within(1e-9));
        assertThat(snapshot.metricValues().get("position")).isNotCloseTo(15.0, within(1e-6));
    }

    @Test
    void sumAndMeanAggregateAcrossFilteredRows() {
        Map<String, Object> payload = Map.of("trend", List.of(
                row("2026-06-01", 10, 100, 5.0),
                row("2026-06-02", 20, 200, 7.0)));
        MetricSpec clicks = new MetricSpec("clicks", "Clicks", null, Aggregation.SUM, "clicks", null,
                null, null, Direction.UP_IS_GOOD, null, null, null);
        MetricSpec impressions = new MetricSpec("impressions", "Impressions", null, Aggregation.MEAN,
                "impressions", null, null, null, Direction.UP_IS_GOOD, null, null, null);
        IngestSpec spec = specWith(clicks, impressions);
        IngestWindow window = windowOf(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3));

        MetricsSnapshot snapshot = aggregator.aggregate(payload, spec, window);

        assertThat(snapshot.metricValues().get("clicks")).isEqualTo(30.0);
        assertThat(snapshot.metricValues().get("impressions")).isEqualTo(150.0);
    }

    @Test
    void lastReturnsNewestRowsFieldByDate() {
        Map<String, Object> payload = Map.of("trend", List.of(
                row("2026-06-02", 20, 200, 9.0),
                row("2026-06-01", 10, 100, 5.0)));
        MetricSpec position = new MetricSpec("position", "Position", null, Aggregation.LAST, "position",
                null, null, null, Direction.DOWN_IS_GOOD, null, null, null);
        IngestSpec spec = specWith(position);
        IngestWindow window = windowOf(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 3));

        MetricsSnapshot snapshot = aggregator.aggregate(payload, spec, window);

        assertThat(snapshot.metricValues().get("position")).isEqualTo(9.0);
    }

    @Test
    void windowSlicingIsHalfOpenAcrossAYearBoundary() {
        // Window is the ISO week Mon 2025-12-29 through (exclusive) Mon 2026-01-05.
        Map<String, Object> payload = Map.of("trend", List.of(
                row("2025-12-28", 1, 10, 1.0),  // before window -- excluded
                row("2025-12-29", 2, 10, 1.0),  // window start -- included
                row("2026-01-01", 3, 10, 1.0),  // crosses the year boundary -- included
                row("2026-01-04", 4, 10, 1.0),  // last day in window -- included
                row("2026-01-05", 5, 10, 1.0))); // window end (exclusive) -- excluded
        MetricSpec clicks = new MetricSpec("clicks", "Clicks", null, Aggregation.SUM, "clicks", null,
                null, null, Direction.UP_IS_GOOD, null, null, null);
        IngestSpec spec = specWith(clicks);
        IngestWindow window = windowOf(LocalDate.of(2025, 12, 29), LocalDate.of(2026, 1, 5));

        MetricsSnapshot snapshot = aggregator.aggregate(payload, spec, window);

        assertThat(snapshot.metricValues().get("clicks")).isEqualTo(2.0 + 3.0 + 4.0);
    }

    @Test
    void dimensionRowsAreSortedDescendingByValueAsIs_notSlicedByWindow() {
        Map<String, Object> payload = Map.of(
                "trend", List.of(),
                "topQueries", List.of(
                        Map.of("query", "a", "clicks", 5),
                        Map.of("query", "b", "clicks", 50),
                        Map.of("query", "c", "clicks", 20)));
        DimensionSpec dim = new DimensionSpec("topQueries", "Top queries", "query", "clicks", 8, 25,
                25.0, 0.30, 5);
        DigestSpec digest = new DigestSpec("trend", "date", List.of(), List.of(dim), "marketing/x.md", null);
        IngestSpec spec = new IngestSpec("weekly", "label", "desc", IngestMode.SNAPSHOT, "search_analytics",
                "metrics.digest.{connector}.{ingest}", null, null, null, null, digest);

        MetricsSnapshot snapshot = aggregator.aggregate(payload, spec, null);

        assertThat(snapshot.dimensionRows().get("topQueries"))
                .extracting(DimensionRow::id)
                .containsExactly("b", "c", "a");
    }
}
