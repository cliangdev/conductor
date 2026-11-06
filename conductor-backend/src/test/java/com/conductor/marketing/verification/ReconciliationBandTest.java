package com.conductor.marketing.verification;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** The reconciliation band (C3): 1 unit for counts under 50, 2 percent above, 2 percent for money. */
class ReconciliationBandTest {

    @Test
    void sevenAgainstTwentyThreeIsAMismatch() {
        assertThat(ReconciliationBand.matches(7, 23, false)).isFalse();
    }

    @Test
    void threeAgainstFourMatchesBecauseASmallCountMayDifferByOneUnit() {
        assertThat(ReconciliationBand.matches(3, 4, false)).isTrue();
        assertThat(ReconciliationBand.matches(4, 3, false)).isTrue();
    }

    @Test
    void threeAgainstFiveIsAMismatch() {
        assertThat(ReconciliationBand.matches(3, 5, false)).isFalse();
    }

    @Test
    void aHundredAgainstAHundredAndThreeIsAMismatchOnceCountsReachFifty() {
        assertThat(ReconciliationBand.matches(100, 103, false)).isFalse();
    }

    @Test
    void aHundredAgainstAHundredAndOneAndAHalfMatches() {
        assertThat(ReconciliationBand.matches(100, 101.5, false)).isTrue();
    }

    @Test
    void currencyIsJudgedByTwoPercentOfTheLargerFigure() {
        assertThat(ReconciliationBand.matches(162.86, 164.0, true)).isTrue(); // 0.7%
        assertThat(ReconciliationBand.matches(162.86, 170.0, true)).isFalse(); // 4.2%
    }

    @Test
    void aSmallCurrencyAmountDoesNotGetTheOneUnitAllowanceOfACount() {
        assertThat(ReconciliationBand.matches(12.0, 13.0, true)).isFalse(); // 7.7%
        assertThat(ReconciliationBand.matches(12.0, 13.0, false)).isTrue();
    }

    @Test
    void zeroAgainstZeroMatches() {
        assertThat(ReconciliationBand.matches(0, 0, false)).isTrue();
        assertThat(ReconciliationBand.matches(0, 0, true)).isTrue();
    }

    @Test
    void zeroAgainstAnythingAboveTheAllowanceIsAMismatch() {
        assertThat(ReconciliationBand.matches(0, 2, false)).isFalse();
        assertThat(ReconciliationBand.matches(0, 0.5, true)).isFalse();
    }

    @Test
    void exactlyTwoPercentIsStillInsideTheBand() {
        assertThat(ReconciliationBand.matches(98, 100, false)).isTrue();
    }

    @Test
    void anUnknownNumberNeverMatches() {
        assertThat(ReconciliationBand.matches(Double.NaN, 3, false)).isFalse();
        assertThat(ReconciliationBand.matches(3, Double.POSITIVE_INFINITY, false)).isFalse();
    }

    @Test
    void theGapIsTheDifferenceAsAFractionOfTheLargerFigure() {
        assertThat(ReconciliationBand.gap(7, 23)).isEqualTo(16.0 / 23.0);
        assertThat(ReconciliationBand.gap(0, 0)).isZero();
    }

    @Test
    void aGapDescriptionNamesBothFiguresAndTheAllowance() {
        assertThat(ReconciliationBand.describeGap(7, 23, false)).isEqualTo("7 vs 23, 16 apart (1 allowed)");
        assertThat(ReconciliationBand.describeGap(100, 103, false)).isEqualTo("100 vs 103, 2.9% apart (2% allowed)");
        assertThat(ReconciliationBand.describeGap(162.86, 170, true)).isEqualTo("162.86 vs 170.00, 4.2% apart (2% allowed)");
    }

    // ---- the pairs ----

    @Test
    void theReferencePairsAreTheFourOfC3() {
        assertThat(ReferencePairs.ALL).extracting(p -> p.channel().name() + "." + p.metric() + "<->" + p.reference())
                .containsExactly("ADS.spend<->ref_ads_spend", "ADS.installs<->ref_ads_tap_installs",
                        "STORE.downloads<->ref_store_downloads",
                        "STORE.downloads_of_record<->ref_sales_first_time_downloads");
    }

    @Test
    void daysBothSidesHaveAreTheOnlyOnesCompared() {
        Map<LocalDate, Double> own = new TreeMap<>();
        Map<LocalDate, Double> reference = new TreeMap<>();
        own.put(day(1), 1.0);
        own.put(day(2), 2.0);
        own.put(day(3), 3.0);
        reference.put(day(2), 20.0);
        reference.put(day(3), 30.0);
        reference.put(day(4), 40.0); // own has no day 4

        Optional<ReferencePairs.Comparison> result = ReferencePairs.compare(own, reference, day(1), day(31));

        assertThat(result).hasValueSatisfying(c -> {
            assertThat(c.days()).isEqualTo(2);
            assertThat(c.ownSum()).isEqualTo(5.0);
            assertThat(c.referenceSum()).isEqualTo(50.0);
            assertThat(c.first()).isEqualTo(day(2));
            assertThat(c.last()).isEqualTo(day(3));
        });
    }

    @Test
    void daysOutsideTheRangeAreIgnored() {
        Map<LocalDate, Double> own = Map.of(day(1), 5.0, day(9), 7.0);
        Map<LocalDate, Double> reference = Map.of(day(1), 5.0, day(9), 7.0);

        assertThat(ReferencePairs.compare(own, reference, day(5), day(10))).hasValueSatisfying(c -> {
            assertThat(c.days()).isEqualTo(1);
            assertThat(c.ownSum()).isEqualTo(7.0);
        });
    }

    @Test
    void noDayInCommonSaysNothing() {
        assertThat(ReferencePairs.compare(Map.of(day(1), 5.0), Map.of(day(2), 5.0), day(1), day(5))).isEmpty();
        assertThat(ReferencePairs.compare(Map.of(), Map.of(day(2), 5.0), day(1), day(5))).isEmpty();
    }

    private static LocalDate day(int dayOfOctober) {
        return LocalDate.of(2026, 10, dayOfOctober);
    }
}
