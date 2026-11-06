package com.conductor.marketing.report;

import com.conductor.entity.WorkItem;
import com.conductor.integration.ingest.ChannelDimensionStateStore;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.marketing.report.MarketingReportService.AcquisitionRow;
import com.conductor.marketing.report.MarketingReportService.Benchmark;
import com.conductor.marketing.report.MarketingReportService.Report;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ChannelMetricDefinitionRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.conductor.marketing.report.MarketingReportFixture.REF_DAYS;
import static com.conductor.marketing.report.MarketingReportFixture.REF_FROM;
import static com.conductor.marketing.report.MarketingReportFixture.REF_NOW;
import static com.conductor.marketing.report.MarketingReportFixture.REF_TO;
import static com.conductor.marketing.report.MarketingReportFixture.REF_TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pins the RE-99 acceptance numbers for the reference window 2026-09-29..2026-10-26 (28 days), verified against each
 * platform on 2026-10-29: Apple Ads 23 tap installs (+1 view-through) for USD 162.86, organic search 6 first-time
 * downloads and 3,696 impressions, search conversion 33 / 5,639 = 0.59%, Sales Reports 30 downloads on 28 of 28 days.
 * The rows are seeded into {@code channel_metric_daily} and read back through the real overview service.
 */
@Transactional
class MarketingReportReferenceWindowTest extends AbstractNoneWebIntegrationTest {

    private static final String ASC = "app-store-connect";
    private static final String ASA = "apple-search-ads";
    private static final String SEARCH = "source:APP_STORE_SEARCH";

    @Autowired private MarketingReportService service;
    @Autowired private MarketingBenchmarkRepository benchmarkRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ConnectorFeedRepository feedRepository;
    @Autowired private ChannelMetricDailyRepository dailyRepository;
    @Autowired private ChannelMetricDefinitionRepository definitionRepository;
    @Autowired private ChannelDimensionStateStore stateStore;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private JdbcTemplate jdbc;

    private MarketingReportFixture f;

    @BeforeEach
    void setUp() {
        f = new MarketingReportFixture(userRepository, projectRepository, connectionRepository, feedRepository,
                dailyRepository, definitionRepository, stateStore, workItemRepository);
    }

    private String connect(String connectorId) {
        return f.connect(connectorId, REF_NOW.minusHours(1));
    }

    private Report report() {
        return service.report(f.project.getId(), REF_DAYS, REF_TODAY, REF_NOW);
    }

    private Report report(int days) {
        return service.report(f.project.getId(), days, REF_TODAY, REF_NOW);
    }

    private static AcquisitionRow row(Report report, String key) {
        return report.acquisition().stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow();
    }

    private static Benchmark benchmarkOf(Report report, String metricRef) {
        return report.benchmarks().stream().filter(b -> b.metricRef().equals(metricRef)).findFirst().orElseThrow();
    }

    private String referenceStoreAndAds() {
        String store = connect(ASC);
        String ads = connect(ASA);
        f.seedReferenceStore(store, 30);
        f.seedReferenceAds(ads);
        return store;
    }

    private void benchmark(String ref, double value, String numerator, String denominator, String sourceType) {
        MarketingBenchmark b = new MarketingBenchmark();
        b.setProjectId(f.project.getId());
        b.setMetricRef(ref);
        b.setValue(BigDecimal.valueOf(value));
        b.setSourceName("Example Study");
        b.setSourceUrl("https://example.com/study");
        b.setCheckedOn(REF_TODAY.minusDays(3));
        b.setNumerator(numerator);
        b.setDenominator(denominator);
        b.setSourceType(sourceType);
        benchmarkRepository.saveAndFlush(b);
    }

    // ---- P0-1: Apple Ads outputs -----------------------------------------------------------------

    @Test
    void theAppleAdsRowReadsTapInstallsAndShowsViewThroughSeparately() {
        referenceStoreAndAds();

        Report report = report();
        AcquisitionRow ads = row(report, "apple_ads");

        assertThat(report.window().from()).isEqualTo(REF_FROM);
        assertThat(report.window().to()).isEqualTo(REF_TO);
        assertThat(ads.installs()).isEqualTo(23.0);
        assertThat(ads.viewThroughInstalls()).isEqualTo(1.0);
        assertThat(ads.cost()).isCloseTo(162.86, within(0.01));
        assertThat(ads.reach()).isEqualTo(3405.0);
        assertThat(ads.actions()).isEqualTo(89.0);
    }

