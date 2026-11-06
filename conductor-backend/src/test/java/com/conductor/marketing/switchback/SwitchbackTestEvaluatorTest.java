package com.conductor.marketing.switchback;

import com.conductor.marketing.switchback.SwitchbackEvaluator.WeekInput;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settle arithmetic with no database: weekly organic (search minus Apple Ads first-time downloads, floored at 0 per
 * week), the on/off means, the n a verdict rests on, and the verdict the declared rule gives.
 */
class SwitchbackTestEvaluatorTest {

    private static final LocalDate W1 = LocalDate.of(2026, 11, 9);
    private static final SwitchbackRule DEFAULT_RULE = SwitchbackRule.defaults();

    private static WeekInput week(int index, boolean on, double search, double ads) {
        return new WeekInput(W1.plusWeeks(index), on, BigDecimal.valueOf(search), BigDecimal.valueOf(ads));
    }

    private static SwitchbackOutcome evaluate(int minN, SwitchbackRule rule, WeekInput... weeks) {
        return SwitchbackEvaluator.evaluate(List.of(weeks), rule, minN, "recipe app");
    }

    // ---- per-week figures --------------------------------------------------------------------------------

    @Test
    void organicIsSearchMinusAppleAdsFirstTimeDownloadsAndTotalAddsTheAdsBack() {
        SwitchbackOutcome out = evaluate(1, DEFAULT_RULE, week(0, true, 30, 12), week(1, false, 20, 0));

        SwitchbackOutcome.Week on = out.weeks().get(0);
        assertThat(on.weekStart()).isEqualTo("2026-11-09");
        assertThat(on.on()).isTrue();
        assertThat(on.searchDownloads()).isEqualByComparingTo("30");
        assertThat(on.adsNewDownloads()).isEqualByComparingTo("12");
        assertThat(on.organic()).isEqualByComparingTo("18");
        assertThat(on.totalInstalls()).isEqualByComparingTo("30");
        SwitchbackOutcome.Week off = out.weeks().get(1);
        assertThat(off.organic()).isEqualByComparingTo("20");
        assertThat(off.totalInstalls()).isEqualByComparingTo("20");
    }

    @Test
    void aWeekWherePaidExceededTheStoresSearchFigureIsFlooredAtZeroOrganicNotNegative() {
        SwitchbackOutcome out = evaluate(1, DEFAULT_RULE, week(0, true, 5, 8), week(1, false, 6, 0));

        SwitchbackOutcome.Week on = out.weeks().get(0);
        assertThat(on.organic()).isEqualByComparingTo("0");
        assertThat(on.totalInstalls()).isEqualByComparingTo("8");
    }

    @Test
    void nIsTheSumOfSearchDownloadsAcrossAllWeeks() {
        SwitchbackOutcome out = evaluate(1, DEFAULT_RULE, week(0, true, 30, 12), week(1, false, 20, 0),
                week(2, true, 25, 5));

        assertThat(out.n()).isEqualTo(75);
        assertThat(out.minN()).isEqualTo(1);
    }

    @Test
    void onAndOffMeansAreTakenOverTheirOwnWeeksToTwoDecimals() {
        SwitchbackOutcome out = evaluate(1, DEFAULT_RULE, week(0, true, 30, 10), week(1, false, 20, 0),
                week(2, true, 33, 10), week(3, false, 21, 0), week(4, false, 22, 0));

        assertThat(out.onWeeks().weeks()).isEqualTo(2);
        assertThat(out.onWeeks().meanOrganic()).isEqualByComparingTo("21.50");
        assertThat(out.onWeeks().meanTotalInstalls()).isEqualByComparingTo("31.50");
        assertThat(out.offWeeks().weeks()).isEqualTo(3);
        assertThat(out.offWeeks().meanOrganic()).isEqualByComparingTo("21.00");
        assertThat(out.offWeeks().meanTotalInstalls()).isEqualByComparingTo("21.00");
        assertThat(out.lift()).isEqualByComparingTo("10.50");
        assertThat(out.organicChangePct()).isEqualByComparingTo("2.4");
    }

    // ---- verdicts ----------------------------------------------------------------------------------------

    @Test
    void paidThatAddsInstallsWithoutShrinkingOrganicWorked() {
        SwitchbackOutcome out = evaluate(20, DEFAULT_RULE, week(0, true, 40, 20), week(1, false, 20, 0),
                week(2, true, 40, 20), week(3, false, 20, 0));

        assertThat(out.verdict()).isEqualTo("worked");
        assertThat(out.lift()).isEqualByComparingTo("20");
        assertThat(out.organicChangePct()).isEqualByComparingTo("0");
        assertThat(out.reason()).startsWith("Paid is incremental for \"recipe app\"");
    }

    @Test
    void paidThatTakesTheSameInstallsOutOfOrganicOnlyRelabelsThem() {
        SwitchbackOutcome out = evaluate(20, DEFAULT_RULE, week(0, true, 20, 10), week(1, false, 20, 0),
                week(2, true, 20, 10), week(3, false, 20, 0));

        assertThat(out.verdict()).isEqualTo("did_not_work");
        assertThat(out.lift()).isEqualByComparingTo("0");
        assertThat(out.organicChangePct()).isEqualByComparingTo("-50");
        assertThat(out.reason()).startsWith("Paid only relabels organic for \"recipe app\"");
    }

