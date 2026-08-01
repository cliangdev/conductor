package com.conductor.integration.ingest.digest;

import com.conductor.integration.DigestSpec;
import com.conductor.integration.DimensionSpec;
import com.conductor.integration.MetricSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides, period over period, whether a {@link MetricsSnapshot} is worth narrating — pure, no Spring,
 * safe to {@code new} directly in a unit test.
 *
 * <p>Compares against a rolling EWMA baseline (see {@link MetricStat}), not the prior period alone: a
 * single-period diff carries no variance signal, so it can't tell a big-but-normal move on a volatile
 * series from a real one on a stable series. Update rule per period, per metric (α = {@link #ALPHA} ≈
 * 6-period memory):
 * <pre>
 * diff = value - ewma
 * ewma' = ewma + α·diff
 * ewmVar' = (1-α)·(ewmVar + α·diff²)
 * n' = n + 1
 * </pre>
 * This update runs on EVERY period for EVERY metric, material or not — a quiet week still teaches the
 * detector what quiet looks like, which is what makes the statistical gate meaningful at all.
 *
 * <p>A metric is material only if it clears all three gates: absolute
 * ({@code abs(value-last) >= minAbsolute}, kills small-base nonsense like 2→4), relative
 * ({@code abs(value-last)/max(abs(last),1) >= minRelative}, kills large-base jitter like 4210→4290),
 * and statistical ({@code abs(value-ewma)/sqrt(ewmVar) >= zThreshold}, kills large-but-normal moves on
 * a volatile series) — the statistical gate is skipped (treated as passing) below {@link #MIN_HISTORY}
 * periods of history or while {@code ewmVar} is still exactly zero, since neither case has a variance
 * signal to judge against; {@link MetricChange#lowConfidence()} flags the former.
 *
 * <p><b>The digest-level novelty gate is the core of this design:</b> if no metric and no dimension
 * mover is material, the period is not material overall — the (later) caller records
 * {@code material=false, status=SKIPPED}, still persists the updated baseline, still advances the
 * cursor, and dispatches no narrator. Without this, a stable weekly feed files ~52 near-identical
 * "clicks were N" pages a year. The one escape valve: once {@code quietPeriods} reaches
 * {@code DigestSpec#maxQuietPeriods()}, this detector forces exactly one emission
 * ({@code reason = "steady_state"}) and resets the counter — otherwise a genuinely flat metric goes
 * silent forever and a reader can't tell "stable" from "the feed is broken".
 */
public class MetricsChangeDetector {

    public static final double ALPHA = 0.3;
    public static final int MIN_HISTORY = 4;

    public ChangeDetectionResult detect(MetricsSnapshot current, MetricsBaseline previous, DigestSpec spec,
                                        String periodKey, int quietPeriodsSoFar) {
        Map<String, MetricStat> updatedStats = new LinkedHashMap<>();
        List<MetricChange> changes = new ArrayList<>();
        boolean anyMaterial = false;

        for (MetricSpec m : spec.metrics()) {
            double value = current.metricValues().getOrDefault(m.key(), 0.0);
            MetricStat prev = previous != null ? previous.metrics().get(m.key()) : null;

            double last = prev != null ? prev.last() : value;
            double ewma = prev != null ? prev.ewma() : value;
            double ewmVar = prev != null ? prev.ewmVar() : 0.0;
            int n = prev != null ? prev.n() : 0;

            boolean lowConfidence = n < MIN_HISTORY;
            boolean material = isMaterial(m, value, last, ewma, ewmVar, n);
            if (material) anyMaterial = true;

            double diff = value - ewma;
            double newEwma = ewma + ALPHA * diff;
            double newEwmVar = (1 - ALPHA) * (ewmVar + ALPHA * diff * diff);
            int newN = n + 1;

            updatedStats.put(m.key(), new MetricStat(value, newEwma, newEwmVar, newN));
            changes.add(new MetricChange(m.key(), value, last, value - last, material, lowConfidence,
                    newEwma, newEwmVar));
        }

        Map<String, List<DimensionMover>> movers = new LinkedHashMap<>();
        Map<String, List<DimensionRow>> updatedDimensions = new LinkedHashMap<>();
        for (DimensionSpec d : spec.dimensions()) {
            List<DimensionRow> currentRows = current.dimensionRows().getOrDefault(d.key(), List.of());
            List<DimensionRow> baselineRows = previous != null
                    ? previous.dimensions().getOrDefault(d.key(), List.of())
                    : List.of();

            List<DimensionMover> dMovers = detectMovers(d, currentRows, baselineRows);
            if (!dMovers.isEmpty()) anyMaterial = true;
            movers.put(d.key(), dMovers);

            // Persist only baselineN members -- this, not MetricsAggregator, is what keeps
            // connector_feed.last_stats bounded regardless of how wide the source snapshot's own
            // top-N lists are.
            int baselineN = d.baselineN() != null ? d.baselineN() : currentRows.size();
            updatedDimensions.put(d.key(), currentRows.stream().limit(baselineN).toList());
        }

        // The escape valve checks the streak ACCUMULATED BEFORE this period, not the newly-incremented
        // count -- otherwise a run of exactly maxQuietPeriods consecutive quiet periods would force an
        // emission on the last of those periods instead of the one after, off-by-one.
        boolean forceEmit = false;
        String reason = null;
        int maxQuietPeriods = spec.maxQuietPeriods();
        int newQuietPeriods;
        if (anyMaterial) {
            newQuietPeriods = 0;
        } else if (quietPeriodsSoFar >= maxQuietPeriods) {
            forceEmit = true;
            reason = "steady_state";
            newQuietPeriods = 0;
        } else {
            newQuietPeriods = quietPeriodsSoFar + 1;
        }

        MetricsBaseline updatedBaseline = new MetricsBaseline(periodKey, updatedStats, updatedDimensions);
        return new ChangeDetectionResult(anyMaterial || forceEmit, reason, newQuietPeriods, updatedBaseline,
                changes, movers);
    }

    private boolean isMaterial(MetricSpec m, double value, double last, double ewma, double ewmVar, int n) {
        double absDelta = Math.abs(value - last);
        double relDelta = absDelta / Math.max(Math.abs(last), 1.0);
        boolean absGate = absDelta >= m.minAbsolute();
        boolean relGate = relDelta >= m.minRelative();

        boolean statGate = true; // skipped (treated as passing) below MIN_HISTORY or with no variance signal yet
        if (n >= MIN_HISTORY && ewmVar > 0) {
            double z = Math.abs(value - ewma) / Math.sqrt(ewmVar);
            statGate = z >= m.zThreshold();
        }
        return absGate && relGate && statGate;
    }

    /**
     * Matches the current top-{@code topN} against the baseline's persisted rows by
     * {@link DimensionSpec#idField()}. {@code EXITED} is restricted to baseline rows that were
     * THEMSELVES within the previous top-N (rank {@code <= topN}) — a row that was never in the
     * spotlight dropping further down isn't news.
     */
    private List<DimensionMover> detectMovers(DimensionSpec d, List<DimensionRow> currentAll,
                                              List<DimensionRow> baselineAll) {
        int topN = d.topN() != null ? d.topN() : currentAll.size();
        int minRankMove = d.minRankMove() != null ? d.minRankMove() : Integer.MAX_VALUE;
        double minAbs = d.minAbsolute() != null ? d.minAbsolute() : 0.0;
        double minRel = d.minRelative() != null ? d.minRelative() : 0.0;

        List<DimensionRow> currentTop = currentAll.stream().limit(topN).toList();

        Map<String, Integer> baselineRank = new LinkedHashMap<>();
        Map<String, Double> baselineValue = new LinkedHashMap<>();
        for (int i = 0; i < baselineAll.size(); i++) {
            DimensionRow row = baselineAll.get(i);
            baselineRank.put(row.id(), i + 1);
            baselineValue.put(row.id(), row.value());
        }

        List<DimensionMover> movers = new ArrayList<>();
        Set<String> currentTopIds = new LinkedHashSet<>();

        for (int i = 0; i < currentTop.size(); i++) {
            DimensionRow row = currentTop.get(i);
            int currentRank = i + 1;
            currentTopIds.add(row.id());

            Integer prevRank = baselineRank.get(row.id());
            Double prevValue = baselineValue.get(row.id());

            if (prevRank == null) {
                movers.add(new DimensionMover(row.id(), MoverKind.ENTERED, null, currentRank, null, row.value()));
                continue;
            }

            int rankMove = Math.abs(prevRank - currentRank);
            if (rankMove >= minRankMove) {
                movers.add(new DimensionMover(row.id(), MoverKind.RANK_MOVED, prevRank, currentRank,
                        prevValue, row.value()));
                continue;
            }

            double absDelta = Math.abs(row.value() - prevValue);
            double relDelta = absDelta / Math.max(Math.abs(prevValue), 1.0);
            if (absDelta >= minAbs && relDelta >= minRel) {
                MoverKind kind = row.value() > prevValue ? MoverKind.ROSE : MoverKind.FELL;
                movers.add(new DimensionMover(row.id(), kind, prevRank, currentRank, prevValue, row.value()));
            }
        }

        for (Map.Entry<String, Integer> entry : baselineRank.entrySet()) {
            if (entry.getValue() <= topN && !currentTopIds.contains(entry.getKey())) {
                movers.add(new DimensionMover(entry.getKey(), MoverKind.EXITED, entry.getValue(), null,
                        baselineValue.get(entry.getKey()), null));
            }
        }

        return movers;
    }
}