    @Test
    void costPerInstallIsSpendOverTapInstallsNotOverRedownloadsOrViews() {
        referenceStoreAndAds();

        MarketingReportService.Metric cpi = report().headline().stream()
                .filter(m -> m.key().equals("cost_per_install")).findFirst().orElseThrow();

        assertThat(cpi.value()).isCloseTo(7.08, within(7.08 * 0.02));
    }

    @Test
    void viewThroughInstallsAreNullWhenTheAdsFeedReportsNone() {
        String ads = connect(ASA);
        f.putSpread(ads, ASA, "ADS", "spend", "", 10, 2);
        f.putSpread(ads, ASA, "ADS", "installs", "", 2, 0);

        assertThat(row(report(), "apple_ads").viewThroughInstalls()).isNull();
    }

    @Test
    void sourcesStateTheAdsDayBoundaryAndTheSearchTermZone() {
        referenceStoreAndAds();

        Map<String, MarketingReportService.Source> byChannel = new java.util.HashMap<>();
        report().sources().forEach(s -> byChannel.put(s.channel(), s));

        assertThat(byChannel.get("ADS").timeZone()).isEqualTo("UTC");
        assertThat(byChannel.get("ADS").searchTermTimeZone()).isEqualTo("ORTZ");
        assertThat(byChannel.get("STORE").timeZone()).isNull();
        assertThat(byChannel.get("STORE").searchTermTimeZone()).isNull();
    }

    @Test
    void keywordsCarryTapInstallsFirstTimeDownloadsAndCostPerTapInstall() {
        referenceStoreAndAds();

        MarketingReportService.Keyword rexipe = report().keywords().stream()
                .filter(k -> k.keywordId().equals("11")).findFirst().orElseThrow();

        assertThat(rexipe.text()).isEqualTo("rexipe");
        assertThat(rexipe.campaign()).isEqualTo("Brand");
        assertThat(rexipe.installs()).isEqualTo(15.0);
        assertThat(rexipe.newDownloads()).isEqualTo(15.0);
        assertThat(rexipe.spend()).isCloseTo(60.0, within(0.001));
        assertThat(rexipe.cpi()).isCloseTo(4.0, within(0.001));
    }

    @Test
    void adsCoverageShowsKeywordAndSearchTermSpendPerCampaign() {
        referenceStoreAndAds();

        List<MarketingReportService.AdsCoverage> coverage = report().adsCoverage();

        assertThat(coverage).extracting(MarketingReportService.AdsCoverage::name)
                .containsExactly("Brand", "Generic", "Competitor", "Discovery");
        MarketingReportService.AdsCoverage brand = coverage.get(0);
        assertThat(brand.campaignId()).isEqualTo("1");
        assertThat(brand.spend()).isCloseTo(100.0, within(0.001));
        assertThat(brand.keywordSpend()).isCloseTo(98.0, within(0.001));
        assertThat(brand.keywordCoveragePct()).isCloseTo(0.98, within(0.0001));
        assertThat(brand.searchTermSpend()).isEqualTo(0.0);
        assertThat(brand.rows()).isEqualTo(2);
        assertThat(coverage.get(1).keywordCoveragePct()).isCloseTo(1.0, within(0.0001));
        MarketingReportService.AdsCoverage discovery = coverage.get(3);
        assertThat(discovery.keywordSpend()).isEqualTo(0.0);
        assertThat(discovery.searchTermSpend()).isCloseTo(7.86, within(0.001));
        assertThat(discovery.keywordCoveragePct()).isEqualTo(0.0);
        assertThat(discovery.rows()).isEqualTo(1);
    }

