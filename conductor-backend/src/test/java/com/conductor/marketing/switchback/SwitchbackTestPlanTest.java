package com.conductor.marketing.switchback;

import com.conductor.exception.BusinessException;
import com.conductor.marketing.switchback.SwitchbackPlan.CurrentWeek;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The declared plan with no database: which weeks are valid, what the rule says, and which state a week should be in. */
class SwitchbackTestPlanTest {

    private static final LocalDate MON = LocalDate.of(2026, 11, 9);

    private static SwitchbackWeek week(int index, boolean on) {
        return new SwitchbackWeek(MON.plusWeeks(index), on);
    }

    private static List<SwitchbackWeek> alternating(int count) {
        List<SwitchbackWeek> weeks = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            weeks.add(week(i, i % 2 == 0));
        }
        return weeks;
    }

    // ---- weeks -------------------------------------------------------------------------------------------

    @Test
    void twoToTwelveConsecutiveMondaysWithBothStatesAreValid() {
        assertThat(SwitchbackPlan.validateWeeks(alternating(2))).hasSize(2);
        assertThat(SwitchbackPlan.validateWeeks(alternating(12))).hasSize(12);
    }

    @Test
    void anyOnOffPatternIsAcceptedAsLongAsBothStatesAppear() {
        List<SwitchbackWeek> weeks = List.of(week(0, true), week(1, true), week(2, false), week(3, false));

        assertThat(SwitchbackPlan.validateWeeks(weeks)).isEqualTo(weeks);
    }

    @Test
    void aWeekThatDoesNotStartOnAMondayIsRefusedNamingTheDay() {
        List<SwitchbackWeek> weeks = List.of(week(0, true), new SwitchbackWeek(MON.plusDays(8), false));

        assertThatThrownBy(() -> SwitchbackPlan.validateWeeks(weeks)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("2026-11-17").hasMessageContaining("Tuesday").hasMessageContaining("Monday");
    }

    @Test
    void aGapBetweenWeeksIsRefused() {
        List<SwitchbackWeek> weeks = List.of(week(0, true), week(1, false), week(3, true));

        assertThatThrownBy(() -> SwitchbackPlan.validateWeeks(weeks)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("consecutive").hasMessageContaining("2026-11-30");
    }

    @Test
    void weeksOutOfOrderOrRepeatedAreRefused() {
        assertThatThrownBy(() -> SwitchbackPlan.validateWeeks(List.of(week(1, true), week(0, false))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("consecutive");
        assertThatThrownBy(() -> SwitchbackPlan.validateWeeks(List.of(week(0, true), week(0, false))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("consecutive");
    }

    @Test
    void aTestWithEveryWeekOnOrEveryWeekOffIsRefusedBecauseThereIsNothingToCompare() {
        List<SwitchbackWeek> allOn = List.of(week(0, true), week(1, true), week(2, true));
        List<SwitchbackWeek> allOff = List.of(week(0, false), week(1, false));

        assertThatThrownBy(() -> SwitchbackPlan.validateWeeks(allOn)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("at least one week on and one week off");
        assertThatThrownBy(() -> SwitchbackPlan.validateWeeks(allOff)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("at least one week on and one week off");
    }

    @Test
    void fewerThanTwoOrMoreThanTwelveWeeksAreRefused() {
        assertThatThrownBy(() -> SwitchbackPlan.validateWeeks(List.of(week(0, true))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("2 to 12 weeks");
        assertThatThrownBy(() -> SwitchbackPlan.validateWeeks(alternating(13)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("2 to 12 weeks");
        assertThatThrownBy(() -> SwitchbackPlan.validateWeeks(List.of())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> SwitchbackPlan.validateWeeks(null)).isInstanceOf(BusinessException.class);
    }

    @Test
    void aWeekWithNoDateIsRefusedNotSkipped() {
        List<SwitchbackWeek> weeks = List.of(week(0, true), new SwitchbackWeek(null, false));

        assertThatThrownBy(() -> SwitchbackPlan.validateWeeks(weeks)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("weekStart");
    }

    // ---- the rule ----------------------------------------------------------------------------------------

    @Test
    void theDefaultRuleIsMinLiftOneAndMaxCannibalisationTwentyPercentOnOrganicSearchFirstTimeDownloads() {
        SwitchbackRule rule = SwitchbackRule.defaults();

        assertThat(rule.metric()).isEqualTo("organic_search_first_time_downloads");
        assertThat(rule.minLift()).isEqualByComparingTo("1");
        assertThat(rule.maxCannibalisationPct()).isEqualByComparingTo("20");
    }

    @Test
    void theRuleIsShownAsASentenceBuiltFromItsFields() {
        SwitchbackRule rule = SwitchbackRule.of(null, new BigDecimal("1.50"), BigDecimal.valueOf(10));

        assertThat(rule.incrementalIf()).isEqualTo("Paid is incremental if the on-week mean of total first-time installs "
                + "exceeds the off-week mean by at least 1.5 and the on-week mean of organic search first-time "
                + "downloads does not fall by more than 10% against the off-week mean.");
    }

    @Test
    void fieldsLeftOutTakeTheirDefaults() {
        SwitchbackRule rule = SwitchbackRule.of(null, null, BigDecimal.valueOf(5));

        assertThat(rule.minLift()).isEqualByComparingTo("1");
        assertThat(rule.maxCannibalisationPct()).isEqualByComparingTo("5");
    }

    @Test
    void anUnknownMetricANegativeLiftOrAPercentOutsideZeroToOneHundredIsRefused() {
        assertThatThrownBy(() -> SwitchbackRule.of("organic_search_impressions", null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("organic_search_first_time_downloads");
        assertThatThrownBy(() -> SwitchbackRule.of(null, BigDecimal.valueOf(-1), null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("minLift");
        assertThatThrownBy(() -> SwitchbackRule.of(null, null, BigDecimal.valueOf(101)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("maxCannibalisationPct");
        assertThatThrownBy(() -> SwitchbackRule.of(null, null, BigDecimal.valueOf(-0.5)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("maxCannibalisationPct");
    }

    // ---- which state a week should be in -----------------------------------------------------------------

    @Test
    void aRunningTestSaysOnDuringAnOnWeekAndOffDuringAnOffWeek() {
        List<SwitchbackWeek> weeks = alternating(4);

        CurrentWeek monday = SwitchbackPlan.currentWeek("RUNNING", weeks, MON);
        CurrentWeek sundayOfWeekOne = SwitchbackPlan.currentWeek("RUNNING", weeks, MON.plusDays(6));
        CurrentWeek secondWeek = SwitchbackPlan.currentWeek("RUNNING", weeks, MON.plusDays(7));

        assertThat(monday.state()).isEqualTo("ON");
        assertThat(monday.weekStart()).isEqualTo(MON);
        assertThat(monday.weekEnd()).isEqualTo(MON.plusDays(6));
        assertThat(sundayOfWeekOne.state()).isEqualTo("ON");
        assertThat(secondWeek.state()).isEqualTo("OFF");
        assertThat(secondWeek.weekStart()).isEqualTo(MON.plusDays(7));
    }

    @Test
    void beforeTheFirstWeekAndAfterTheLastWeekATestIsNotRunning() {
        List<SwitchbackWeek> weeks = alternating(2);

        CurrentWeek before = SwitchbackPlan.currentWeek("RUNNING", weeks, MON.minusDays(1));
        CurrentWeek after = SwitchbackPlan.currentWeek("RUNNING", weeks, MON.plusDays(14));

        assertThat(before.state()).isEqualTo("not running");
        assertThat(before.weekStart()).isNull();
        assertThat(before.weekEnd()).isNull();
        assertThat(after.state()).isEqualTo("not running");
    }

    @Test
    void aTestThatIsNotRunningNeverSaysOnOrOffWhateverTheDate() {
        List<SwitchbackWeek> weeks = alternating(4);

        for (String status : List.of("DRAFT", "SETTLED", "INCONCLUSIVE", "CANCELLED")) {
            assertThat(SwitchbackPlan.currentWeek(status, weeks, MON).state()).as(status).isEqualTo("not running");
        }
    }
}
