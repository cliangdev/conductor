package com.conductor.marketing.overview;

import com.conductor.integration.MetricChannel;
import com.conductor.marketing.overview.MarketingOverviewQueryService.TargetStatus;
import com.conductor.marketing.overview.MarketingOverviewQueryService.TargetView;
import com.conductor.marketing.overview.MetricCatalog.Aggregation;
import com.conductor.marketing.overview.MetricCatalog.Good;
import com.conductor.marketing.overview.MetricCatalog.Metric;
import com.conductor.marketing.overview.MetricCatalog.Unit;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The target status bands, without a database: the rule is a pure function of value, target and sample. */
class MarketingTargetStatusTest {

    private static Metric count(Good good) {
        return new Metric("m", "M", Unit.COUNT, Aggregation.SUM, good, false, false, false, null, null, null);
    }

    private static Metric rate() {
        return new Metric("r", "R", Unit.PERCENT, Aggregation.DERIVED, Good.UP, false, false, false, "a", "b", null);
    }

    /** A rate here is divided over its sample, so the sample is also the interval's n. */
    private static TargetStatus status(Metric metric, Double value, TargetView target, Long sample, int days) {
        return MarketingOverviewQueryService.targetStatus(metric, value, target, sample, sample, days);
    }

    @Test
    void noTargetIsNoTarget() {
        assertThat(status(count(Good.UP), 5.0, null, null, 28).status()).isEqualTo("NO_TARGET");
    }