    @Test
    void aCampaignWithNoSpendHasNoCoveragePercentNotZero() {
        String ads = connect(ASA);
        f.putSpread(ads, ASA, "ADS", "spend", "", 0, 2);
        f.putSpread(ads, ASA, "ADS", "impressions", "campaign:9:Paused", 40, 0);
        f.putSpread(ads, ASA, "ADS", "spend", "campaign:9:Paused", 0, 2);

        MarketingReportService.AdsCoverage paused = report().adsCoverage().get(0);

        assertThat(paused.name()).isEqualTo("Paused");
        assertThat(paused.spend()).isEqualTo(0.0);
        assertThat(paused.keywordCoveragePct()).isNull();
    }

    @Test
    void adsCoverageIsEmptyWhenAdsIsNotConnected() {
        f.seedReferenceStore(connect(ASC), 30);

        assertThat(report().adsCoverage()).isEmpty();
    }

    // ---- P0-3: organic search on the same settled days ---------------------------------------------

    @Test
    void organicSearchIsSixDownloadsAndThreeThousandSixHundredNinetySixImpressions() {
        referenceStoreAndAds();

        AcquisitionRow organic = row(report(), "app_store_search");

        assertThat(organic.installs()).isEqualTo(6.0);
        assertThat(organic.reach()).isEqualTo(3696.0);
        assertThat(organic.reachLabel()).isEqualTo("Impressions (organic)");
        assertThat(organic.derived()).isTrue();
        MarketingReportService.Derivation d = organic.derivation();
        assertThat(d.from()).isEqualTo(REF_FROM);
        assertThat(d.to()).isEqualTo(REF_TO);
        assertThat(d.days()).isEqualTo(28);
        assertThat(d.grossInstalls()).isEqualTo(29.0);
        assertThat(d.paidInstalls()).isEqualTo(23.0);
        assertThat(d.grossReach()).isEqualTo(7101.0);
        assertThat(d.paidReach()).isEqualTo(3405.0);
        assertThat(organic.note()).isEqualTo("App Store search first-time downloads minus Apple Ads first-time "
                + "downloads, and search impressions minus Apple Ads impressions, over the 28 days both have "
                + "settled (2026-09-29–2026-10-26), floored at 0.");
    }

    @Test
    void organicSearchWaitsForAdsFirstTimeDownloadsWhenAdsHasDaysButNoneOfThem() {
        String store = connect(ASC);
        String ads = connect(ASA);
        f.seedReferenceStore(store, 30);
        f.putSpread(ads, ASA, "ADS", "spend", "", 162.86, 2);
        f.putSpread(ads, ASA, "ADS", "impressions", "", 3405, 0);
        f.putSpread(ads, ASA, "ADS", "installs", "", 23, 0);

        AcquisitionRow organic = row(report(), "app_store_search");

        assertThat(organic.installs()).isNull();
        assertThat(organic.reach()).isNull();
        assertThat(organic.derived()).isFalse();
        assertThat(organic.derivation()).isNull();
        assertThat(organic.note()).isEqualTo("Waiting for Apple Ads first-time downloads for these days");
    }

    @Test
    void organicSearchUsesOnlyTheDaysBothSourcesHaveSettled() {
        String store = connect(ASC);
        String ads = connect(ASA);
        f.seedReferenceStore(store, 30);
        // ads report every day but first-time downloads for only the first 20 days (2 a day)
        f.putSpread(ads, ASA, "ADS", "spend", "", 162.86, 2);
        f.putSpread(ads, ASA, "ADS", "impressions", "", 3405, 0);
        f.putSpread(ads, ASA, "ADS", "installs", "", 23, 0);
        for (int i = 0; i < 20; i++) {
            f.put(ads, ASA, "ADS", "new_downloads", "", REF_FROM.plusDays(i), 2);
        }
        double[] search = MarketingReportFixture.spread(29, REF_DAYS, 0);
        double[] impressions = MarketingReportFixture.spread(7101, REF_DAYS, 0);
        double[] adImpressions = MarketingReportFixture.spread(3405, REF_DAYS, 0);
        double grossDownloads = 0;
        double grossImpressions = 0;
        double paidImpressions = 0;
        for (int i = 0; i < 20; i++) {
            grossDownloads += search[i];
            grossImpressions += impressions[i];
            paidImpressions += adImpressions[i];
        }

        AcquisitionRow organic = row(report(), "app_store_search");

        assertThat(organic.derivation().days()).isEqualTo(20);
        assertThat(organic.derivation().from()).isEqualTo(REF_FROM);
        assertThat(organic.derivation().to()).isEqualTo(REF_FROM.plusDays(19));
        assertThat(organic.derivation().grossInstalls()).isEqualTo(grossDownloads);
        assertThat(organic.derivation().paidInstalls()).isEqualTo(40.0);
        assertThat(organic.installs()).isEqualTo(Math.max(0, grossDownloads - 40));
        assertThat(organic.reach()).isEqualTo(grossImpressions - paidImpressions);
        assertThat(organic.note()).contains("over the 20 days both have settled");
    }

