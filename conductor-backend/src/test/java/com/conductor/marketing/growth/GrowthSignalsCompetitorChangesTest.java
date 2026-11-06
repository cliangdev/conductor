package com.conductor.marketing.growth;

import com.conductor.marketing.growth.CompetitorChanges.SnapshotView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Weekly ratings growth and the competitor change list, both computed from two snapshots. */
class GrowthSignalsCompetitorChangesTest {

    private static final LocalDate LAST_WEEK = LocalDate.of(2026, 10, 26);
    private static final LocalDate THIS_WEEK = LocalDate.of(2026, 11, 2);

    private static SnapshotView snapshot(LocalDate week, String price, String currency, String rating, Long count,
                                         String version, String title, String subtitle, String hash) {
        return inCountry("us", week, price, currency, rating, count, version, title, subtitle, hash);
    }

    private static SnapshotView inCountry(String country, LocalDate week, String price, String currency, String rating,
                                          Long count, String version, String title, String subtitle, String hash) {
        return new SnapshotView(week, country, price == null ? null : new BigDecimal(price), currency,
                rating == null ? null : new BigDecimal(rating), count, version, title, subtitle, hash);
    }

    private static SnapshotView base(LocalDate week) {
        return snapshot(week, "0.99", "USD", "4.5", 100L, "1.0", "Rival", "Cook faster", "h1");
    }

    // ---- weekly growth --------------------------------------------------------------------------------------

    @Test
    void weeklyGrowthIsTheRatingCountDeltaBetweenTheLatestTwoSnapshots() {
        SnapshotView previous = snapshot(LAST_WEEK, null, null, null, 120L, null, null, null, null);
        SnapshotView latest = snapshot(THIS_WEEK, null, null, null, 135L, null, null, null, null);

        assertThat(CompetitorChanges.weeklyGrowth(previous, latest)).isEqualTo(15L);
    }

    @Test
    void weeklyGrowthCanBeNegativeWhenRatingsAreRemoved() {
        SnapshotView previous = snapshot(LAST_WEEK, null, null, null, 120L, null, null, null, null);
        SnapshotView latest = snapshot(THIS_WEEK, null, null, null, 118L, null, null, null, null);

        assertThat(CompetitorChanges.weeklyGrowth(previous, latest)).isEqualTo(-2L);
    }

    @Test
    void weeklyGrowthIsNullNotZeroWithOnlyOneSnapshot() {
        SnapshotView latest = snapshot(THIS_WEEK, null, null, null, 135L, null, null, null, null);

        assertThat(CompetitorChanges.weeklyGrowth(null, latest)).isNull();
        assertThat(CompetitorChanges.weeklyGrowth(latest, null)).isNull();
        assertThat(CompetitorChanges.weeklyGrowth(null, null)).isNull();
    }

    @Test
    void weeklyGrowthIsNullWhenEitherWeekHasNoRatingCount() {
        SnapshotView counted = snapshot(LAST_WEEK, null, null, null, 120L, null, null, null, null);
        SnapshotView uncounted = snapshot(THIS_WEEK, null, null, null, null, null, null, null, null);

        assertThat(CompetitorChanges.weeklyGrowth(counted, uncounted)).isNull();
        assertThat(CompetitorChanges.weeklyGrowth(uncounted, counted)).isNull();
    }

    // ---- change list ----------------------------------------------------------------------------------------

    @Test
    void priceAndVersionChangesAreListed() {
        SnapshotView previous = base(LAST_WEEK);
        SnapshotView latest = snapshot(THIS_WEEK, "1.99", "USD", "4.5", 100L, "1.1", "Rival", "Cook faster", "h1");

        assertThat(CompetitorChanges.diff(previous, latest)).containsExactly("price 0.99 -> 1.99", "version 1.0 -> 1.1");
    }

    @Test
    void ratingsTitleSubtitleAndDescriptionChangesAreListed() {
        SnapshotView previous = base(LAST_WEEK);
        SnapshotView latest = snapshot(THIS_WEEK, "0.99", "USD", "4.6", 130L, "1.0", "Rival Pro", "Cook even faster", "h2");

        assertThat(CompetitorChanges.diff(previous, latest)).containsExactly(
                "rating 4.5 -> 4.6", "ratings 100 -> 130", "title Rival -> Rival Pro",
                "subtitle Cook faster -> Cook even faster", "description changed");
    }

    @Test
    void nothingChangedMeansAnEmptyList() {
        assertThat(CompetitorChanges.diff(base(LAST_WEEK), base(THIS_WEEK))).isEmpty();
    }

    @Test
    void equalPricesWithDifferentScaleAreNotAChange() {
        SnapshotView previous = snapshot(LAST_WEEK, "1.0", "USD", null, null, null, null, null, null);
        SnapshotView latest = snapshot(THIS_WEEK, "1.00", "USD", null, null, null, null, null, null);

        assertThat(CompetitorChanges.diff(previous, latest)).isEmpty();
    }

    @Test
    void aFieldMissingInEitherWeekIsUnknownNotAChange() {
        SnapshotView previous = base(LAST_WEEK);
        SnapshotView latest = snapshot(THIS_WEEK, null, null, null, null, null, null, null, null);

        assertThat(CompetitorChanges.diff(previous, latest)).isEmpty();
        assertThat(CompetitorChanges.diff(latest, previous)).isEmpty();
    }

    @Test
    void withoutAPreviousSnapshotThereIsNothingToCompare() {
        assertThat(CompetitorChanges.diff(null, base(THIS_WEEK))).isEmpty();
    }

    @Test
    void aPriceQuotedInAnotherCurrencyNamesTheCurrencies() {
        SnapshotView previous = base(LAST_WEEK);
        SnapshotView latest = snapshot(THIS_WEEK, "0.89", "EUR", "4.5", 100L, "1.0", "Rival", "Cook faster", "h1");

        assertThat(CompetitorChanges.diff(previous, latest)).containsExactly("price 0.99 USD -> 0.89 EUR");
    }

    // ---- storefronts ----------------------------------------------------------------------------------------

    @Test
    void snapshotsOfDifferentStorefrontsAreNeverCompared() {
        SnapshotView us = inCountry("us", LAST_WEEK, "0.99", "USD", "4.5", 100L, "1.0", "Rival", "Cook faster", "h1");
        SnapshotView gb = inCountry("gb", THIS_WEEK, "0.89", "GBP", "4.1", 12L, "1.1", "Rival", "Cook faster", "h2");

        assertThat(CompetitorChanges.weeklyGrowth(us, gb)).isNull();
        assertThat(CompetitorChanges.diff(us, gb)).isEmpty();
    }

    @Test
    void aSnapshotWithNoRecordedStorefrontIsCompared() {
        SnapshotView unknown = inCountry(null, LAST_WEEK, "0.99", "USD", "4.5", 100L, "1.0", "Rival", "x", "h1");
        SnapshotView us = inCountry("US", THIS_WEEK, "1.99", "USD", "4.5", 110L, "1.0", "Rival", "x", "h1");

        assertThat(CompetitorChanges.weeklyGrowth(unknown, us)).isEqualTo(10L);
        assertThat(CompetitorChanges.diff(unknown, us)).contains("price 0.99 -> 1.99");
    }
}
