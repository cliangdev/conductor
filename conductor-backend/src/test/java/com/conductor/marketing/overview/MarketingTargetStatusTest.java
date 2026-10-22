package com.conductor.marketing.overview;

import com.conductor.marketing.overview.MarketingOverviewQueryService.TargetStatus;
import com.conductor.marketing.overview.MarketingOverviewQueryService.TargetView;
import com.conductor.marketing.overview.MetricCatalog.Aggregation;
import com.conductor.marketing.overview.MetricCatalog.Good;
import com.conductor.marketing.overview.MetricCatalog.Metric;
import com.conductor.marketing.overview.MetricCatalog.Unit;
import org.junit.jupiter.api.Test;

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

    private static TargetStatus status(Metric metric, Double value, TargetView target, Long sample, int days) {
        return MarketingOverviewQueryService.targetStatus(metric, value, target, sample, days);
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
    void aCountTargetIsProratedFromItsWindowToTheRequestedOne() {
        TargetView per28 = new TargetView(280, "UP", true, "Set in x", "28d", null);
        assertThat(status(count(Good.UP), 70.0, per28, null, 7).status()).isEqualTo("ON_TRACK");
        assertThat(status(count(Good.UP), 63.0, per28, null, 7).status()).isEqualTo("WATCH");
        assertThat(status(count(Good.UP), 60.0, per28, null, 7).status()).isEqualTo("OFF_TRACK");
        // no stated window means 28d; a 7d-stated target is not prorated down to 7
        assertThat(status(count(Good.UP), 70.0, new TargetView(280, "UP"), null, 7).status()).isEqualTo("ON_TRACK");
        assertThat(status(count(Good.UP), 60.0, new TargetView(60, "UP", true, "x", "7d", null), null, 7).status())
                .isEqualTo("ON_TRACK");
        // 90 days scales up
        assertThat(status(count(Good.UP), 800.0, per28, null, 90).status()).isEqualTo("OFF_TRACK"); // needs 900
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