    @Test
    void noMovementInTotalsAndNoDropInOrganicIsNoEffect() {
        SwitchbackOutcome out = evaluate(20, DEFAULT_RULE, week(0, true, 20, 0), week(1, false, 20, 0),
                week(2, true, 20, 0), week(3, false, 20, 0));

        assertThat(out.verdict()).isEqualTo("no_effect");
        assertThat(out.reason()).startsWith("Switching \"recipe app\" on made no measurable difference");
    }

    @Test
    void aLiftOfExactlyMinLiftCountsAsIncremental() {
        SwitchbackOutcome out = evaluate(1, DEFAULT_RULE, week(0, true, 21, 1), week(1, false, 20, 0));

        assertThat(out.lift()).isEqualByComparingTo("1");
        assertThat(out.verdict()).isEqualTo("worked");
    }

    @Test
    void aLiftBelowMinLiftIsNotIncremental() {
        SwitchbackOutcome out = evaluate(1, DEFAULT_RULE, week(0, true, 21, 1), week(1, false, 20, 0),
                week(2, true, 20, 0), week(3, false, 20, 0));

        assertThat(out.lift()).isEqualByComparingTo("0.50");
        assertThat(out.verdict()).isEqualTo("no_effect");
    }

    @Test
    void organicFallingMoreThanTheLimitFailsTheRuleEvenWhenTotalsRose() {
        // on: organic 79, ads 30, total 109; off: organic 100, total 100 -> lift 9 but organic fell 21%
        SwitchbackOutcome out = evaluate(1, DEFAULT_RULE, week(0, true, 109, 30), week(1, false, 100, 0));

        assertThat(out.lift()).isEqualByComparingTo("9");
        assertThat(out.organicChangePct()).isEqualByComparingTo("-21");
        assertThat(out.verdict()).isEqualTo("did_not_work");
    }

    @Test
    void organicFallingByExactlyTwentyPercentIsStillIncremental() {
        // on: organic 80, ads 30, total 110; off: organic 100 -> change -20.0, not more than 20
        SwitchbackOutcome out = evaluate(1, DEFAULT_RULE, week(0, true, 110, 30), week(1, false, 100, 0));

        assertThat(out.organicChangePct()).isEqualByComparingTo("-20");
        assertThat(out.verdict()).isEqualTo("worked");
    }

    @Test
    void aStricterDeclaredRuleChangesTheVerdictForTheSameData() {
        SwitchbackRule strict = SwitchbackRule.of(null, BigDecimal.valueOf(25), BigDecimal.valueOf(5));

        SwitchbackOutcome out = evaluate(1, strict, week(0, true, 40, 20), week(1, false, 20, 0));

        assertThat(out.lift()).isEqualByComparingTo("20");
        assertThat(out.verdict()).isEqualTo("no_effect");
    }

    @Test
    void whenOffWeeksHadNoOrganicNothingCouldFallSoOrganicChangeIsUnknownAndNotAFailure() {
        SwitchbackOutcome out = evaluate(5, DEFAULT_RULE, week(0, true, 10, 10), week(1, false, 0, 0));

        assertThat(out.organicChangePct()).isNull();
        assertThat(out.lift()).isEqualByComparingTo("10");
        assertThat(out.verdict()).isEqualTo("worked");
    }

    // ---- minimum n ---------------------------------------------------------------------------------------

    @Test
    void belowMinNTheVerdictIsInconclusiveAndSaysTheNReached() {
        SwitchbackOutcome out = evaluate(20, DEFAULT_RULE, week(0, true, 8, 4), week(1, false, 4, 0));

        assertThat(out.n()).isEqualTo(12);
        assertThat(out.verdict()).isEqualTo("inconclusive");
        assertThat(out.reason()).contains("n reached 12 of min 20");
    }

    @Test
    void anInconclusiveResultStillCarriesTheFiguresSoAHumanCanSeeWhatWasMeasured() {
        SwitchbackOutcome out = evaluate(20, DEFAULT_RULE, week(0, true, 8, 4), week(1, false, 4, 0));

        assertThat(out.onWeeks().meanTotalInstalls()).isEqualByComparingTo("8");
        assertThat(out.offWeeks().meanTotalInstalls()).isEqualByComparingTo("4");
        assertThat(out.lift()).isEqualByComparingTo("4");
    }

    @Test
    void exactlyMinNIsEnoughForAVerdict() {
        SwitchbackOutcome out = evaluate(60, DEFAULT_RULE, week(0, true, 40, 20), week(1, false, 20, 0));

        assertThat(out.n()).isEqualTo(60);
        assertThat(out.verdict()).isEqualTo("worked");
    }

    // ---- the change record's text ------------------------------------------------------------------------

    @Test
    void theOutcomeTextListsEveryWeekThenTheMeansAndTheN() {
        SwitchbackOutcome out = evaluate(20, DEFAULT_RULE, week(0, true, 40, 20), week(1, false, 20, 0));

        String text = SwitchbackEvaluator.outcomeText(out);

        assertThat(text).contains("2026-11-09 ON: search 40, Apple Ads first-time 20, organic 20, total installs 40");
        assertThat(text).contains("2026-11-16 OFF: search 20, Apple Ads first-time 0, organic 20, total installs 20");
        assertThat(text).contains("On weeks (1): mean organic 20, mean total installs 40");
        assertThat(text).contains("Off weeks (1): mean organic 20, mean total installs 20");
        assertThat(text).contains("n = 60 first-time search downloads (min 20)");
    }
}
