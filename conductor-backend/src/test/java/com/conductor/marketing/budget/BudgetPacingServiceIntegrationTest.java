package com.conductor.marketing.budget;

import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.marketing.budget.BudgetPacingService.ChannelPacing;
import com.conductor.marketing.budget.BudgetPacingService.CpiWindow;
import com.conductor.marketing.budget.BudgetPacingService.Pacing;
import com.conductor.marketing.budget.BudgetPacingService.RuleEvaluation;
import com.conductor.marketing.budget.BudgetPacingService.StopRuleInput;
import com.conductor.marketing.budget.BudgetPacingService.TrippedRule;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Proves {@link BudgetPacingService} against real Postgres: month-to-date spend against the monthly cap, the
 * compliance flag for paid channels without a stop rule, stop-rule evaluation over settled days only, and the
 * marginal cost per install. Rows are seeded straight into {@code channel_metric_daily}; "today" is a fixed day
 * (2026-11-15) so every number is deterministic.
 */
@Transactional
class BudgetPacingServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);
    private static final YearMonth NOV = YearMonth.of(2026, 11);

    @Autowired private BudgetPacingService service;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ChannelMetricDailyRepository dailyRepository;

    private String projectId;
    private String connectionId;

    @BeforeEach
    void setUp() {
        projectId = newProject();
        connectionId = newConnection(projectId);
    }

    // ---- pacing against the cap -------------------------------------------------------------------

    @Test
    void eightyDollarsByDayFifteenOfAThirtyDayMonthAgainstA160CapPacesAtOneAndProjectsTheCap() {
        service.setBudget(projectId, "2026-11", BigDecimal.valueOf(160), "USD", null);
        seedAdsSpend(1, 14, 5);
        put("ADS", "spend", "", nov(15), 10);

        Pacing p = service.pacing(projectId, NOV, TODAY);

        assertThat(p.month()).isEqualTo("2026-11");
        assertThat(p.capAmount()).isEqualTo(160.0);
        assertThat(p.currency()).isEqualTo("USD");
        assertThat(p.through()).isEqualTo(nov(15));
        assertThat(p.daysElapsed()).isEqualTo(15);
        assertThat(p.daysInMonth()).isEqualTo(30);
        assertThat(p.mtdSpend()).isEqualTo(80.0);
        assertThat(p.expectedSpend()).isEqualTo(80.0);
        assertThat(p.pacePct()).isEqualTo(1.0);
        assertThat(p.projectedSpend()).isEqualTo(160.0);
    }

    @Test
    void spendAtHalfThePaceReadsPointFiveAndProjectsHalfTheCap() {
        service.setBudget(projectId, "2026-11", BigDecimal.valueOf(160), "USD", null);
        seedAdsSpend(1, 15, 40.0 / 15);

        Pacing p = service.pacing(projectId, NOV, TODAY);

        assertThat(p.mtdSpend()).isEqualTo(40.0);
        assertThat(p.pacePct()).isEqualTo(0.5);
        assertThat(p.projectedSpend()).isEqualTo(80.0);
    }

    @Test
    void monthToDateSpendIsShownDailyWithTheCumulativeAndStraightLineExpectation() {
        service.setBudget(projectId, "2026-11", BigDecimal.valueOf(150), "USD", null);
        seedAdsSpend(1, 15, 6);

        Pacing p = service.pacing(projectId, NOV, TODAY);

        assertThat(p.daily()).hasSize(15);
        assertThat(p.daily().get(0).day()).isEqualTo(nov(1));
        assertThat(p.daily().get(0).spend()).isEqualTo(6.0);
        assertThat(p.daily().get(0).cumulativeSpend()).isEqualTo(6.0);
        assertThat(p.daily().get(0).expectedSpend()).isEqualTo(5.0);
        assertThat(p.daily().get(14).cumulativeSpend()).isEqualTo(90.0);
        assertThat(p.daily().get(14).expectedSpend()).isEqualTo(75.0);
        assertThat(adsOf(p).daily()).hasSize(15);
        assertThat(adsOf(p).daily().get(14).cumulativeSpend()).isEqualTo(90.0);
        assertThat(adsOf(p).daily().get(14).expectedSpend()).isNull();
    }

    @Test
    void withoutACapPacingShowsSpendButNoCapAndNoPace() {
        seedAdsSpend(1, 15, 80.0 / 15);

        Pacing p = service.pacing(projectId, NOV, TODAY);

        assertThat(p.capAmount()).isNull();
        assertThat(p.currency()).isNull();
        assertThat(p.expectedSpend()).isNull();
        assertThat(p.pacePct()).isNull();
        assertThat(p.mtdSpend()).isEqualTo(80.0);
        assertThat(p.projectedSpend()).isEqualTo(160.0);
        assertThat(p.daily().get(14).expectedSpend()).isNull();
    }

    @Test
    void aCapSetForAnotherMonthIsNotThisMonthsCap() {
        service.setBudget(projectId, "2026-10", BigDecimal.valueOf(160), "USD", null);
        seedAdsSpend(1, 15, 5);

        assertThat(service.pacing(projectId, NOV, TODAY).capAmount()).isNull();
    }

    @Test
    void settledAndProvisionalSpendAreCountedSeparately() {
        service.setBudget(projectId, "2026-11", BigDecimal.valueOf(160), "USD", null);
        seedAdsSpend(1, 13, 5);
        putProvisional("ADS", "spend", "", nov(14), 7);
        putProvisional("ADS", "spend", "", nov(15), 7);

        Pacing p = service.pacing(projectId, NOV, TODAY);

        assertThat(p.mtdSpend()).isEqualTo(65.0);
        assertThat(p.provisionalSpend()).isEqualTo(14.0);
        assertThat(p.through()).isEqualTo(nov(13));
        assertThat(p.daysElapsed()).isEqualTo(13);
        assertThat(p.projectedSpend()).isEqualTo(150.0);
        assertThat(p.daily()).hasSize(15);
        assertThat(p.daily().get(13).spend()).isNull();
        assertThat(p.daily().get(13).provisionalSpend()).isEqualTo(7.0);
        assertThat(p.daily().get(13).cumulativeSpend()).isEqualTo(65.0);
        assertThat(adsOf(p).provisionalSpend()).isEqualTo(14.0);
    }

    @Test
    void aMonthWithNoSettledSpendHasNoPaceAndNoProjection() {
        service.setBudget(projectId, "2026-11", BigDecimal.valueOf(160), "USD", null);
        putProvisional("ADS", "spend", "", nov(2), 4);

        Pacing p = service.pacing(projectId, NOV, nov(3));

        assertThat(p.through()).isNull();
        assertThat(p.daysElapsed()).isZero();
        assertThat(p.mtdSpend()).isZero();
        assertThat(p.provisionalSpend()).isEqualTo(4.0);
        assertThat(p.pacePct()).isNull();
        assertThat(p.projectedSpend()).isNull();
    }

    @Test
    void anotherProjectsSpendIsNotCounted() {
        String other = newProject();
        String otherConnection = newConnection(other);
        dailyRepository.upsert(UUID.randomUUID().toString(), other, otherConnection, "apple-search-ads", "ADS",
                "spend", "", nov(5), BigDecimal.valueOf(999), false);
        seedAdsSpend(1, 15, 5);

        assertThat(service.pacing(projectId, NOV, TODAY).mtdSpend()).isEqualTo(75.0);
    }

    // ---- paid channels and compliance -------------------------------------------------------------

    @Test
    void aPaidChannelWithSpendAndNoStopRuleIsNonCompliantUntilARuleIsAdded() {
        seedAdsSpend(1, 15, 5);

        ChannelPacing before = adsOf(service.pacing(projectId, NOV, TODAY));
        assertThat(before.compliant()).isFalse();
        assertThat(before.complianceReason()).isEqualTo("no stop rule");

        service.addStopRule(projectId, cpiRule(10, 20), null);

        ChannelPacing after = adsOf(service.pacing(projectId, NOV, TODAY));
        assertThat(after.compliant()).isTrue();
        assertThat(after.complianceReason()).isNull();
    }

    @Test
    void anyChannelWithASpendMetricCountsAsPaid() {
        seedAdsSpend(1, 15, 5);
        String boosts = newConnection(projectId);
        for (int d = 1; d <= 15; d++) {
            dailyRepository.upsert(UUID.randomUUID().toString(), projectId, boosts, "social-boosts", "SOCIAL", "spend",
                    "", nov(d), BigDecimal.valueOf(2), false);
        }

        Pacing p = service.pacing(projectId, NOV, TODAY);

        assertThat(p.channels()).extracting(ChannelPacing::channel).containsExactly("ADS", "SOCIAL");
        assertThat(p.mtdSpend()).isEqualTo(105.0);
        assertThat(p.channels()).allSatisfy(c -> assertThat(c.compliant()).isFalse());
    }

    @Test
    void aChannelWithoutAnySpendRowIsNotPaid() {
        for (int d = 1; d <= 15; d++) {
            put("WEB", "visitors", "", nov(d), 30);
        }

        assertThat(service.pacing(projectId, NOV, TODAY).channels()).isEmpty();
    }

    @Test
    void spendInAnEarlierMonthDoesNotMakeAChannelPaidThisMonth() {
        put("ADS", "spend", "", LocalDate.of(2026, 10, 20), 5);

        assertThat(service.pacing(projectId, NOV, TODAY).channels()).isEmpty();
    }

    @Test
    void aChannelWithAnActiveRuleIsListedEvenBeforeItsFirstSpendRowOfTheMonth() {
        service.addStopRule(projectId, cpiRule(10, 0), null);

        Pacing p = service.pacing(projectId, NOV, TODAY);

        assertThat(adsOf(p).compliant()).isTrue();
        assertThat(adsOf(p).mtdSpend()).isZero();
        assertThat(adsOf(p).through()).isNull();
    }

    @Test
    void removingTheOnlyRuleMakesTheChannelNonCompliantAgainAndKeepsNoActiveRule() {
        seedAdsSpend(1, 15, 5);
        MarketingStopRule rule = service.addStopRule(projectId, cpiRule(10, 20), null);

        service.removeStopRule(projectId, rule.getId());

        assertThat(service.listStopRules(projectId)).isEmpty();
        assertThat(adsOf(service.pacing(projectId, NOV, TODAY)).compliant()).isFalse();
        service.removeStopRule(projectId, rule.getId());
    }

    @Test
    void aRuleOfAnotherProjectCannotBeRemoved() {
        String other = newProject();
        MarketingStopRule rule = service.addStopRule(other, cpiRule(10, 20), null);

        assertThatThrownBy(() -> service.removeStopRule(projectId, rule.getId()))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
        assertThat(service.listStopRules(other)).hasSize(1);
    }

    // ---- stop rules -------------------------------------------------------------------------------

    @Test
    void aCpiRuleWithAMinimumSampleOf20DoesNotTripAt15TapsAndTripsAt25() {
        seedWindow(9, 15, 20, 1, 0);
        seedTapsAcrossWindow(15);
        service.addStopRule(projectId, cpiRule(10, 20), null);

        RuleEvaluation below = onlyRule(service.pacing(projectId, NOV, TODAY));
        assertThat(below.observed()).isEqualTo(20.0);
        assertThat(below.sample()).isEqualTo(15.0);
        assertThat(below.belowMinSample()).isTrue();
        assertThat(below.tripped()).isFalse();
        assertThat(service.trippedRules(projectId, TODAY)).isEmpty();

        seedTapsAcrossWindow(25);

        RuleEvaluation enough = onlyRule(service.pacing(projectId, NOV, TODAY));
        assertThat(enough.sample()).isEqualTo(25.0);
        assertThat(enough.belowMinSample()).isFalse();
        assertThat(enough.tripped()).isTrue();
        assertThat(service.trippedRules(projectId, TODAY)).hasSize(1);
    }

    @Test
    void aRuleTripsWhenTheSampleEqualsTheMinimum() {
        seedWindow(9, 15, 20, 1, 0);
        seedTapsAcrossWindow(20);
        service.addStopRule(projectId, cpiRule(10, 20), null);

        assertThat(onlyRule(service.pacing(projectId, NOV, TODAY)).tripped()).isTrue();
    }

    @Test
    void aRuleDoesNotTripWhenTheComparatorDoesNotHold() {
        seedWindow(9, 15, 5, 1, 30);
        service.addStopRule(projectId, cpiRule(10, 20), null);

        RuleEvaluation e = onlyRule(service.pacing(projectId, NOV, TODAY));

        assertThat(e.observed()).isEqualTo(5.0);
        assertThat(e.tripped()).isFalse();
        assertThat(e.belowMinSample()).isFalse();
    }

    @Test
    void aLessThanRuleTripsWhenTheObservedValueFallsBelowTheThreshold() {
        seedWindow(9, 15, 10, 1, 100);
        service.addStopRule(projectId, new StopRuleInput("ADS", null, "install_rate", "LT", BigDecimal.valueOf(0.05),
                20, 7, null), null);

        RuleEvaluation e = onlyRule(service.pacing(projectId, NOV, TODAY));

        assertThat(e.observed()).isCloseTo(0.01, within(1e-9));
        assertThat(e.tripped()).isTrue();
    }

    @Test
    void provisionalDaysNeverCountTowardATrippedRule() {
        // settled days 9..13 are healthy (CPI 5); days 14 and 15 are provisional with a terrible CPI
        seedWindow(9, 13, 5, 1, 10);
        for (int d = 14; d <= 15; d++) {
            putProvisional("ADS", "spend", "", nov(d), 500);
            putProvisional("ADS", "installs", "", nov(d), 1);
            putProvisional("ADS", "taps", "", nov(d), 50);
        }
        service.addStopRule(projectId, cpiRule(10, 20), null);

        RuleEvaluation e = onlyRule(service.pacing(projectId, NOV, TODAY));

        assertThat(e.windowTo()).isEqualTo(nov(13));
        assertThat(e.observed()).isEqualTo(5.0);
        assertThat(e.sample()).isEqualTo(50.0);
        assertThat(e.tripped()).isFalse();
        assertThat(service.trippedRules(projectId, TODAY)).isEmpty();
    }

    @Test
    void aRuleOnlyReadsItsWindowDays() {
        // days 1..8 are terrible, the 7-day window (9..15) is healthy
        seedWindow(1, 8, 500, 1, 100);
        seedWindow(9, 15, 5, 1, 100);
        service.addStopRule(projectId, cpiRule(10, 20), null);

        RuleEvaluation e = onlyRule(service.pacing(projectId, NOV, TODAY));

        assertThat(e.windowFrom()).isEqualTo(nov(9));
        assertThat(e.windowTo()).isEqualTo(nov(15));
        assertThat(e.observed()).isEqualTo(5.0);
        assertThat(e.tripped()).isFalse();
    }

    @Test
    void aKeywordScopedRuleReadsOnlyThatKeywordsRows() {
        seedWindow(9, 15, 10, 2, 10); // channel total: CPI 5, 70 taps
        seedDimension("keyword:1:alpha", 9, 15, 20, 1, 5); // CPI 20, 35 taps
        seedDimension("keyword:2:beta", 9, 15, 4, 2, 5); // CPI 2
        seedDimension("keyword:10:gamma", 9, 15, 100, 1, 5); // CPI 100; id 10 must not match id 1
        service.addStopRule(projectId, scoped("keyword:1", 10, 20), null);
        service.addStopRule(projectId, scoped("keyword:2", 10, 20), null);
        service.addStopRule(projectId, cpiRule(10, 20), null);

        List<RuleEvaluation> rules = adsOf(service.pacing(projectId, NOV, TODAY)).rules();

        RuleEvaluation alpha = ruleAt(rules, "keyword:1");
        assertThat(alpha.observed()).isEqualTo(20.0);
        assertThat(alpha.sample()).isEqualTo(35.0);
        assertThat(alpha.tripped()).isTrue();
        RuleEvaluation beta = ruleAt(rules, "keyword:2");
        assertThat(beta.observed()).isEqualTo(2.0);
        assertThat(beta.tripped()).isFalse();
        RuleEvaluation channel = ruleAt(rules, "channel");
        assertThat(channel.observed()).isEqualTo(5.0);
        assertThat(channel.sample()).isEqualTo(70.0);
        assertThat(channel.tripped()).isFalse();
        assertThat(service.trippedRules(projectId, TODAY)).extracting(TrippedRule::scope).containsExactly("keyword:1");
    }

    @Test
    void aCampaignScopedRuleMatchesTheCampaignIdPrefixOnly() {
        seedWindow(9, 15, 10, 2, 10);
        seedDimension("campaign:7:Brand terms", 9, 15, 20, 1, 5);
        seedDimension("campaign:70:Other", 9, 15, 1000, 1, 5);
        service.addStopRule(projectId, new StopRuleInput("ADS", "campaign:7", "spend", "GT", BigDecimal.valueOf(100),
                0, 7, null), null);

        RuleEvaluation e = onlyRule(service.pacing(projectId, NOV, TODAY));

        assertThat(e.observed()).isEqualTo(140.0);
        assertThat(e.tripped()).isTrue();
    }

    @Test
    void aCostPerInstallRuleTripsWhenThereAreNoInstallsButSpend() {
        seedWindow(9, 15, 10, 0, 40);
        service.addStopRule(projectId, cpiRule(10, 20), null);
        service.addStopRule(projectId, new StopRuleInput("ADS", null, "cost_per_install", "LT", BigDecimal.valueOf(3),
                20, 7, null), null);

        List<RuleEvaluation> rules = adsOf(service.pacing(projectId, NOV, TODAY)).rules();

        RuleEvaluation gt = rules.stream().filter(r -> r.comparator().equals("GT")).findFirst().orElseThrow();
        assertThat(gt.observed()).isNull();
        assertThat(gt.tripped()).isTrue();
        assertThat(gt.detail()).contains("no installs");
        RuleEvaluation lt = rules.stream().filter(r -> r.comparator().equals("LT")).findFirst().orElseThrow();
        assertThat(lt.tripped()).isFalse();
    }

    @Test
    void aCostPerInstallRuleDoesNotTripWithNoSpendAndNoInstalls() {
        seedWindow(9, 15, 0, 0, 40);
        service.addStopRule(projectId, cpiRule(10, 20), null);

        RuleEvaluation e = onlyRule(service.pacing(projectId, NOV, TODAY));

        assertThat(e.observed()).isNull();
        assertThat(e.tripped()).isFalse();
    }

    @Test
    void aRateRuleWithAZeroDenominatorHasNoObservedValueAndDoesNotTrip() {
        for (int d = 9; d <= 15; d++) {
            put("ADS", "spend", "", nov(d), 5);
            put("ADS", "impressions", "", nov(d), 0);
            put("ADS", "taps", "", nov(d), 0);
        }
        service.addStopRule(projectId, new StopRuleInput("ADS", null, "tap_through_rate", "LT", BigDecimal.valueOf(0.1),
                0, 7, null), null);
        service.addStopRule(projectId, new StopRuleInput("ADS", null, "install_rate", "LT", BigDecimal.valueOf(0.1),
                0, 7, null), null);

        List<RuleEvaluation> rules = adsOf(service.pacing(projectId, NOV, TODAY)).rules();

        assertThat(rules).hasSize(2).allSatisfy(r -> {
            assertThat(r.observed()).isNull();
            assertThat(r.tripped()).isFalse();
        });
    }

    @Test
    void aRuleOnAChannelWithoutSettledDataHasNoWindowAndDoesNotTrip() {
        putProvisional("ADS", "spend", "", nov(15), 100);
        service.addStopRule(projectId, cpiRule(10, 0), null);

        RuleEvaluation e = onlyRule(service.pacing(projectId, NOV, TODAY));

        assertThat(e.windowFrom()).isNull();
        assertThat(e.observed()).isNull();
        assertThat(e.sample()).isNull();
        assertThat(e.tripped()).isFalse();
    }

    @Test
    void aRuleWhoseSampleMetricIsNotReportedIsBelowAnyMinimumSample() {
        for (int d = 9; d <= 15; d++) {
            put("ADS", "spend", "", nov(d), 100);
            put("ADS", "installs", "", nov(d), 1);
        }
        service.addStopRule(projectId, cpiRule(10, 20), null);

        RuleEvaluation e = onlyRule(service.pacing(projectId, NOV, TODAY));

        assertThat(e.sample()).isNull();
        assertThat(e.belowMinSample()).isTrue();
        assertThat(e.tripped()).isFalse();
    }

    @Test
    void anInactiveRuleIsNeitherEvaluatedNorReturnedAsTripped() {
        seedWindow(9, 15, 50, 1, 100);
        MarketingStopRule rule = service.addStopRule(projectId, cpiRule(10, 20), null);
        assertThat(service.trippedRules(projectId, TODAY)).hasSize(1);

        service.removeStopRule(projectId, rule.getId());

        assertThat(service.trippedRules(projectId, TODAY)).isEmpty();
    }

    @Test
    void trippedRulesCarryWhatAnAlertNeeds() {
        seedWindow(9, 15, 20, 1, 100);
        MarketingStopRule rule = service.addStopRule(projectId, new StopRuleInput("ads", null, "cost_per_install", "gt",
                BigDecimal.valueOf(10), 20, 7, "Pause the ad group"), null);

        List<TrippedRule> tripped = service.trippedRules(projectId, TODAY);

        assertThat(tripped).hasSize(1);
        TrippedRule t = tripped.get(0);
        assertThat(t.ruleId()).isEqualTo(rule.getId());
        assertThat(t.channel()).isEqualTo("ADS");
        assertThat(t.scope()).isEqualTo("channel");
        assertThat(t.metric()).isEqualTo("cost_per_install");
        assertThat(t.comparator()).isEqualTo("GT");
        assertThat(t.threshold()).isEqualByComparingTo("10");
        assertThat(t.minSample()).isEqualTo(20);
        assertThat(t.windowDays()).isEqualTo(7);
        assertThat(t.note()).isEqualTo("Pause the ad group");
        assertThat(t.windowFrom()).isEqualTo(nov(9));
        assertThat(t.windowTo()).isEqualTo(nov(15));
        assertThat(t.observed()).isEqualTo(20.0);
        assertThat(t.sample()).isEqualTo(700.0);
    }

    @Test
    void trippedRulesAreScopedToTheProject() {
        seedWindow(9, 15, 20, 1, 100);
        String other = newProject();
        service.addStopRule(other, cpiRule(10, 0), null);

        assertThat(service.trippedRules(other, TODAY)).isEmpty();
    }

    // ---- marginal cost per install ----------------------------------------------------------------

    @Test
    void marginalCpiComparesTheLastSevenSettledDaysWithThePriorSevenAndMonthToDate() {
        seedWindow(2, 8, 10, 1, 10); // prior 7: spend 70, installs 7, CPI 10
        seedWindow(9, 15, 10, 2, 10); // last 7: spend 70, installs 14, CPI 5

        var m = adsOf(service.pacing(projectId, NOV, TODAY)).marginalCpi();

        assertThat(m.last7().from()).isEqualTo(nov(9));
        assertThat(m.last7().to()).isEqualTo(nov(15));
        assertThat(m.last7().spend()).isEqualTo(70.0);
        assertThat(m.last7().installs()).isEqualTo(14.0);
        assertThat(m.last7().cpi()).isEqualTo(5.0);
        assertThat(m.prior7().from()).isEqualTo(nov(2));
        assertThat(m.prior7().to()).isEqualTo(nov(8));
        assertThat(m.prior7().spend()).isEqualTo(70.0);
        assertThat(m.prior7().installs()).isEqualTo(7.0);
        assertThat(m.prior7().cpi()).isEqualTo(10.0);
        assertThat(m.monthToDate().from()).isEqualTo(nov(1));
        assertThat(m.monthToDate().to()).isEqualTo(nov(15));
        assertThat(m.monthToDate().spend()).isEqualTo(140.0);
        assertThat(m.monthToDate().installs()).isEqualTo(21.0);
        assertThat(m.monthToDate().cpi()).isEqualTo(6.67);
        assertThat(adsOf(service.pacing(projectId, NOV, TODAY)).cpi()).isEqualTo(6.67);
    }

    @Test
    void marginalCpiIsNullWhenAWindowHasZeroInstalls() {
        seedWindow(2, 8, 10, 1, 10);
        seedWindow(9, 15, 10, 0, 10);

        var m = adsOf(service.pacing(projectId, NOV, TODAY)).marginalCpi();

        assertThat(m.last7().spend()).isEqualTo(70.0);
        assertThat(m.last7().installs()).isZero();
        assertThat(m.last7().cpi()).isNull();
        assertThat(m.prior7().cpi()).isEqualTo(10.0);
    }

    @Test
    void marginalCpiHasNoFiguresForAWindowWithNoRows() {
        seedWindow(9, 15, 10, 2, 10);

        CpiWindow prior = adsOf(service.pacing(projectId, NOV, TODAY)).marginalCpi().prior7();

        assertThat(prior.spend()).isNull();
        assertThat(prior.installs()).isNull();
        assertThat(prior.cpi()).isNull();
    }

    @Test
    void marginalCpiIsNullWhenInstallsAreNotReportedAtAll() {
        seedAdsSpend(1, 15, 10);

        var m = adsOf(service.pacing(projectId, NOV, TODAY)).marginalCpi();

        assertThat(m.last7().spend()).isEqualTo(70.0);
        assertThat(m.last7().installs()).isNull();
        assertThat(m.last7().cpi()).isNull();
        assertThat(adsOf(service.pacing(projectId, NOV, TODAY)).installs()).isNull();
        assertThat(adsOf(service.pacing(projectId, NOV, TODAY)).cpi()).isNull();
    }

    @Test
    void marginalWindowsEndOnTheLastSettledDayNotOnToday() {
        seedWindow(1, 11, 10, 1, 10);
        putProvisional("ADS", "spend", "", nov(12), 500);
        putProvisional("ADS", "installs", "", nov(12), 1);

        var m = adsOf(service.pacing(projectId, NOV, TODAY)).marginalCpi();

        assertThat(m.last7().to()).isEqualTo(nov(11));
        assertThat(m.last7().from()).isEqualTo(nov(5));
        assertThat(m.last7().cpi()).isEqualTo(10.0);
    }

    // ---- budget and rule validation ---------------------------------------------------------------

    @Test
    void settingTheBudgetTwiceForAMonthReplacesTheCap() {
        service.setBudget(projectId, "2026-11", BigDecimal.valueOf(160), "USD", "u1");
        MarketingBudget second = service.setBudget(projectId, "2026-11", new BigDecimal("200.50"), "eur", "u2");

        assertThat(second.getCapAmount()).isEqualByComparingTo("200.50");
        assertThat(second.getCurrency()).isEqualTo("EUR");
        assertThat(second.getUpdatedBy()).isEqualTo("u2");
        assertThat(service.pacing(projectId, NOV, TODAY).capAmount()).isEqualTo(200.5);
        assertThat(service.pacing(projectId, NOV, TODAY).currency()).isEqualTo("EUR");
    }

    @Test
    void budgetCurrencyDefaultsToUsd() {
        assertThat(service.setBudget(projectId, "2026-11", BigDecimal.valueOf(160), null, null).getCurrency())
                .isEqualTo("USD");
    }

    @Test
    void aBudgetCapMustBeAPositiveAmountAndNeverZero() {
        assertThatThrownBy(() -> service.setBudget(projectId, "2026-11", BigDecimal.ZERO, "USD", null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.setBudget(projectId, "2026-11", BigDecimal.valueOf(-5), "USD", null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.setBudget(projectId, "2026-11", null, "USD", null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.setBudget(projectId, "2026-11", BigDecimal.valueOf(1e12), "USD", null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.setBudget(projectId, "2026-11", BigDecimal.TEN, "DOLLARS", null))
                .isInstanceOf(BusinessException.class);
        assertThat(service.pacing(projectId, NOV, TODAY).capAmount()).isNull();
    }

    @Test
    void aMonthMustBeFourDigitYearDashTwoDigitMonth() {
        assertThat(BudgetPacingService.parseMonth("2026-11")).isEqualTo(NOV);
        assertThat(BudgetPacingService.parseMonth(null)).isEqualTo(YearMonth.now(java.time.ZoneOffset.UTC));
        assertThat(BudgetPacingService.parseMonth(" ")).isEqualTo(YearMonth.now(java.time.ZoneOffset.UTC));
        for (String bad : List.of("2026-13", "2026-1", "26-11", "2026/11", "November")) {
            assertThatThrownBy(() -> BudgetPacingService.parseMonth(bad))
                    .as(bad).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> service.setBudget(projectId, "2026-13", BigDecimal.TEN, "USD", null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aStopRuleIsValidatedAndNormalised() {
        MarketingStopRule saved = service.addStopRule(projectId, new StopRuleInput(" ads ", " ", "spend", "lt",
                BigDecimal.valueOf(5), null, null, "  "), "u1");

        assertThat(saved.getChannel()).isEqualTo("ADS");
        assertThat(saved.getScope()).isEqualTo("channel");
        assertThat(saved.getComparator()).isEqualTo("LT");
        assertThat(saved.getMinSample()).isZero();
        assertThat(saved.getWindowDays()).isEqualTo(7);
        assertThat(saved.getNote()).isNull();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getCreatedBy()).isEqualTo("u1");

        for (StopRuleInput bad : List.of(
                new StopRuleInput("NOPE", null, "spend", "GT", BigDecimal.ONE, null, null, null),
                new StopRuleInput(null, null, "spend", "GT", BigDecimal.ONE, null, null, null),
                new StopRuleInput("ADS", null, "roas", "GT", BigDecimal.ONE, null, null, null),
                new StopRuleInput("ADS", null, "spend", "EQ", BigDecimal.ONE, null, null, null),
                new StopRuleInput("ADS", null, "spend", "GT", null, null, null, null),
                new StopRuleInput("ADS", null, "spend", "GT", BigDecimal.valueOf(-1), null, null, null),
                new StopRuleInput("ADS", null, "spend", "GT", BigDecimal.ONE, -1, null, null),
                new StopRuleInput("ADS", null, "spend", "GT", BigDecimal.ONE, null, 0, null),
                new StopRuleInput("ADS", null, "spend", "GT", BigDecimal.ONE, null, 91, null),
                new StopRuleInput("ADS", "adgroup:5", "spend", "GT", BigDecimal.ONE, null, null, null),
                new StopRuleInput("ADS", "keyword:", "spend", "GT", BigDecimal.ONE, null, null, null),
                new StopRuleInput("ADS", "keyword:a b", "spend", "GT", BigDecimal.ONE, null, null, null),
                new StopRuleInput("ADS", null, "spend", "GT", BigDecimal.ONE, null, null, "x".repeat(501)))) {
            assertThatThrownBy(() -> service.addStopRule(projectId, bad, null))
                    .as(bad.toString()).isInstanceOf(BusinessException.class);
        }
        assertThat(service.listStopRules(projectId)).hasSize(1);
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private static LocalDate nov(int day) {
        return LocalDate.of(2026, 11, day);
    }

    private String newProject() {
        User u = new User();
        u.setFirebaseUid("budget-uid-" + UUID.randomUUID());
        u.setEmail(UUID.randomUUID() + "@example.com");
        u.setName("Budget Test");
        User user = userRepository.save(u);
        Project p = new Project();
        p.setName("Budget Test Project");
        p.setKey("BG" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(user);
        return projectRepository.save(p).getId();
    }

    private String newConnection(String project) {
        Connection connection = new Connection();
        connection.setProjectId(project);
        connection.setConnectorId("apple-search-ads");
        connection.setDisplayLabel("Apple Ads account");
        connection.setAuthType("OAUTH2");
        connection.setStatus("ACTIVE");
        connection.setConfigJson("{}");
        connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        return connectionRepository.saveAndFlush(connection).getId();
    }

    private void put(String channel, String metric, String dimension, LocalDate day, double value) {
        upsert(channel, metric, dimension, day, value, false);
    }

    private void putProvisional(String channel, String metric, String dimension, LocalDate day, double value) {
        upsert(channel, metric, dimension, day, value, true);
    }

    private void upsert(String channel, String metric, String dimension, LocalDate day, double value,
                        boolean provisional) {
        dailyRepository.upsert(UUID.randomUUID().toString(), projectId, connectionId, "apple-search-ads", channel,
                metric, dimension, day, BigDecimal.valueOf(value), provisional);
    }

    private void seedAdsSpend(int fromDay, int toDay, double spendPerDay) {
        for (int d = fromDay; d <= toDay; d++) {
            put("ADS", "spend", "", nov(d), spendPerDay);
        }
    }

    /** Settled ADS total rows for each day: spend, installs and taps per day. */
    private void seedWindow(int fromDay, int toDay, double spend, double installs, double taps) {
        for (int d = fromDay; d <= toDay; d++) {
            put("ADS", "spend", "", nov(d), spend);
            put("ADS", "installs", "", nov(d), installs);
            put("ADS", "taps", "", nov(d), taps);
        }
    }

    /** Spreads {@code totalTaps} over days 9..15 (the 7-day window ending today), restating the day rows. */
    private void seedTapsAcrossWindow(int totalTaps) {
        for (int i = 0; i < 7; i++) {
            int share = totalTaps / 7 + (i < totalTaps % 7 ? 1 : 0);
            put("ADS", "taps", "", nov(9 + i), share);
        }
    }

    private void seedDimension(String dimension, int fromDay, int toDay, double spend, double installs, double taps) {
        for (int d = fromDay; d <= toDay; d++) {
            put("ADS", "spend", dimension, nov(d), spend);
            put("ADS", "installs", dimension, nov(d), installs);
            put("ADS", "taps", dimension, nov(d), taps);
        }
    }

    private static StopRuleInput cpiRule(double threshold, int minSample) {
        return new StopRuleInput("ADS", null, "cost_per_install", "GT", BigDecimal.valueOf(threshold), minSample, 7,
                null);
    }

    private static StopRuleInput scoped(String scope, double threshold, int minSample) {
        return new StopRuleInput("ADS", scope, "cost_per_install", "GT", BigDecimal.valueOf(threshold), minSample, 7,
                null);
    }

    private static ChannelPacing adsOf(Pacing pacing) {
        return pacing.channels().stream().filter(c -> c.channel().equals("ADS")).findFirst().orElseThrow();
    }

    private static RuleEvaluation onlyRule(Pacing pacing) {
        List<RuleEvaluation> rules = adsOf(pacing).rules();
        assertThat(rules).hasSize(1);
        return rules.get(0);
    }

    private static RuleEvaluation ruleAt(List<RuleEvaluation> rules, String scope) {
        return rules.stream().filter(r -> r.scope().equals(scope)).findFirst().orElseThrow();
    }
}
