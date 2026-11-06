package com.conductor.marketing.report;

import com.conductor.entity.ChannelMetricDefinitionRow;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.PublishLane;
import com.conductor.entity.WorkItem;
import com.conductor.integration.ingest.ChannelDimensionStateStore;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.marketing.links.TrackedLink;
import com.conductor.marketing.links.TrackedLinkRepository;
import com.conductor.marketing.report.MarketingReportService.AcquisitionRow;
import com.conductor.marketing.report.MarketingReportService.AsoKeyword;
import com.conductor.marketing.report.MarketingReportService.Keyword;
import com.conductor.marketing.report.MarketingReportService.PaybackRow;
import com.conductor.marketing.report.MarketingReportService.Report;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ChannelMetricDefinitionRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.conductor.marketing.report.MarketingReportFixture.REF_DAYS;
import static com.conductor.marketing.report.MarketingReportFixture.REF_FROM;
import static com.conductor.marketing.report.MarketingReportFixture.REF_NOW;
import static com.conductor.marketing.report.MarketingReportFixture.REF_TO;
import static com.conductor.marketing.report.MarketingReportFixture.REF_TODAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * The channel-quality sections of the report (RE-99 T7.1): keyword quality and cost per outcome, payback by channel,
 * the App Review exclusion, the EMAIL and SHARES rows, tracked-link families, ASO keywords and the verification
 * summary. Rows are seeded into {@code channel_metric_daily} for the reference window and read back through the
 * real services. The invariant under test throughout: data a source did not send reads null (or the row is
 * omitted), never 0.
 */
@Transactional
class MarketingReportQualityTest extends AbstractNoneWebIntegrationTest {

    private static final String ASA = "apple-search-ads";
    private static final String ASC = "app-store-connect";
    private static final String PM = "product-metrics";
    private static final String WINDOW = REF_FROM + ".." + REF_TO;

    @Autowired private MarketingReportService service;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ConnectorFeedRepository feedRepository;
    @Autowired private ChannelMetricDailyRepository dailyRepository;
    @Autowired private ChannelMetricDefinitionRepository definitionRepository;
    @Autowired private ChannelDimensionStateStore stateStore;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private TrackedLinkRepository linkRepository;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private PostPublishTargetMetricRepository metricRepository;

    private MarketingReportFixture f;
    private int sequence = 100;

