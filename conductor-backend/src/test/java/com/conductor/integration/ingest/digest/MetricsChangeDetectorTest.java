package com.conductor.integration.ingest.digest;

import com.conductor.integration.Aggregation;
import com.conductor.integration.Direction;
import com.conductor.integration.DigestSpec;
import com.conductor.integration.DimensionSpec;
import com.conductor.integration.MetricSpec;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class MetricsChangeDetectorTest {

    private final MetricsChangeDetector detector = new MetricsChangeDetector();

    private static MetricSpec metricSpec(String key, double minAbsolute, double minRelative, double zThreshold) {
        return new MetricSpec(key, key, null, Aggregation.SUM, key, null, null, null,
                Direction.NEUTRAL, minAbsolute, minRelative, zThreshold);
    }

    private static DigestSpec digestSpec(MetricSpec metric, Integer maxQuietPeriods) {
        return new DigestSpec("trend", "date", List.of(metric), List.of(), "x.md", maxQuietPeriods);
    }

    private static MetricsSnapshot snapshotOf(String key, double value) {
        return new MetricsSnapshot(Map.of(key, value), Map.of());
    }

    /** Runs one metric through {@code values} in sequence, threading baseline/quietPeriods through each
     *  call, and returns the LAST result. */
    private ChangeDetectionResult runSeries(DigestSpec digest, String key, double... values) {
        MetricsBaseline baseline = null;
        int quiet = 0;
        ChangeDetectionResult result = null;
        for (int i = 0; i < values.length; i++) {
            result = detector.detect(snapshotOf(key, values[i]), baseline, digest, "p" + i, quiet);
            baseline = result.updatedBaseline();
            quiet = result.quietPeriods();
        }
        return result;
    }

    @Test
    void minAbsoluteKillsSmallBaseDoublingFrom2To4() {
        DigestSpec digest = digestSpec(metricSpec("clicks", 100.0, 0.0, 2.0), null);

        ChangeDetectionResult result = runSeries(digest, "clicks", 2, 4);

        assertThat(result.metricChanges().get(0).material()).isFalse();
    }

    @Test
    void minRelativeKillsLargeBaseJitterFrom4210To4290() {
        DigestSpec digest = digestSpec(metricSpec("clicks", 1.0, 0.10, 2.0), null);

        ChangeDetectionResult result = runSeries(digest, "clicks", 4210, 4290);

        assertThat(result.metricChanges().get(0).material()).isFalse();
    }

    @Test
    void sameRelativeMoveIsMaterialOnLowVarianceButNotOnHighVarianceSeries() {
        DigestSpec digest = digestSpec(metricSpec("v", 1.0, 0.01, 2.0), null);

        // +18% jump after 4 periods of ~0.5% noise -- large z-score against a near-zero variance.
        ChangeDetectionResult lowVariance = runSeries(digest, "v", 1000, 1005, 995, 1000, 1180);
        assertThat(lowVariance.metricChanges().get(0).material()).isTrue();

        // The SAME +18% jump after 4 periods of huge (up to 30%) swings -- small z-score against a
        // large variance the detector has learned to expect.
        ChangeDetectionResult highVariance = runSeries(digest, "v", 1000, 1300, 700, 1000, 1180);
        assertThat(highVariance.metricChanges().get(0).material()).isFalse();
    }

    @Test
    void belowMinHistorySkipsStatisticalGateAndFlagsLowConfidence() {
        DigestSpec digest = digestSpec(metricSpec("v", 1.0, 0.01, 2.0), null);

        // n=2 going into the 3rd period -- below MIN_HISTORY(4), so the z-gate is skipped and
        // materiality rests on the absolute/relative gates alone.
        ChangeDetectionResult result = runSeries(digest, "v", 100, 100, 200);

        MetricChange change = result.metricChanges().get(0);
        assertThat(change.lowConfidence()).isTrue();
        assertThat(change.material()).isTrue();
    }

    @Test
    void ewmaAndEwmVarRecurrenceMatchesHandComputedValues() {
        DigestSpec digest = digestSpec(metricSpec("v", 0.0, 0.0, 2.0), null);

        ChangeDetectionResult result = runSeries(digest, "v", 1000, 1005, 995, 1000);

        MetricStat stat = result.updatedBaseline().metrics().get("v");
        // Hand-computed with alpha=0.3: see MetricsChangeDetectorTest class notes / PR description.
        assertThat(stat.ewma()).isCloseTo(999.685, within(1e-9));
        assertThat(stat.ewmVar()).isCloseTo(8.825775, within(1e-9));
        assertThat(stat.n()).isEqualTo(4);
        assertThat(stat.last()).isEqualTo(1000.0);
    }

    @Test
    void baselineUpdatesEvenWhenPeriodIsNotMaterial() {
        DigestSpec digest = digestSpec(metricSpec("v", 1000.0, 1.0, 2.0), null); // nothing clears these

        ChangeDetectionResult result = runSeries(digest, "v", 100, 110);

        assertThat(result.material()).isFalse();
        MetricStat stat = result.updatedBaseline().metrics().get("v");
        assertThat(stat.n()).isEqualTo(2);
        assertThat(stat.ewma()).isCloseTo(103.0, within(1e-9)); // 100 + 0.3*(110-100)
    }

    @Test
    void maxQuietPeriodsForcesExactlyOneEmissionThenResets() {
        DigestSpec digest = digestSpec(metricSpec("v", 1000.0, 1.0, 2.0), 3); // never individually material

        MetricsBaseline baseline = null;
        int quiet = 0;
        List<ChangeDetectionResult> results = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ChangeDetectionResult r = detector.detect(snapshotOf("v", 100 + i), baseline, digest, "p" + i, quiet);
            results.add(r);
            baseline = r.updatedBaseline();
            quiet = r.quietPeriods();
        }

        assertThat(results.get(0).material()).isFalse();
        assertThat(results.get(1).material()).isFalse();
        assertThat(results.get(2).material()).isFalse();
        assertThat(results.get(3).material()).isTrue();
        assertThat(results.get(3).reason()).isEqualTo("steady_state");
        assertThat(results.get(3).quietPeriods()).isZero();
        assertThat(results.get(4).material()).isFalse();
        assertThat(results.get(4).quietPeriods()).isEqualTo(1);
    }

    /** The "clicks were 4210 forever" guard: a genuinely flat series must never dispatch a narrator. */
    @Test
    void flatSeriesNeverMaterialAcross13PeriodsAndDispatchesZeroNarrators() {
        DigestSpec digest = digestSpec(metricSpec("clicks", 50.0, 0.15, 2.0), null); // default maxQuietPeriods=13

        MetricsBaseline baseline = null;
        int quiet = 0;
        int dispatched = 0;
        for (int i = 0; i < 13; i++) {
            // Deterministic, bounded +/-35 wobble around 4200 with a small per-step derivative (well
            // under minAbsolute=50) -- a smooth "quiet week" series, not a truly constant one.
            double value = 4200 + 35 * Math.sin(i * 0.7);
            ChangeDetectionResult r = detector.detect(snapshotOf("clicks", value), baseline, digest, "p" + i, quiet);
            assertThat(r.material()).as("period %d should not be material", i).isFalse();
            if (r.material()) dispatched++;
            baseline = r.updatedBaseline();
            quiet = r.quietPeriods();
        }

        assertThat(dispatched).isZero();
    }

    @Test
    void eachDimensionMoverKindFiresAndSubMinRankMoveDoesNotFire() {
        DimensionSpec dimSpec = new DimensionSpec("topQueries", "Top queries", "id", "value", 5, 5,
                10.0, 0.10, 3);
        DigestSpec digest = new DigestSpec("trend", "date", List.of(), List.of(dimSpec), "x.md", null);

        List<DimensionRow> baselineRows = List.of(
                new DimensionRow("a", 100), new DimensionRow("b", 90), new DimensionRow("c", 80),
                new DimensionRow("d", 70), new DimensionRow("e", 60));
        MetricsBaseline baseline = new MetricsBaseline("p0", Map.of(), Map.of("topQueries", baselineRows));

        // d: baseline rank4 -> current rank1 (rankMove=3 >= minRankMove) -> RANK_MOVED
        // f: not in baseline at all -> ENTERED
        // a: baseline rank1 -> current rank3 (rankMove=2 == minRankMove-1) and delta=8 < minAbsolute -> no mover
        // b: baseline rank2 -> current rank4 (rankMove=2 < minRankMove), delta=10, rel=0.111 -> ROSE
        // c: baseline rank3 -> current rank5 (rankMove=2 < minRankMove), delta=20, rel=0.25 -> FELL
        // e: baseline rank5 (within old topN), absent from current top5 -> EXITED
        List<DimensionRow> currentRows = List.of(
                new DimensionRow("d", 200), new DimensionRow("f", 150), new DimensionRow("a", 108),
                new DimensionRow("b", 100), new DimensionRow("c", 60));
        MetricsSnapshot current = new MetricsSnapshot(Map.of(), Map.of("topQueries", currentRows));

        ChangeDetectionResult result = detector.detect(current, baseline, digest, "p1", 0);

        Map<String, DimensionMover> byId = result.dimensionMovers().get("topQueries").stream()
                .collect(Collectors.toMap(DimensionMover::id, m -> m));

        assertThat(byId.get("d").kind()).isEqualTo(MoverKind.RANK_MOVED);
        assertThat(byId.get("f").kind()).isEqualTo(MoverKind.ENTERED);
        assertThat(byId.get("b").kind()).isEqualTo(MoverKind.ROSE);
        assertThat(byId.get("c").kind()).isEqualTo(MoverKind.FELL);
        assertThat(byId.get("e").kind()).isEqualTo(MoverKind.EXITED);
        assertThat(byId).doesNotContainKey("a");
        assertThat(result.material()).isTrue();
    }

    @Test
    void serializedBaselineStaysBoundedAcross100PeriodsOf500RowDimensions() {
        DimensionSpec dimSpec = new DimensionSpec("topQueries", "Top queries", "id", "value", 8, 25,
                25.0, 0.30, 5);
        DigestSpec digest = new DigestSpec("trend", "date", List.of(), List.of(dimSpec), "x.md", null);

        MetricsBaseline baseline = null;
        int quiet = 0;
        Random random = new Random(7);
        for (int period = 0; period < 100; period++) {
            List<DimensionRow> rows = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                rows.add(new DimensionRow("query-" + i, random.nextDouble() * 1000));
            }
            rows.sort(Comparator.comparingDouble(DimensionRow::value).reversed());
            MetricsSnapshot snapshot = new MetricsSnapshot(Map.of(), Map.of("topQueries", rows));
            ChangeDetectionResult r = detector.detect(snapshot, baseline, digest, "p" + period, quiet);
            baseline = r.updatedBaseline();
            quiet = r.quietPeriods();
        }

        String json = baseline.toJson();
        assertThat(json.getBytes(StandardCharsets.UTF_8).length).isLessThan(8 * 1024);
        assertThat(baseline.dimensions().get("topQueries")).hasSize(25);
    }
}