    @Test
    void organicSearchIsUnknownWhenNoDayHasBothSourcesSettled() {
        String store = connect(ASC);
        String ads = connect(ASA);
        f.putSpread(store, ASC, "STORE", "impressions", "", 9000, 0);
        f.putSpread(ads, ASA, "ADS", "spend", "", 162.86, 2);
        // search downloads only for the first 14 days, ads first-time downloads only for the last 14
        for (int i = 0; i < 14; i++) {
            f.put(store, ASC, "STORE", "downloads", SEARCH, REF_FROM.plusDays(i), 2);
            f.put(ads, ASA, "ADS", "new_downloads", "", REF_FROM.plusDays(14 + i), 1);
        }

        AcquisitionRow organic = row(report(), "app_store_search");

        assertThat(organic.installs()).isNull();
        assertThat(organic.reach()).isNull();
        assertThat(organic.derived()).isFalse();
        assertThat(organic.derivation()).isNull();
        assertThat(organic.note()).isEqualTo(MarketingReportService.NO_SHARED_DAYS_NOTE);
    }

    @Test
    void organicImpressionsAreUnknownWhenASourceLacksThemForSomeOfTheDays() {
        String store = connect(ASC);
        String ads = connect(ASA);
        f.seedReferenceStore(store, 30);
        f.putSpread(ads, ASA, "ADS", "spend", "", 162.86, 2);
        f.putSpread(ads, ASA, "ADS", "new_downloads", "", 23, 0);
        for (int i = 5; i < REF_DAYS; i++) {
            f.put(ads, ASA, "ADS", "impressions", "", REF_FROM.plusDays(i), 100);
        }

        AcquisitionRow organic = row(report(), "app_store_search");

        assertThat(organic.installs()).isEqualTo(6.0);
        assertThat(organic.reach()).isNull();
        assertThat(organic.derivation().days()).isEqualTo(28);
        assertThat(organic.derivation().grossReach()).isNull();
        assertThat(organic.derivation().paidReach()).isNull();
        assertThat(organic.note()).contains("Impressions are missing for some of these days");
    }

    @Test
    void organicIsFlooredAfterSummingTheDaysNeverPerDay() {
        String store = connect(ASC);
        String ads = connect(ASA);
        // seven days: search downloads 1, 9, then 2 a day; ads first-time downloads 5, 1, then 1 a day
        double[] searchDownloads = {1, 9, 2, 2, 2, 2, 2};
        double[] paidDownloads = {5, 1, 1, 1, 1, 1, 1};
        for (int i = 0; i < 7; i++) {
            LocalDate day = REF_TO.minusDays(6 - i);
            f.put(store, ASC, "STORE", "downloads", SEARCH, day, searchDownloads[i]);
            f.put(store, ASC, "STORE", "impressions", SEARCH, day, 100);
            f.put(ads, ASA, "ADS", "new_downloads", "", day, paidDownloads[i]);
            f.put(ads, ASA, "ADS", "impressions", "", day, 150);
            f.put(ads, ASA, "ADS", "spend", "", day, 1);
        }

        AcquisitionRow organic = row(report(7), "app_store_search");

        // 20 search - 11 paid = 9; flooring day by day would give 13
        assertThat(organic.installs()).isEqualTo(9.0);
        // 700 search impressions - 1,050 paid impressions: floored at 0 once, after summing
        assertThat(organic.reach()).isEqualTo(0.0);
    }

