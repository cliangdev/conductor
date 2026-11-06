package com.conductor.marketing.promises;

import com.conductor.marketing.promises.WilsonInterval.Range;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.offset;

/** The 95% Wilson score interval behind every promise-ledger rate. */
class PromiseLedgerWilsonTest {

    private static final double TOLERANCE = 0.0005;

    @Test
    void oneOfSixReadsAboutThreeToFiftySixPercent() {
        Range range = WilsonInterval.of(1, 6);

        assertThat(range.low()).isCloseTo(0.0300, offset(TOLERANCE));
        assertThat(range.high()).isCloseTo(0.5635, offset(TOLERANCE));
    }

    @Test
    void fourOfSeventeenReadsAboutTenToFortySevenPercent() {
        Range range = WilsonInterval.of(4, 17);

        assertThat(range.low()).isCloseTo(0.0956, offset(TOLERANCE));
        assertThat(range.high()).isCloseTo(0.4726, offset(TOLERANCE));
    }

    @Test
    void theRangeNarrowsAsTheSampleGrows() {
        Range small = WilsonInterval.of(3, 20);
        Range large = WilsonInterval.of(300, 2000);

        assertThat(large.high() - large.low()).isLessThan(small.high() - small.low());
        assertThat(large.low()).isCloseTo(0.1350, offset(0.001));
        assertThat(large.high()).isCloseTo(0.1663, offset(0.001));
    }

    @Test
    void noSuccessesStillHasAnUpperBoundAndALowerBoundOfZero() {
        Range range = WilsonInterval.of(0, 10);

        assertThat(range.low()).isEqualTo(0.0);
        assertThat(range.high()).isCloseTo(0.2775, offset(TOLERANCE));
    }

    @Test
    void everySuccessStillHasALowerBoundBelowOneAndAnUpperBoundOfOne() {
        Range range = WilsonInterval.of(10, 10);

        assertThat(range.low()).isCloseTo(0.7225, offset(TOLERANCE));
        assertThat(range.high()).isEqualTo(1.0);
    }

    @Test
    void theRangeNeverLeavesZeroToOne() {
        for (int n = 1; n <= 60; n++) {
            for (int k = 0; k <= n; k++) {
                Range range = WilsonInterval.of(k, n);
                assertThat(range.low()).as("low of %d/%d", k, n).isBetween(0.0, 1.0);
                assertThat(range.high()).as("high of %d/%d", k, n).isBetween(0.0, 1.0);
                assertThat(range.low()).as("low <= rate of %d/%d", k, n).isLessThanOrEqualTo((double) k / n + 1e-12);
                assertThat(range.high()).as("high >= rate of %d/%d", k, n).isGreaterThanOrEqualTo((double) k / n - 1e-12);
            }
        }
    }

    @Test
    void refusesAnEmptySampleAndMoreSuccessesThanTheSample() {
        assertThatThrownBy(() -> WilsonInterval.of(0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WilsonInterval.of(7, 6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WilsonInterval.of(-1, 6)).isInstanceOf(IllegalArgumentException.class);
    }
}