    @Test
    void metUpTargetIsOnTrackAndAMissIsBandedByTenPercent() {
        TargetView target = new TargetView(100, "UP");
        assertThat(status(count(Good.UP), 100.0, target, null, 28)).isEqualTo(new TargetStatus("ON_TRACK", 0.0));
        assertThat(status(count(Good.UP), 130.0, target, null, 28).status()).isEqualTo("ON_TRACK");
        assertThat(status(count(Good.UP), 95.0, target, null, 28).status()).isEqualTo("WATCH");
        // the edge: exactly 10% short is still WATCH, a hair more is OFF_TRACK
        assertThat(status(count(Good.UP), 90.0, target, null, 28).status()).isEqualTo("WATCH");
        assertThat(status(count(Good.UP), 89.9, target, null, 28).status()).isEqualTo("OFF_TRACK");
        assertThat(status(count(Good.UP), 50.0, target, null, 28).missPct()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void downTargetIsMetAtOrBelowAndMissedAbove() {
        TargetView target = new TargetView(10, "DOWN");
        assertThat(status(count(Good.DOWN), 10.0, target, null, 28).status()).isEqualTo("ON_TRACK");
        assertThat(status(count(Good.DOWN), 6.0, target, null, 28).status()).isEqualTo("ON_TRACK");
        assertThat(status(count(Good.DOWN), 11.0, target, null, 28).status()).isEqualTo("WATCH");
        assertThat(status(count(Good.DOWN), 11.0, target, null, 28).missPct()).isCloseTo(0.1, within(1e-9));
        assertThat(status(count(Good.DOWN), 11.5, target, null, 28).status()).isEqualTo("OFF_TRACK");
    }

    @Test
    void aTargetsOwnWatchBandOverridesTheDefault() {
        TargetView wide = new TargetView(100, "UP", true, "Set in x", null, 25.0);
        assertThat(status(count(Good.UP), 80.0, wide, null, 28).status()).isEqualTo("WATCH");
        TargetView narrow = new TargetView(100, "UP", true, "Set in x", null, 2.0);
        assertThat(status(count(Good.UP), 95.0, narrow, null, 28).status()).isEqualTo("OFF_TRACK");
    }

    @Test
    void noValueIsTooEarly() {
        assertThat(status(count(Good.UP), null, new TargetView(100, "UP"), null, 28))
                .isEqualTo(new TargetStatus("TOO_EARLY", null));
    }

    @Test
    void aRateUnderTwentyObservationsIsTooEarlyAtNineteenButNotAtTwenty() {
        TargetView target = new TargetView(0.25, "UP");
        assertThat(status(rate(), 0.5, target, 19L, 28).status()).isEqualTo("TOO_EARLY");
        assertThat(status(rate(), 0.5, target, 20L, 28).status()).isEqualTo("ON_TRACK");
        assertThat(status(rate(), 0.5, target, null, 28).status()).isEqualTo("ON_TRACK"); // sample unknown
        // a plain count on a small sample is still judged
        assertThat(status(count(Good.UP), 500.0, new TargetView(100, "UP"), 3L, 28).status()).isEqualTo("ON_TRACK");
    }

    @Test
    void aRateMissThatChanceCouldMakeIsWatchNotOffTrack() {
        // Rexipe's day-1 return: 4 of 32 against 20%; at a true 20%, 4 or fewer happens about one time in five
        TargetStatus dayOne = status(rate(), 4.0 / 32, new TargetView(0.20, "UP"), 32L, 28);
        assertThat(dayOne.status()).isEqualTo("WATCH");
        assertThat(dayOne.closeToTarget()).isFalse();
        assertThat(dayOne.missPct()).isCloseTo(0.375, within(1e-9));
        // the same rate on 400 installs is a real miss
        assertThat(status(rate(), 50.0 / 400, new TargetView(0.20, "UP"), 400L, 28).status()).isEqualTo("OFF_TRACK");
        // a DOWN rate too: 6 of 20 cancelled against a 10% ceiling is real, 3 of 20 could be chance
        Metric down = new Metric("c", "C", Unit.PERCENT, Aggregation.DERIVED, Good.DOWN, false, false, false, "a", "b",
                null);
        assertThat(status(down, 6.0 / 20, new TargetView(0.10, "DOWN"), 20L, 28).status()).isEqualTo("OFF_TRACK");
        assertThat(status(down, 3.0 / 20, new TargetView(0.10, "DOWN"), 20L, 28).status()).isEqualTo("WATCH");
    }

    @Test
    void theIntervalIsDrawnOnWhatTheRateWasDividedOverNotOnTheSample() {
        // 50 paid of 1000 trials against 10%: a sample of 50 reported for the numerator must not widen the interval
        TargetView target = new TargetView(0.10, "UP");
        assertThat(MarketingOverviewQueryService.targetStatus(rate(), 0.05, target, 50L, 1000L, 28).status())
                .isEqualTo("OFF_TRACK");
        assertThat(MarketingOverviewQueryService.targetStatus(rate(), 0.05, target, 50L, 50L, 28).status())
                .isEqualTo("WATCH");
    }

    @Test
    void onlyAMetOrHalfBandMissIsCloseToTargetSoAnOpenAlertDoesNotResolveAtTheBandsEdge() {
        TargetView target = new TargetView(100, "UP");
        assertThat(status(count(Good.UP), 100.0, target, null, 28).closeToTarget()).isTrue();
        assertThat(status(count(Good.UP), 95.0, target, null, 28).closeToTarget()).isTrue();
        TargetStatus edge = status(count(Good.UP), 92.0, target, null, 28);
        assertThat(edge.status()).isEqualTo("WATCH");
        assertThat(edge.closeToTarget()).isFalse();
        assertThat(status(count(Good.UP), 80.0, target, null, 28).closeToTarget()).isFalse();
        assertThat(status(count(Good.UP), null, target, null, 28).closeToTarget()).isFalse();
    }

    @Test
    void aRateOverFewerThanTwentyIsTooEarlyWhateverSampleTheFeedReported() {
        // a feed sample of 50 must not let a ratio over 3 through to an interval that spans nearly everything
        TargetView target = new TargetView(0.20, "UP");
        assertThat(MarketingOverviewQueryService.targetStatus(rate(), 0.10, target, 50L, 3L, 28).status())
                .isEqualTo("TOO_EARLY");
    }

    @Test
    void aMetricLastReportedMoreThanAWeekBeforeItsChannelHasStoppedArriving() {
        LocalDate through = LocalDate.of(2026, 11, 4);
        assertThat(MarketingOverviewQueryService.stoppedArriving(LocalDate.of(2026, 10, 18), through)).isTrue();
        assertThat(MarketingOverviewQueryService.stoppedArriving(through.minusDays(7), through)).isFalse();
        assertThat(MarketingOverviewQueryService.stoppedArriving(null, through)).isFalse();
        assertThat(MarketingOverviewQueryService.stoppedArriving(LocalDate.of(2026, 10, 18), null)).isFalse();
    }

    @Test
    void aRowFromBeforeTheHistoryCountsForAMetricOnlyWhereTheOverviewWouldReadIt() {
        Metric level = new Metric("rank", "Rank", Unit.COUNT, Aggregation.LEVEL, Good.UP, false, false, false, null,
                null, null);
        Metric total = count(Good.UP);
        MetricCatalog.Channel totalsOnly = new MetricCatalog.Channel(MetricChannel.PRODUCT, "In-app", List.of(),
                List.of(level, total), List.of(), 3);
        // a channel that loads only '' rows never reads a level's per-dimension rows (keyword ranks)
        assertThat(MarketingOverviewQueryService.readsDimension(totalsOnly, level, "aso:recipes")).isFalse();
        assertThat(MarketingOverviewQueryService.readsDimension(totalsOnly, level, "")).isTrue();
        Metric perPlatform = new Metric("followers", "Followers", Unit.COUNT, Aggregation.LEVEL, Good.UP, false,
                false, true, null, null, null);
        MetricCatalog.Channel withDimensions = new MetricCatalog.Channel(MetricChannel.SOCIAL, "Social", List.of(),
                List.of(perPlatform, total), List.of(), 3);
        assertThat(MarketingOverviewQueryService.readsDimension(withDimensions, perPlatform, "instagram")).isTrue();
        // a total flow reads only the '' total, even where dimension rows are loaded
        assertThat(MarketingOverviewQueryService.readsDimension(withDimensions, total, "instagram")).isFalse();
    }

    @Test
    void aRateMissOutsideItsIntervalStaysOffTrack() {
        // Rexipe's paywall reach: 20 of 28 against 85%, just outside the 95% interval (upper bound about 84.7%)
        assertThat(status(rate(), 20.0 / 28, new TargetView(0.85, "UP"), 28L, 28).status()).isEqualTo("OFF_TRACK");
        // with no sample reported the interval cannot be drawn, so the band alone decides
        assertThat(status(rate(), 4.0 / 32, new TargetView(0.20, "UP"), null, 28).status()).isEqualTo("OFF_TRACK");
        // a count is never judged by the interval
        assertThat(status(count(Good.UP), 28.0, new TargetView(60, "UP"), 28L, 28).status()).isEqualTo("OFF_TRACK");
    }

    @Test
    void aCountTargetIsProratedFromItsWindowToTheRequestedOne() {
        TargetView per28 = new TargetView(280, "UP", true, "Set in x", "28d", null);
        assertThat(status(count(Good.UP), 70.0, per28, null, 7).status()).isEqualTo("ON_TRACK");
        assertThat(status(count(Good.UP), 63.0, per28, null, 7).status()).isEqualTo("WATCH");
        assertThat(status(count(Good.UP), 60.0, per28, null, 7).status()).isEqualTo("OFF_TRACK");
        // a 7d-stated target is not prorated down to 7
        assertThat(status(count(Good.UP), 60.0, new TargetView(60, "UP", true, "x", "7d", null), null, 7).status())
                .isEqualTo("ON_TRACK");
        // 90 days scales up
        assertThat(status(count(Good.UP), 800.0, per28, null, 90).status()).isEqualTo("OFF_TRACK"); // needs 900
    }

    @Test
    void aTargetWithNoWindowIsComparedAsStatedWhateverTheRequestedWindow() {
        TargetView owned = new TargetView(100, "UP");
        assertThat(status(count(Good.UP), 100.0, owned, null, 7).status()).isEqualTo("ON_TRACK");
        assertThat(status(count(Good.UP), 100.0, owned, null, 90).status()).isEqualTo("ON_TRACK");
        assertThat(status(count(Good.UP), 25.0, owned, null, 7).status()).isEqualTo("OFF_TRACK");
        assertThat(MarketingOverviewQueryService.effectiveTarget(count(Good.UP), owned, 90)).isEqualTo(100.0);
    }

    @Test
    void theEffectiveTargetIsTheProratedOneForAWindowedTarget() {
        TargetView per28 = new TargetView(200, "UP", true, "x", "28d", null);
        assertThat(MarketingOverviewQueryService.effectiveTarget(count(Good.UP), per28, 7)).isCloseTo(50.0, within(1e-9));
    }

    @Test
    void ratesAndLevelsAreNotProrated() {
        TargetView target = new TargetView(0.25, "UP");
        assertThat(status(rate(), 0.26, target, 100L, 7).status()).isEqualTo("ON_TRACK");
        Metric level = new Metric("l", "L", Unit.COUNT, Aggregation.LEVEL, Good.UP, false, false, false, null, null,
                null);
        assertThat(status(level, 1000.0, new TargetView(1000, "UP"), null, 7).status()).isEqualTo("ON_TRACK");
    }

    @Test
    void aZeroTargetThatIsMissedIsOffTrackWithNoPercent() {
        TargetStatus s = status(count(Good.DOWN), 3.0, new TargetView(0, "DOWN"), null, 28);
        assertThat(s.status()).isEqualTo("OFF_TRACK");
        assertThat(s.missPct()).isNull();
    }
}