    @Test
    void withoutAdsTheStoreSearchFiguresAreShownGrossWithTheOldNote() {
        f.seedReferenceStore(connect(ASC), 30);

        AcquisitionRow search = row(report(), "app_store_search");

        assertThat(search.installs()).isEqualTo(29.0);
        assertThat(search.reach()).isEqualTo(7101.0);
        assertThat(search.derived()).isFalse();
        assertThat(search.derivation()).isNull();
        assertThat(search.note()).isEqualTo("Apple Ads is not reporting, so any paid installs are counted here.");
    }

    // ---- P0-4: benchmarks compared on a matching definition ----------------------------------------

    @Test
    void anAppFollowSearchBenchmarkIsJudgedAgainstSearchConversionNotAllSourceConversion() {
        referenceStoreAndAds();
        benchmark("STORE.conversion_rate", 0.124, "total_downloads", "unique_impressions", "APP_STORE_SEARCH");

        Report report = report();
        Benchmark b = benchmarkOf(report, "STORE.conversion_rate");

        assertThat(b.comparedMetricRef()).isEqualTo("STORE.conversion_rate_search");
        assertThat(b.value()).isCloseTo(33.0 / 5639, within(1e-9));
        assertThat(b.comparable()).isTrue();
        assertThat(b.reason()).isNull();
        assertThat(b.judged()).isTrue();
        assertThat(b.ratio()).isCloseTo((33.0 / 5639) / 0.124, within(1e-9));
        assertThat(b.numerator()).isEqualTo("total_downloads");
        assertThat(b.denominator()).isEqualTo("unique_impressions");
        assertThat(b.sourceType()).isEqualTo("APP_STORE_SEARCH");
        assertThat(b.metricDefinition().numerator()).isEqualTo("total_downloads");
        assertThat(b.metricDefinition().sourceType()).isEqualTo("APP_STORE_SEARCH");
        // the all-source rate is still its own number: 30 / 5,652
        MarketingReportService.ScorecardRow all = report.scorecard().stream()
                .filter(r -> r.key().equals("conversion_rate")).findFirst().orElseThrow();
        assertThat(all.value()).isCloseTo(30.0 / 5652, within(1e-9));
    }

    @Test
    void aBenchmarkWithADifferentDenominatorIsNotJudgedAndTheReasonNamesBothDefinitions() {
        referenceStoreAndAds();
        benchmark("STORE.conversion_rate", 0.05, "first_time_downloads", "page_views", null);

        Benchmark b = benchmarkOf(report(), "STORE.conversion_rate");

        assertThat(b.comparable()).isFalse();
        assertThat(b.judged()).isFalse();
        assertThat(b.reason()).contains("first-time downloads / page views (all sources)")
                .contains("first-time downloads / unique impressions (all sources)");
        assertThat(b.comparedMetricRef()).isEqualTo("STORE.conversion_rate");
        assertThat(b.value()).isCloseTo(30.0 / 5652, within(1e-9));
    }

    @Test
    void aBenchmarkWithoutADefinitionIsShownButNeverJudged() {
        referenceStoreAndAds();
        benchmark("ADS.spend", 100, null, null, null);

        Benchmark b = benchmarkOf(report(), "ADS.spend");

        assertThat(b.comparable()).isFalse();
        assertThat(b.reason()).isEqualTo("definition missing");
        assertThat(b.judged()).isFalse();
        assertThat(b.value()).isCloseTo(162.86, within(0.01));
        assertThat(b.numerator()).isNull();
    }

    @Test
    void aBenchmarkWhoseDefinitionMatchesTheMetricIsJudged() {
        referenceStoreAndAds();
        benchmark("ADS.cost_per_install", 8.0, "spend", "tap_installs", "ALL");

        Benchmark b = benchmarkOf(report(), "ADS.cost_per_install");

        assertThat(b.comparable()).isTrue();
        assertThat(b.comparedMetricRef()).isEqualTo("ADS.cost_per_install");
        assertThat(b.judged()).isTrue();
        assertThat(b.value()).isCloseTo(7.08, within(0.02 * 7.08));
    }

