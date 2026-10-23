package com.conductor.marketing.report;

import com.conductor.entity.WorkItem;
import com.conductor.integration.ingest.ChannelDimensionStateStore;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.marketing.report.MarketingReportService.AcquisitionRow;
import com.conductor.marketing.report.MarketingReportService.Keyword;
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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@link MarketingReportService} against real Postgres: the report is assembled from seeded
 * {@code channel_metric_daily} rows through the overview service, so these tests pin the joins and the
 * differences the report adds (organic split, funnel drop, keyword outcomes, benchmark judging, change buckets).
 *
 * <p>"Today" is the real UTC day; data covers the seven days before it, so the 7-day window is exactly those.
 */
@Transactional
class MarketingReportServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);
    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

    @Autowired private MarketingReportService service;
    @Autowired private com.conductor.marketing.overview.MarketingOverviewQueryService overviewService;
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
    private String store;
    private String ads;
    private String product;

    @BeforeEach
    void setUp() {
        f = new MarketingReportFixture(userRepository, projectRepository, connectionRepository, feedRepository,
                dailyRepository, definitionRepository, stateStore, workItemRepository);
        store = f.connect("app-store-connect", NOW.minusHours(1));
        ads = f.connect("apple-search-ads", NOW.minusHours(1));
        product = f.connect("product-metrics", NOW.minusHours(1));
    }

    private static LocalDate ago(int days) {
        return TODAY.minusDays(days);
    }

    /** Seven settled days of store and ad data: 10 search + 2 browse downloads a day, {@code adInstalls} in total. */
    private void seedStoreAndAds(double adInstallsPerDay) {
        for (int d = 1; d <= 7; d++) {
            f.put(store, "app-store-connect", "STORE", "impressions", "", ago(d), 1000);
            f.put(store, "app-store-connect", "STORE", "impressions_unique", "", ago(d), 900);
            f.put(store, "app-store-connect", "STORE", "downloads", "", ago(d), 12);
            f.put(store, "app-store-connect", "STORE", "impressions", "source:APP_STORE_SEARCH", ago(d), 800);
            f.put(store, "app-store-connect", "STORE", "page_views", "source:APP_STORE_SEARCH", ago(d), 100);
            f.put(store, "app-store-connect", "STORE", "downloads", "source:APP_STORE_SEARCH", ago(d), 10);
            f.put(store, "app-store-connect", "STORE", "downloads", "source:APP_STORE_BROWSE", ago(d), 2);
            f.put(ads, "apple-search-ads", "ADS", "spend", "", ago(d), 5);
            f.put(ads, "apple-search-ads", "ADS", "installs", "", ago(d), adInstallsPerDay);
            f.put(ads, "apple-search-ads", "ADS", "taps", "", ago(d), 20);
            f.put(ads, "apple-search-ads", "ADS", "impressions", "", ago(d), 500);
        }
    }

    private Report report() {
        return service.report(f.project.getId(), 7, TODAY, NOW);
    }

    private static AcquisitionRow row(Report report, String key) {
        return report.acquisition().stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void organicSearchIsStoreSearchDownloadsMinusAdInstallsAndIsFlaggedDerived() {
        seedStoreAndAds(4); // 28 paid installs over the window; 70 search downloads

        AcquisitionRow organic = row(report(), "app_store_search");

        assertThat(organic.installs()).isEqualTo(42.0);
        assertThat(organic.derived()).isTrue();
        assertThat(organic.note()).contains("minus Apple Ads installs");
        assertThat(organic.reach()).isEqualTo(5600.0);
        assertThat(row(report(), "app_store_browse").installs()).isEqualTo(14.0);
        assertThat(row(report(), "apple_ads").installs()).isEqualTo(28.0);
        assertThat(row(report(), "apple_ads").cost()).isEqualTo(35.0);
    }

    @Test
    void organicSearchSubtractsAdsFirstTimeDownloadsNotRedownloads() {
        // 100 search downloads; ads report 90 tap installs, 30 of them redownloads, so 60 first-time (keyword rows)
        f.put(store, "app-store-connect", "STORE", "downloads", "source:APP_STORE_SEARCH", ago(1), 100);
        f.put(ads, "apple-search-ads", "ADS", "installs", "", ago(1), 90);
        f.put(ads, "apple-search-ads", "ADS", "installs", "keyword:1:alpha", ago(1), 35);
        f.put(ads, "apple-search-ads", "ADS", "installs", "keyword:2:beta", ago(1), 25);

        AcquisitionRow organic = row(report(), "app_store_search");

        assertThat(organic.installs()).isEqualTo(40.0);
        assertThat(organic.derived()).isTrue();
        assertThat(organic.note()).contains("first-time downloads").contains("lower-bound");
    }

    @Test
    void productKeywordTableLabelsRowsWithTheKeywordTextNotTheId() {
        f.define(product, "trial_started", "SUM", "COUNT", null, null, null);
        f.put(product, "product-metrics", "PRODUCT", "trial_started", "keyword:77:meal planner", ago(2), 3);

        var table = overviewService.snapshot(f.project.getId(), 7, TODAY, NOW)
                .channels().get(com.conductor.integration.MetricChannel.PRODUCT).dimensions().stream()
                .filter(t -> t.key().equals("keywords")).findFirst().orElseThrow();

        assertThat(table.rows().get(0).key()).isEqualTo("77");
        assertThat(table.rows().get(0).label()).isEqualTo("meal planner");
    }

    @Test
    void keywordOutcomesAreOmittedWhenProductDefinesNeitherMetric() {
        String kw = "keyword:77:meal planner";
        f.put(ads, "apple-search-ads", "ADS", "taps", kw, ago(2), 30);
        f.put(ads, "apple-search-ads", "ADS", "spend", kw, ago(2), 40);

        Keyword k = report().keywords().get(0);

        assertThat(k.trials()).isNull();
        assertThat(k.activations()).isNull();
    }

    @Test
    void organicSearchNeverGoesBelowZeroWhenAdsReportMoreInstallsThanSearchDownloads() {
        seedStoreAndAds(30); // 210 paid installs against 70 search downloads

        assertThat(row(report(), "app_store_search").installs()).isEqualTo(0.0);
    }

    @Test
    void channelsThatAreNotConnectedAreListedWithTheirStatusAndNoNumbers() {
        seedStoreAndAds(4);

        Report report = report();

        assertThat(row(report, "search_engine").status()).isEqualTo("NOT_CONNECTED");
        assertThat(row(report, "search_engine").reach()).isNull();
        assertThat(report.sources()).extracting(MarketingReportService.Source::channel)
                .contains("WEB", "SEARCH", "SOCIAL", "STORE", "ADS", "PRODUCT");
        assertThat(report.sources().stream().filter(s -> s.channel().equals("STORE")).findFirst().orElseThrow()
                .connector()).isEqualTo("app-store-connect");
    }

    @Test
    void theFunnelFollowsTheInAppStagesAndMarksTheBiggestDrop() {
        f.define(product, "first_open", "SUM", "COUNT", 1, null, null);
        f.define(product, "onboarding_done", "SUM", "COUNT", 2, null, null);
        f.define(product, "trial_started", "SUM", "COUNT", 3, null, null);
        f.define(product, "paid", "SUM", "COUNT", 4, null, null);
        for (int d = 1; d <= 7; d++) {
            f.put(product, "product-metrics", "PRODUCT", "first_open", "", ago(d), 100);
            f.put(product, "product-metrics", "PRODUCT", "onboarding_done", "", ago(d), 80);
            f.put(product, "product-metrics", "PRODUCT", "trial_started", "", ago(d), 8);
            f.put(product, "product-metrics", "PRODUCT", "paid", "", ago(d), 4);
        }

        MarketingReportService.Funnel funnel = report().funnel();

        assertThat(funnel.stages()).extracting(MarketingReportService.FunnelStage::key)
                .containsExactly("first_open", "onboarding_done", "trial_started", "paid");
        assertThat(funnel.stages().get(0).dropPct()).isNull();
        assertThat(funnel.stages().get(1).dropPct()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(funnel.stages().get(2).dropPct()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(funnel.stages()).extracting(MarketingReportService.FunnelStage::biggestDrop)
                .containsExactly(false, false, true, false);
    }

    @Test
    void keywordRowsAreRankedBySpendAndJoinedToInAppOutcomesById() {
        f.define(product, "trial_started", "SUM", "COUNT", null, null, null);
        f.define(product, "activated", "SUM", "COUNT", null, null, null);
        String big = "keyword:77:meal planner";
        String small = "keyword:78:recipes";
        f.put(ads, "apple-search-ads", "ADS", "impressions", big, ago(2), 1000);
        f.put(ads, "apple-search-ads", "ADS", "taps", big, ago(2), 30);
        f.put(ads, "apple-search-ads", "ADS", "installs", big, ago(2), 6);
        f.put(ads, "apple-search-ads", "ADS", "spend", big, ago(2), 40);
        f.put(ads, "apple-search-ads", "ADS", "impressions", small, ago(2), 5000);
        f.put(ads, "apple-search-ads", "ADS", "taps", small, ago(2), 5);
        f.put(ads, "apple-search-ads", "ADS", "spend", small, ago(2), 3);
        f.state(ads, big, Map.of("status", "ACTIVE", "bidAmount", 1.5, "matchType", "EXACT",
                "adGroupName", "Core", "campaignName", "Brand"));
        f.put(product, "product-metrics", "PRODUCT", "trial_started", big, ago(2), 3);
        f.put(product, "product-metrics", "PRODUCT", "activated", big, ago(2), 2);

        java.util.List<Keyword> keywords = report().keywords();

        assertThat(keywords).extracting(Keyword::keywordId).containsExactly("77", "78");
        Keyword first = keywords.get(0);
        assertThat(first.text()).isEqualTo("meal planner");
        assertThat(first.adGroup()).isEqualTo("Core");
        assertThat(first.campaign()).isEqualTo("Brand");
        assertThat(first.matchType()).isEqualTo("EXACT");
        assertThat(first.bid()).isEqualTo(1.5);
        assertThat(first.spend()).isEqualTo(40.0);
        assertThat(first.installRate()).isEqualTo(0.2);
        assertThat(first.cpi()).isEqualTo(40.0 / 6);
        assertThat(first.trials()).isEqualTo(3.0);
        assertThat(first.activations()).isEqualTo(2.0);
        assertThat(first.judgeable()).isTrue();
        // no in-app rows for the second keyword: unknown, not zero
        assertThat(keywords.get(1).trials()).isNull();
        assertThat(keywords.get(1).activations()).isNull();
        assertThat(keywords.get(1).judgeable()).isFalse();
    }

    @Test
    void anAdRowSplitsItsIdFromItsName() {
        f.put(ads, "apple-search-ads", "ADS", "spend", "ad:9:55:Variant A", ago(2), 12);
        f.put(ads, "apple-search-ads", "ADS", "impressions", "ad:9:55:Variant A", ago(2), 400);
        f.state(ads, "ad:9:55:Variant A", Map.of("status", "ENABLED", "creativeType", "CUSTOM_PRODUCT_PAGE",
                "productPageId", "pp-1", "adGroupName", "Core"));

        MarketingReportService.Ad ad = report().ads().get(0);

        assertThat(ad.adId()).isEqualTo("55");
        assertThat(ad.name()).isEqualTo("Variant A");
        assertThat(ad.adGroup()).isEqualTo("Core");
        assertThat(ad.creativeType()).isEqualTo("CUSTOM_PRODUCT_PAGE");
        assertThat(ad.productPageId()).isEqualTo("pp-1");
        assertThat(ad.spend()).isEqualTo(12.0);
    }

    @Test
    void aRateOnFewerThanTwentyObservationsIsNotJudgedAndAnOldBenchmarkIsStale() {
        // 7 days x 2 unique impressions, 1 download a day: a conversion rate resting on 14 impressions
        for (int d = 1; d <= 7; d++) {
            f.put(store, "app-store-connect", "STORE", "impressions", "", ago(d), 2);
            f.put(store, "app-store-connect", "STORE", "impressions_unique", "", ago(d), 2);
            f.put(store, "app-store-connect", "STORE", "downloads", "", ago(d), 1);
        }
        benchmark("STORE.conversion_rate", 0.03, TODAY.minusDays(120));
        benchmark("STORE.downloads", 100, TODAY.minusDays(10));
        benchmark("STORE.not_a_metric", 1, TODAY);

        Map<String, MarketingReportService.Benchmark> byRef = new java.util.HashMap<>();
        report().benchmarks().forEach(b -> byRef.put(b.metricRef(), b));

        MarketingReportService.Benchmark rate = byRef.get("STORE.conversion_rate");
        assertThat(rate.value()).isNotNull();
        assertThat(rate.judged()).isFalse();
        assertThat(rate.stale()).isTrue();
        assertThat(rate.sampleSize()).isEqualTo(14L);
        MarketingReportService.Benchmark downloads = byRef.get("STORE.downloads");
        assertThat(downloads.value()).isEqualTo(7.0);
        assertThat(downloads.ratio()).isEqualTo(0.07);
        assertThat(downloads.judged()).isTrue();
        assertThat(downloads.stale()).isFalse();
        // a benchmark for a metric the report does not have is listed without a value
        assertThat(byRef.get("STORE.not_a_metric").value()).isNull();
        assertThat(byRef.get("STORE.not_a_metric").judged()).isFalse();
    }

    @Test
    void aRateWithEnoughObservationsIsJudged() {
        for (int d = 1; d <= 7; d++) {
            f.put(store, "app-store-connect", "STORE", "impressions", "", ago(d), 1000);
            f.put(store, "app-store-connect", "STORE", "impressions_unique", "", ago(d), 1000);
            f.put(store, "app-store-connect", "STORE", "downloads", "", ago(d), 50);
        }
        benchmark("STORE.conversion_rate", 0.03, TODAY.minusDays(5));

        MarketingReportService.Benchmark rate = report().benchmarks().get(0);

        assertThat(rate.judged()).isTrue();
        assertThat(rate.stale()).isFalse();
        assertThat(rate.goodDirection()).isEqualTo("UP");
        assertThat(rate.ratio()).isCloseTo(0.05 / 0.03, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void theScorecardListsMetricsWithAnAreaAndShowsTwoOfThirtyForARatioWithNoDescription() {
        f.define(product, "trial_started", "SUM", "COUNT", null, null, null);
        f.define(product, "first_open", "SUM", "COUNT", null, null, null);
        f.define(product, "trial_rate", "RATIO", "PERCENT", null, "trial_started", "first_open");
        for (int d = 1; d <= 7; d++) {
            f.put(product, "product-metrics", "PRODUCT", "first_open", "", ago(d), d == 1 ? 24 : 1);
            f.put(product, "product-metrics", "PRODUCT", "trial_started", "", ago(d), d == 1 ? 2 : 0);
        }
        definitionRepository.findByConnectionId(product).stream()
                .filter(d -> d.getMetric().equals("trial_rate"))
                .forEach(d -> {
                    d.setArea("ACTIVATION");
                    d.setHeadline(1);
                    definitionRepository.saveAndFlush(d);
                });
        seedStoreAndAds(4);

        Report report = report();

        MarketingReportService.ScorecardRow rate = report.scorecard().stream()
                .filter(r -> r.key().equals("trial_rate")).findFirst().orElseThrow();
        assertThat(rate.area()).isEqualTo("ACTIVATION");
        assertThat(rate.basis()).isEqualTo("2 of 30");
        assertThat(rate.sampleSize()).isEqualTo(30L);
        assertThat(rate.source().channel()).isEqualTo("PRODUCT");
        // the Conductor-owned store metrics carry an area too
        assertThat(report.scorecard()).filteredOn(r -> r.source().channel().equals("STORE"))
                .extracting(MarketingReportService.ScorecardRow::key)
                .contains("downloads", "conversion_rate");
        assertThat(report.headline()).extracting(MarketingReportService.Metric::key)
                .containsExactly("trial_rate", "spend", "cost_per_install");
    }

    @Test
    void changesAreBucketedIntoOpenOverdueAndReviewedInTheWindow() {
        WorkItem planned = f.change("Planned change", "PLANNED", null);
        WorkItem liveOnTime = f.change("Live, review later", "LIVE", TODAY.plusDays(5));
        WorkItem liveOverdue = f.change("Live, overdue", "LIVE", TODAY.minusDays(3));
        WorkItem reviewed = f.change("Reviewed this week", "REVIEWED", null);
        WorkItem reviewedLongAgo = f.change("Reviewed long ago", "REVIEWED", null);
        WorkItem abandoned = f.change("Abandoned", "ABANDONED", null);
        jdbc.update("UPDATE work_items SET updated_at = now() - interval '60 days' WHERE id = ?", reviewedLongAgo.getId());
        // an item on another workflow must not appear
        WorkItem other = f.change("Not a change", "LIVE", TODAY.minusDays(9));
        jdbc.update("UPDATE work_items SET workflow = 'MARKETING' WHERE id = ?", other.getId());

        MarketingReportService.Changes changes = report().changes();

        assertThat(changes.open()).extracting(MarketingReportService.Change::title)
                .containsExactlyInAnyOrder("Planned change", "Live, review later", "Live, overdue");
        assertThat(changes.dueForReview()).extracting(MarketingReportService.Change::title)
                .containsExactly("Live, overdue");
        assertThat(changes.reviewedInWindow()).extracting(MarketingReportService.Change::title)
                .containsExactly("Reviewed this week");
        assertThat(changes.open().get(0).displayId()).startsWith(f.project.getKey() + "-");
        assertThat(planned.getId()).isNotNull();
        assertThat(liveOnTime.getId()).isNotNull();
        assertThat(liveOverdue.getId()).isNotNull();
        assertThat(reviewed.getId()).isNotNull();
        assertThat(abandoned.getId()).isNotNull();
    }

    @Test
    void dailyCarriesStoreImpressionsAndAdSpendForEachDay() {
        seedStoreAndAds(4);

        java.util.List<MarketingReportService.Day> daily = report().daily();

        assertThat(daily).hasSize(7);
        assertThat(daily.get(0).day()).isEqualTo(ago(7));
        assertThat(daily.get(0).storeImpressions()).isEqualTo(1000.0);
        assertThat(daily.get(0).adSpend()).isEqualTo(5.0);
        assertThat(daily.get(6).day()).isEqualTo(ago(1));
    }

    private void benchmark(String ref, double value, LocalDate checkedOn) {
        MarketingBenchmark b = new MarketingBenchmark();
        b.setProjectId(f.project.getId());
        b.setMetricRef(ref);
        b.setValue(java.math.BigDecimal.valueOf(value));
        b.setSourceName("Example Study");
        b.setSourceUrl("https://example.com/study");
        b.setCheckedOn(checkedOn);
        benchmarkRepository.saveAndFlush(b);
    }
}