    @BeforeEach
    void setUp() {
        f = new MarketingReportFixture(userRepository, projectRepository, connectionRepository, feedRepository,
                dailyRepository, definitionRepository, stateStore, workItemRepository);
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private String connect(String connectorId) {
        return f.connect(connectorId, REF_NOW.minusHours(1));
    }

    private Report report() {
        return service.report(f.project.getId(), REF_DAYS, REF_TODAY, REF_NOW);
    }

    private Report fixedReport() {
        return service.report(f.project.getId(), WINDOW, REF_TODAY, REF_NOW);
    }

    /**
     * A connected product feed with a day total on every day of the window (first opens), as a real feed has. Without
     * one the feed has no data of its own and its window would not line up with the rows these tests seed.
     */
    private String productFeed() {
        String product = connect(PM);
        f.define(product, "first_open", "SUM", "COUNT", null, null, null);
        f.putSpread(product, PM, "PRODUCT", "first_open", "", REF_DAYS, 0);
        return product;
    }

    private String product(String... sumCountKeys) {
        String product = productFeed();
        for (String key : sumCountKeys) {
            f.define(product, key, "SUM", "COUNT", null, null, null);
        }
        return product;
    }

    private void productTotal(String product, String metric, String dimension, double total) {
        f.putSpread(product, PM, "PRODUCT", metric, dimension, total, 0);
    }

    private Keyword keyword(Report report, String id) {
        return report.keywords().stream().filter(k -> k.keywordId().equals(id)).findFirst().orElseThrow();
    }

    private static Optional<AcquisitionRow> row(Report report, String key) {
        return report.acquisition().stream().filter(r -> r.key().equals(key)).findFirst();
    }

    private void adsKeyword(String ads, String idAndText, double spend) {
        f.putSpread(ads, ASA, "ADS", "impressions", "", 100, 0);
        f.putSpread(ads, ASA, "ADS", "spend", "keyword:" + idAndText, spend, 2);
        f.putSpread(ads, ASA, "ADS", "taps", "keyword:" + idAndText, 30, 0);
    }

    // ---- P1-2.1: keyword quality ------------------------------------------------------------------

    @Test
    void aKeywordRowShowsCostPerActivatedRetainedAndTrialFromItsSpendAndOutcomes() {
        String ads = connect(ASA);
        String product = product("activated", "retained_w2", "trial_started");
        adsKeyword(ads, "11:rexipe", 17.51);
        productTotal(product, "activated", "keyword:11:rexipe", 2);
        productTotal(product, "retained_w2", "keyword:11:rexipe", 1);
        productTotal(product, "trial_started", "keyword:11:rexipe", 1);

        Keyword k = keyword(report(), "11");

        assertThat(k.activations()).isEqualTo(2.0);
        assertThat(k.retainedW2()).isEqualTo(1.0);
        assertThat(k.trials()).isEqualTo(1.0);
        assertThat(k.costPerActivatedInstall()).isCloseTo(8.755, within(1e-9));
        assertThat(k.costPerRetainedW2Install()).isCloseTo(17.51, within(1e-9));
        assertThat(k.costPerTrial()).isCloseTo(17.51, within(1e-9));
        assertThat(k.qualityNote()).isNull();
    }

    @Test
    void zeroActivationsGiveANullCostAndSayNoActivationsYet() {
        String ads = connect(ASA);
        String product = product("activated", "retained_w2", "trial_started");
        adsKeyword(ads, "12:meal planner", 5.0);
        productTotal(product, "activated", "keyword:12:meal planner", 0);
        productTotal(product, "retained_w2", "keyword:12:meal planner", 0);
        productTotal(product, "trial_started", "keyword:12:meal planner", 1);

        Keyword k = keyword(report(), "12");

        assertThat(k.activations()).isEqualTo(0.0);
        assertThat(k.costPerActivatedInstall()).isNull();
        assertThat(k.costPerRetainedW2Install()).isNull();
        assertThat(k.costPerTrial()).isCloseTo(5.0, within(1e-9));
        assertThat(k.qualityNote()).containsIgnoringCase("no activations yet");
    }

    @Test
    void aKeywordTheProductFeedSentNothingForHasNullOutcomesCostsAndNoNote() {
        String ads = connect(ASA);
        String product = product("activated", "retained_w2", "trial_started");
        adsKeyword(ads, "13:quiet", 4.0);
        adsKeyword(ads, "14:loud", 6.0);
        productTotal(product, "activated", "keyword:14:loud", 3);

        Keyword quiet = keyword(report(), "13");

        assertThat(quiet.activations()).isNull();
        assertThat(quiet.retainedW2()).isNull();
        assertThat(quiet.trials()).isNull();
        assertThat(quiet.costPerActivatedInstall()).isNull();
        assertThat(quiet.costPerRetainedW2Install()).isNull();
        assertThat(quiet.costPerTrial()).isNull();
        assertThat(quiet.qualityNote()).isNull();
    }

    @Test
    void aFeedThatDoesNotDeclareAWeekTwoMetricGivesNullNotZeroForIt() {
        String ads = connect(ASA);
        String product = product("activated");
        adsKeyword(ads, "11:rexipe", 10.0);
        productTotal(product, "activated", "keyword:11:rexipe", 2);

        Keyword k = keyword(report(), "11");

        assertThat(k.costPerActivatedInstall()).isCloseTo(5.0, within(1e-9));
        assertThat(k.retainedW2()).isNull();
        assertThat(k.costPerRetainedW2Install()).isNull();
        assertThat(k.trials()).isNull();
        assertThat(k.qualityNote()).isNull();
    }

    // ---- P1-2.2: payback --------------------------------------------------------------------------

    @Test
    void paybackIsProceedsAtTheAppleAdsSourceOverSpend() {
        String ads = connect(ASA);
        f.putSpread(ads, ASA, "ADS", "spend", "", 162.86, 2);
        String product = productFeed();
        f.define(product, "proceeds_net", "SUM", "CURRENCY", null, null, null);
        f.putSpread(product, PM, "PRODUCT", "proceeds_net", "source:apple_search_ads", 400.00, 2);
        f.putSpread(product, PM, "PRODUCT", "proceeds_net", "source:organic", 900.00, 2);

        MarketingReportService.Payback payback = report().payback();

        assertThat(payback).isNotNull();
        assertThat(payback.basis()).containsIgnoringCase("12-month");
        assertThat(payback.channels()).hasSize(1);
        PaybackRow apple = payback.channels().get(0);
        assertThat(apple.channel()).isEqualTo("apple_ads");
        assertThat(apple.spend()).isCloseTo(162.86, within(0.01));
        assertThat(apple.proceedsNet12m()).isCloseTo(400.0, within(0.01));
        assertThat(apple.ratio()).isCloseTo(400.0 / 162.86, within(0.001));
        assertThat(payback.total().spend()).isCloseTo(162.86, within(0.01));
        assertThat(payback.total().proceedsNet12m()).isCloseTo(400.0, within(0.01));
        assertThat(payback.total().ratio()).isCloseTo(400.0 / 162.86, within(0.001));
    }

    @Test
    void paybackNamesAChannelWhoseSourceTheFeedSentNoProceedsForAndLeavesTheRatioNull() {
        String ads = connect(ASA);
        f.putSpread(ads, ASA, "ADS", "spend", "", 100.0, 2);
        String product = productFeed();
        f.define(product, "proceeds_net", "SUM", "CURRENCY", null, null, null);
        f.putSpread(product, PM, "PRODUCT", "proceeds_net", "source:organic", 900.00, 2);

        MarketingReportService.Payback payback = report().payback();

        PaybackRow apple = payback.channels().get(0);
        assertThat(apple.spend()).isCloseTo(100.0, within(0.01));
        assertThat(apple.proceedsNet12m()).isNull();
        assertThat(apple.ratio()).isNull();
        assertThat(apple.note()).isNotBlank();
        assertThat(payback.total().proceedsNet12m()).isNull();
        assertThat(payback.total().ratio()).isNull();
    }

    @Test
    void thereIsNoPaybackWithoutAProceedsMetricOrWithoutSpend() {
        String ads = connect(ASA);
        f.putSpread(ads, ASA, "ADS", "spend", "", 100.0, 2);
        product("activated");
        assertThat(report().payback()).isNull();

        MarketingReportFixture noAds = new MarketingReportFixture(userRepository, projectRepository,
                connectionRepository, feedRepository, dailyRepository, definitionRepository, stateStore,
                workItemRepository);
        String p = noAds.connect(PM, REF_NOW.minusHours(1));
        noAds.define(p, "proceeds_net", "SUM", "CURRENCY", null, null, null);
        noAds.putSpread(p, PM, "PRODUCT", "proceeds_net", "source:apple_search_ads", 400, 2);
        assertThat(service.report(noAds.project.getId(), REF_DAYS, REF_TODAY, REF_NOW).payback()).isNull();
    }

    // ---- P1-2.3: App Review exclusion -------------------------------------------------------------

    @Test
    void likelyAppReviewInstallsSurfaceAsAnExclusionWithTheFeedsOwnWording() {
        String product = product();
        ChannelMetricDefinitionRow d = f.define(product, "app_review_installs_excluded", "SUM", "COUNT", null, null,
                null);
        d.setExclusions("Counted by device model and first-open hour");
        definitionRepository.saveAndFlush(d);
        productTotal(product, "app_review_installs_excluded", "", 7);

        List<MarketingReportService.Exclusion> exclusions = report().exclusions();

        assertThat(exclusions).hasSize(1);
        assertThat(exclusions.get(0).key()).isEqualTo("app_review_installs");
        assertThat(exclusions.get(0).label()).isEqualTo("Likely App Review installs");
        assertThat(exclusions.get(0).value()).isEqualTo(7.0);
        assertThat(exclusions.get(0).note()).isEqualTo("Counted by device model and first-open hour");
    }

    @Test
    void aReportedZeroIsAnExclusionAndAMetricWithNoValueIsNot() {
        String product = product("app_review_installs_excluded");
        assertThat(report().exclusions()).isEmpty();

        productTotal(product, "app_review_installs_excluded", "", 0);
        List<MarketingReportService.Exclusion> exclusions = report().exclusions();

        assertThat(exclusions).hasSize(1);
        assertThat(exclusions.get(0).value()).isEqualTo(0.0);
        assertThat(exclusions.get(0).note()).isNull();
    }

    @Test
    void withoutTheMetricThereAreNoExclusions() {
        product("activated");
        assertThat(report().exclusions()).isEmpty();
    }

    // ---- P1-9: email and shares -------------------------------------------------------------------

    @Test
    void emailAndSharesRowsCarryTheContractCountsLabelsAndADefinition() {
        String product = product("email_sends", "email_clicks", "email_trials_saved", "share_links_created",
                "share_views", "share_installs");
        productTotal(product, "email_sends", "", 400);
        productTotal(product, "email_clicks", "", 60);
        productTotal(product, "email_trials_saved", "", 5);
        productTotal(product, "share_links_created", "", 30);
        productTotal(product, "share_views", "", 90);
        productTotal(product, "share_installs", "", 4);

        Report report = report();
        AcquisitionRow email = row(report, "email").orElseThrow();
        AcquisitionRow shares = row(report, "shares").orElseThrow();

        assertThat(email.reach()).isEqualTo(400.0);
        assertThat(email.reachLabel()).isEqualTo("Emails sent");
        assertThat(email.actions()).isEqualTo(60.0);
        assertThat(email.actionsLabel()).isEqualTo("Clicks");
        assertThat(email.installs()).isEqualTo(5.0);
        assertThat(email.installsLabel()).isEqualTo("Trials saved");
        assertThat(email.definitionLine()).isNotBlank();
        assertThat(shares.reach()).isEqualTo(30.0);
        assertThat(shares.reachLabel()).isEqualTo("Links shared");
        assertThat(shares.actions()).isEqualTo(90.0);
        assertThat(shares.actionsLabel()).isEqualTo("Views");
        assertThat(shares.installs()).isEqualTo(4.0);
        assertThat(shares.definitionLine()).isNotBlank();
        assertThat(report.definitions()).containsKeys("PRODUCT.email_sends", "PRODUCT.share_installs");
    }

    @Test
    void emailAndSharesRowsAreOmittedWhenNoneOfTheirMetricsExistAndAMissingMetricIsNull() {
        String product = product("activated", "email_sends");
        assertThat(row(report(), "email")).isEmpty();
        assertThat(row(report(), "shares")).isEmpty();

        productTotal(product, "email_sends", "", 400);
        AcquisitionRow email = row(report(), "email").orElseThrow();

        assertThat(email.reach()).isEqualTo(400.0);
        assertThat(email.actions()).isNull();
        assertThat(email.actionsLabel()).isNull();
        assertThat(email.installs()).isNull();
        assertThat(email.installsLabel()).isNull();
        assertThat(row(report(), "shares")).isEmpty();
    }

    @Test
    void channelRowsFillInWhenTheTotalsAreAbsent() {
        String product = product("email_sends", "share_views");
        productTotal(product, "email_sends", "channel:email", 250);
        productTotal(product, "share_views", "channel:shares", 70);
        productTotal(product, "share_views", "channel:email", 9);

        Report report = fixedReport();

        assertThat(row(report, "email").orElseThrow().reach()).isEqualTo(250.0);
        assertThat(row(report, "shares").orElseThrow().actions()).isEqualTo(70.0);
    }

    @Test
    void aTotalWinsOverTheChannelRows() {
        String product = product("email_sends");
        productTotal(product, "email_sends", "", 400);
        productTotal(product, "email_sends", "channel:email", 250);

        assertThat(row(report(), "email").orElseThrow().reach()).isEqualTo(400.0);
    }

    // ---- P1-7: tracked-link families --------------------------------------------------------------

    private void link(String kind, String family, String token, String workItemId) {
        TrackedLink l = new TrackedLink();
        l.setProjectId(f.project.getId());
        l.setKind(kind);
        l.setFamily(family);
        l.setToken(token);
        l.setUrl("https://apps.apple.com/app/apple-store/id1?pt=1&ct=" + token);
        l.setWorkItemId(workItemId);
        linkRepository.saveAndFlush(l);
    }

    @Test
    void aLinkFamilyRowShowsDownloadsAndAFamilyBelowTheThresholdShowsNullAndTheNote() {
        String store = connect(ASC);
        link("BIO", "social-bios", "ig-bio", null);
        link("CREATOR", "creators", "maya", null);
        f.putSpread(store, ASC, "STORE", "downloads", "", 30, 0);
        f.putSpread(store, ASC, "STORE", "campaign_downloads", "ct:ig-bio", 12, 0);

        Report report = fixedReport();
        AcquisitionRow bios = row(report, "link_family:social-bios").orElseThrow();
        AcquisitionRow creators = row(report, "link_family:creators").orElseThrow();

        assertThat(bios.label()).isEqualTo("social-bios links (BIO)");
        assertThat(bios.installs()).isEqualTo(12.0);
        assertThat(bios.note()).isNull();
        assertThat(bios.reach()).isNull();
        assertThat(creators.label()).isEqualTo("creators links (CREATOR)");
        assertThat(creators.installs()).isNull();
        assertThat(creators.note()).isEqualTo("below Apple's reporting threshold");
    }

    @Test
    void aPostFamilyRowShowsItsPostsViewsAsReach() {
        String store = connect(ASC);
        WorkItem post = post();
        link("POST", "posts", "post-1", post.getId());
        f.putSpread(store, ASC, "STORE", "downloads", "", 30, 0);
        f.putSpread(store, ASC, "STORE", "campaign_downloads", "ct:post-1", 6, 0);
        PostPublishTarget target = target(post, store);
        snapshot(target, 1200L);

        AcquisitionRow posts = row(fixedReport(), "link_family:posts").orElseThrow();

        assertThat(posts.label()).isEqualTo("posts links (POST)");
        assertThat(posts.reach()).isEqualTo(1200.0);
        assertThat(posts.reachLabel()).isEqualTo("Post views");
        assertThat(posts.installs()).isEqualTo(6.0);
    }

    @Test
    void aProjectWithNoTrackedLinksHasNoLinkRows() {
        String store = connect(ASC);
        f.putSpread(store, ASC, "STORE", "downloads", "", 30, 0);

        assertThat(fixedReport().acquisition()).noneMatch(r -> r.key().startsWith("link_family:"));
    }

    private WorkItem post() {
        WorkItem post = new WorkItem();
        post.setProject(f.project);
        post.setType("POST");
        post.setTitle("A post");
        post.setCurrentStatus("PUBLISHED");
        post.setWorkflow("MARKETING");
        post.setWorkflowVersion(1);
        post.setCreatedBy(f.user);
        post.setSequenceNumber(sequence++);
        return workItemRepository.saveAndFlush(post);
    }

    private PostPublishTarget target(WorkItem post, String connectionId) {
        PostPublishTarget t = new PostPublishTarget();
        t.setWorkItem(post);
        t.setConnectorId("meta");
        t.setConnectionId(connectionId);
        t.setPlatform("instagram");
        t.setLane(PublishLane.APP_MANAGED);
        t.setState(PostPublishTargetState.PUBLISHED);
        t.setPlatformPostId("instagram-" + UUID.randomUUID());
        t.setFireTime(OffsetDateTime.parse("2026-10-09T10:00:00Z"));
        t.setIdempotencyKey("pub:" + UUID.randomUUID());
        return targetRepository.saveAndFlush(t);
    }

    private void snapshot(PostPublishTarget target, Long views) {
        PostPublishTargetMetric m = new PostPublishTargetMetric();
        m.setTargetId(target.getId());
        m.setWorkItemId(target.getWorkItem().getId());
        m.setProjectId(f.project.getId());
        m.setPlatform(target.getPlatform());
        m.setPeriodKey("2026-10-12T06");
        m.setObservedAt(OffsetDateTime.parse("2026-10-12T06:05:00Z"));
        m.setViews(views);
        m.setUnavailable(false);
        metricRepository.saveAndFlush(m);
    }

    // ---- P1-5: ASO keywords -----------------------------------------------------------------------

    private String asoProduct() {
        String product = productFeed();
        f.define(product, "aso_rank", "LEVEL", "POSITION", null, null, null);
        f.define(product, "aso_popularity", "LEVEL", "COUNT", null, null, null);
        return product;
    }

    private void aso(String product, String metric, String keyword, LocalDate day, double value) {
        f.put(product, PM, "PRODUCT", metric, "aso:" + keyword, day, value);
    }

    @Test
    void asoKeywordsShowTheLatestRankAndPopularityAndTheChangeSincePreviousWindowEnd() {
        String product = asoProduct();
        LocalDate previousEnd = REF_FROM.minusDays(1);
        aso(product, "aso_rank", "meal planner", previousEnd.minusDays(2), 25);
        aso(product, "aso_rank", "meal planner", previousEnd, 18);
        aso(product, "aso_rank", "meal planner", REF_TO.minusDays(3), 16);
        aso(product, "aso_rank", "meal planner", REF_TO, 14);
        aso(product, "aso_popularity", "meal planner", REF_TO, 45);
        aso(product, "aso_rank", "recipe app", REF_TO, 30);
        aso(product, "aso_rank", "rexipe", previousEnd, 1);
        aso(product, "aso_rank", "rexipe", REF_TO, 3);
        aso(product, "aso_rank", "afterwards", REF_TO.plusDays(1), 2);

        List<AsoKeyword> keywords = fixedReport().asoKeywords();

        assertThat(keywords).extracting(AsoKeyword::keyword).containsExactly("rexipe", "meal planner", "recipe app");
        AsoKeyword meal = keywords.get(1);
        assertThat(meal.rank()).isEqualTo(14.0);
        assertThat(meal.popularity()).isEqualTo(45.0);
        assertThat(meal.previousRank()).isEqualTo(18.0);
        assertThat(meal.rankChange()).isEqualTo(-4.0);
        assertThat(keywords.get(0).rankChange()).isEqualTo(2.0);
        AsoKeyword recipe = keywords.get(2);
        assertThat(recipe.previousRank()).isNull();
        assertThat(recipe.rankChange()).isNull();
        assertThat(recipe.popularity()).isNull();
    }

    @Test
    void aKeywordWithNoReadingInTheWindowHasNoChangeToReport() {
        String product = asoProduct();
        aso(product, "aso_rank", "stale", REF_FROM.minusDays(5), 9);
        aso(product, "aso_rank", "fresh", REF_TO, 4);

        AsoKeyword stale = fixedReport().asoKeywords().stream().filter(k -> k.keyword().equals("stale")).findFirst()
                .orElseThrow();

        assertThat(stale.rank()).isEqualTo(9.0);
        assertThat(stale.rankChange()).isNull();
    }

    @Test
    void asoKeywordsAreCappedAtFiftyByBestRank() {
        String product = asoProduct();
        for (int i = 1; i <= 55; i++) {
            aso(product, "aso_rank", "keyword " + (i < 10 ? "0" + i : i), REF_TO, i);
        }

        List<AsoKeyword> keywords = fixedReport().asoKeywords();

        assertThat(keywords).hasSize(50);
        assertThat(keywords.get(0).rank()).isEqualTo(1.0);
        assertThat(keywords.get(49).rank()).isEqualTo(50.0);
    }

    @Test
    void thereAreNoAsoKeywordsWithoutTheMetricsOrReadings() {
        product("activated");
        assertThat(fixedReport().asoKeywords()).isEmpty();

        asoProduct();
        assertThat(fixedReport().asoKeywords()).isEmpty();
    }

    // ---- verification summary ---------------------------------------------------------------------

    @Test
    void theVerificationSummaryCountsWhatTheLedgerFoundForTheWindow() {
        // the ledger reads the real clock, so the days are seeded relative to it: 28 settled days ending 4 days ago
        LocalDate last = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(4);
        String ads = connect(ASA);
        String product = connect(PM);
        for (int i = 0; i < REF_DAYS; i++) {
            f.put(ads, ASA, "ADS", "spend", "", last.minusDays(i), 5.82);
            f.put(product, PM, "PRODUCT", "ref_ads_spend", "", last.minusDays(i), 2.0);
        }

        MarketingReportService.Verification verification = service.report(f.project.getId(), "28d").verification();

        assertThat(verification).isNotNull();
        assertThat(verification.mismatched()).isEqualTo(1);
        assertThat(verification.both()).isZero();
        assertThat(verification.staleReads()).isZero();
        assertThat(verification.unchecked()).isGreaterThanOrEqualTo(0);
        // no window named: both the report and the ledger fall back to their default, so the tally is still there
        assertThat(service.report(f.project.getId(), null).verification()).isEqualTo(verification);
    }

    @Test
    void aProjectWithNoConnectedChannelHasNoVerificationSummary() {
        assertThat(fixedReport().verification()).isNull();
    }
}