    @Test
    void aBenchmarkForAMetricTheReportDoesNotHaveHasNoValueAndIsNotJudged() {
        referenceStoreAndAds();
        benchmark("STORE.not_a_metric", 1, "total_downloads", null, null);

        Benchmark b = benchmarkOf(report(), "STORE.not_a_metric");

        assertThat(b.value()).isNull();
        assertThat(b.judged()).isFalse();
        assertThat(b.comparable()).isFalse();
        assertThat(b.reason()).isNotBlank();
    }

    // ---- P0-5.2: definitions -------------------------------------------------------------------------

    @Test
    void everyMetricTheReportMentionsCarriesItsDefinition() {
        referenceStoreAndAds();
        benchmark("STORE.conversion_rate", 0.124, "total_downloads", "unique_impressions", "APP_STORE_SEARCH");

        Report report = report();

        assertThat(report.headline()).isNotEmpty().allSatisfy(m -> assertThat(m.definition()).isNotNull());
        assertThat(report.scorecard()).isNotEmpty().allSatisfy(r -> assertThat(r.definition()).isNotNull());
        assertThat(report.definitions().get("ADS.installs").numerator()).isEqualTo("tap_installs");
        assertThat(report.definitions().get("ADS.view_installs").numerator()).isEqualTo("view_through_installs");
        assertThat(report.definitions().get("ADS.new_downloads").numerator()).isEqualTo("tap_new_downloads");
        assertThat(report.definitions().get("ADS.spend").timeZone()).isEqualTo("UTC");
        assertThat(report.definitions().get("STORE.conversion_rate_search").sourceType()).isEqualTo("APP_STORE_SEARCH");
        assertThat(report.definitions().get("STORE.downloads").numerator()).isEqualTo("first_time_downloads");
        assertThat(report.definitions()).containsKeys("ADS.cost_per_install", "STORE.conversion_rate");
        // every scorecard row and headline metric is reachable by its CHANNEL.key
        report.scorecard().forEach(r -> assertThat(report.definitions())
                .containsKey(r.source().channel() + "." + r.key()));
        report.headline().forEach(m -> assertThat(report.definitions()).containsKey(m.channel() + "." + m.key()));
    }

    // ---- P1-1: change review and markers --------------------------------------------------------------

    private void reviewedBetween(WorkItem item, String completedAt, String updatedAt) {
        jdbc.update("UPDATE work_items SET completed_at = ?::timestamptz, updated_at = ?::timestamptz WHERE id = ?",
                completedAt, updatedAt, item.getId());
    }

    @Test
    void dueForReviewHoldsPlannedAndLiveChangesPastTheirDateWithNoVerdict() {
        f.change("planned past due", "PLANNED", REF_TODAY.minusDays(2));
        f.change("planned past due with verdict", "PLANNED", REF_TODAY.minusDays(2), Map.of("verdict", "worked"));
        f.change("live past due with verdict", "LIVE", REF_TODAY.minusDays(1), Map.of("verdict", "no_effect"));
        f.change("live past due", "LIVE", REF_TODAY.minusDays(1));
        f.change("live past due blank verdict", "LIVE", REF_TODAY.minusDays(5), Map.of("verdict", " "));
        f.change("planned in the future", "PLANNED", REF_TODAY.plusDays(3));
        f.change("planned undated", "PLANNED", null);

        MarketingReportService.Changes changes = report().changes();

        assertThat(changes.dueForReview()).extracting(MarketingReportService.Change::title)
                .containsExactlyInAnyOrder("planned past due", "live past due", "live past due blank verdict");
        assertThat(changes.open()).hasSize(7);
    }

    @Test
    void reviewedInWindowReadsCompletedAtAndFallsBackToUpdatedAt() {
        WorkItem completedInside = f.change("completed inside", "REVIEWED", null);
        WorkItem completedOutside = f.change("completed outside, edited inside", "REVIEWED", null);
        WorkItem noCompletion = f.change("no completion time", "REVIEWED", null);
        reviewedBetween(completedInside, "2026-10-10T12:00:00Z", "2026-01-01T00:00:00Z");
        reviewedBetween(completedOutside, "2026-01-01T00:00:00Z", "2026-10-10T12:00:00Z");
        jdbc.update("UPDATE work_items SET completed_at = NULL, updated_at = ?::timestamptz WHERE id = ?",
                "2026-10-12T12:00:00Z", noCompletion.getId());

        assertThat(report().changes().reviewedInWindow()).extracting(MarketingReportService.Change::title)
                .containsExactlyInAnyOrder("completed inside", "no completion time");
    }

    @Test
    void changeMarkersListTheDayEachLiveOrReviewedChangeWentLive() {
        WorkItem live = f.change("live in window", "LIVE", null, Map.of("liveSince", "2026-10-10"));
        WorkItem reviewed = f.change("reviewed, live before the window", "REVIEWED", null,
                Map.of("liveSince", "2026-09-01"));
        reviewedBetween(reviewed, "2026-10-15T12:00:00Z", "2026-10-15T12:00:00Z");
        f.change("live, odd date", "LIVE", null, Map.of("liveSince", "not a date"));
        f.change("planned with a date", "PLANNED", null, Map.of("liveSince", "2026-10-11"));
        f.change("live after the window", "LIVE", null, Map.of("liveSince", "2026-11-02"));
        f.change("live, no date", "LIVE", null);

        List<MarketingReportService.ChangeMarker> markers = report().changeMarkers();

        assertThat(markers).extracting(MarketingReportService.ChangeMarker::day)
                .containsExactly(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 10));
        assertThat(markers).extracting(MarketingReportService.ChangeMarker::title)
                .containsExactly("reviewed, live before the window", "live in window");
        MarketingReportService.ChangeMarker marker = markers.get(1);
        assertThat(marker.changeId()).isEqualTo(live.getId());
        assertThat(marker.displayId()).startsWith(f.project.getKey() + "-");
        assertThat(marker.status()).isEqualTo("LIVE");
    }

    // ---- P1-3: downloads of record ------------------------------------------------------------------

    private void seedSalesReports(String store, double downloads, int daysRead) {
        f.putSpread(store, ASC, "STORE", "downloads_of_record", "", downloads, 0);
        for (int i = 0; i < daysRead; i++) {
            f.put(store, ASC, "STORE", "sales_report_days", "", REF_FROM.plusDays(i), 1);
        }
    }

    private static MarketingReportService.FunnelStage stage(Report report, String key) {
        return report.funnel().stages().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void downloadsOfRecordReplaceAnalyticsDownloadsInTheFunnelWithCoverageShown() {
        String store = connect(ASC);
        f.seedReferenceStore(store, 27);
        seedSalesReports(store, 30, 28);

        Report report = report();

        assertThat(report.store().downloadsOfRecord()).isEqualTo(30.0);
        assertThat(report.store().analyticsDownloads()).isEqualTo(27.0);
        assertThat(report.store().coverage().daysCovered()).isEqualTo(28);
        assertThat(report.store().coverage().daysInWindow()).isEqualTo(28);
        MarketingReportService.FunnelStage downloads = stage(report, "store_downloads");
        assertThat(downloads.value()).isEqualTo(30.0);
        assertThat(downloads.coverage().daysCovered()).isEqualTo(28);
        assertThat(downloads.dropPct()).isCloseTo((500.0 - 30) / 500, within(1e-9));
        assertThat(downloads.note()).contains("Sales Reports").contains("28 of 28");
        assertThat(report.definitions()).containsKey("STORE.downloads_of_record");
        // the traffic-source split stays on Analytics: Sales Reports have none
        assertThat(row(report, "app_store_search").installs()).isEqualTo(29.0);
    }

    @Test
    void partialSalesReportCoverageIsStatedNotHidden() {
        String store = connect(ASC);
        f.seedReferenceStore(store, 27);
        seedSalesReports(store, 30, 20);

        Report report = report();

        assertThat(report.store().coverage().daysCovered()).isEqualTo(20);
        assertThat(report.store().coverage().daysInWindow()).isEqualTo(28);
        assertThat(stage(report, "store_downloads").note()).contains("20 of 28");
    }

    @Test
    void withoutSalesReportsTheFunnelKeepsAnalyticsDownloadsAndThereIsNoCoverage() {
        f.seedReferenceStore(connect(ASC), 27);

        Report report = report();

        assertThat(report.store().downloadsOfRecord()).isNull();
        assertThat(report.store().coverage()).isNull();
        assertThat(report.store().analyticsDownloads()).isEqualTo(27.0);
        assertThat(stage(report, "store_downloads").value()).isEqualTo(27.0);
        assertThat(stage(report, "store_downloads").coverage()).isNull();
    }

    @Test
    void salesReportsWithoutDayCountsHaveUnknownCoverageNotZero() {
        String store = connect(ASC);
        f.seedReferenceStore(store, 27);
        f.putSpread(store, ASC, "STORE", "downloads_of_record", "", 30, 0);

        Report report = report();

        assertThat(report.store().downloadsOfRecord()).isEqualTo(30.0);
        assertThat(report.store().coverage()).isNull();
    }

    @Test
    void thereIsNoStoreSummaryWhenTheStoreIsNotConnected() {
        connect(ASA);

        assertThat(report().store()).isNull();
    }

    // ---- P1-6: web conversion sessions and social likes -----------------------------------------------

    private void seedWeb(String web, boolean withSessions) {
        f.putSpread(web, "posthog", "WEB", "sessions", "", 68, 0);
        f.putSpread(web, "posthog", "WEB", "visitors", "", 60, 0);
        f.put(web, "posthog", "WEB", "visitors_unique_28d", "", REF_TO, 49);
        f.putSpread(web, "posthog", "WEB", "conversion_clicks", "", 20, 0);
        if (withSessions) {
            f.putSpread(web, "posthog", "WEB", "conversion_sessions", "", 12, 0);
        }
    }

    @Test
    void theWebRowAndFunnelCountSessionsWithAnAppStoreTapWhenThereAreAny() {
        f.seedReferenceStore(connect(ASC), 30);
        seedWeb(connect("posthog"), true);

        Report report = report();
        AcquisitionRow web = row(report, "web_referrer");

        assertThat(web.reach()).isEqualTo(49.0);
        assertThat(web.actions()).isEqualTo(12.0);
        assertThat(web.actionsLabel()).isEqualTo("Sessions with an App Store tap");
        MarketingReportService.FunnelStage stage = stage(report, "web_conversion_clicks");
        assertThat(stage.value()).isEqualTo(12.0);
        assertThat(stage.label()).isEqualTo("Sessions with an App Store tap");
        assertThat(stage.note()).contains("12 of 68 sessions");
        assertThat(report.definitions()).containsKey("WEB.conversion_sessions");
    }

    @Test
    void withoutConversionSessionsTheWebRowKeepsConversionClicks() {
        f.seedReferenceStore(connect(ASC), 30);
        seedWeb(connect("posthog"), false);

        Report report = report();

        assertThat(row(report, "web_referrer").actions()).isEqualTo(20.0);
        assertThat(row(report, "web_referrer").actionsLabel()).isEqualTo("Conversion clicks");
        assertThat(stage(report, "web_conversion_clicks").value()).isEqualTo(20.0);
        assertThat(stage(report, "web_conversion_clicks").note()).isNull();
    }

    @Test
    void theSocialRowCountsTikTokFollowersAndShowsLikesWhenTheyExist() {
        String social = connect("tiktok");
        f.put(social, "tiktok", "SOCIAL", "followers", "tiktok", REF_TO, 120);
        f.put(social, "tiktok", "SOCIAL", "likes", "tiktok", REF_TO, 340);

        AcquisitionRow row = row(report(), "social");

        assertThat(row.reach()).isEqualTo(120.0);
        assertThat(row.likes()).isEqualTo(340.0);
    }

    @Test
    void theSocialRowHasNoLikesWhenNoneAreReported() {
        String social = connect("meta");
        f.put(social, "meta", "SOCIAL", "followers", "instagram", REF_TO, 80);

        AcquisitionRow row = row(report(), "social");

        assertThat(row.reach()).isEqualTo(80.0);
        assertThat(row.likes()).isNull();
    }
}
