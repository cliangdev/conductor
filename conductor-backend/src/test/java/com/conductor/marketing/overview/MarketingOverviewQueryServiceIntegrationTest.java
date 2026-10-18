package com.conductor.marketing.overview;

import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.integration.ingest.ConnectorFeedStatus;
import com.conductor.marketing.overview.MarketingOverviewQueryService.ChannelView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.MetricView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.Overview;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves {@link MarketingOverviewQueryService} against real Postgres: window math, previous-period
 * comparison, LEVEL and derived roll-ups, freshness status, provisional counting, dimension tables and
 * targets. Shared singleton Postgres, random project, no global counts.
 *
 * <p>"Today" is pinned to 2026-10-15, so the 7-day window is Oct 8-14 and the previous one Oct 1-7.
 */
@Transactional
class MarketingOverviewQueryServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final OffsetDateTime NOW = TODAY.atTime(12, 0).atOffset(ZoneOffset.UTC);

    @Autowired private MarketingOverviewQueryService service;
    @Autowired private ChannelMetricDailyRepository dailyRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ConnectorFeedRepository feedRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;

    private Project project;
    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Overview Test");
        user = userRepository.save(user);

        project = new Project();
        project.setName("Overview Test Project");
        project.setKey("OV" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        project = projectRepository.save(project);
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private String connect(String connectorId, OffsetDateTime lastSuccessAt) {
        Connection connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId(connectorId);
        connection.setDisplayLabel(connectorId + " account");
        connection.setAuthType("OAUTH2");
        connection.setStatus("ACTIVE");
        connection.setConfigJson("{}");
        connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        String id = connectionRepository.saveAndFlush(connection).getId();

        ConnectorFeed feed = new ConnectorFeed();
        feed.setProjectId(project.getId());
        feed.setConnectionId(id);
        feed.setConnectorId(connectorId);
        feed.setIngestId(MetricCatalog.FEED_INGEST_ID);
        feed.setIntervalMinutes(360);
        feed.setStatus(ConnectorFeedStatus.ACTIVE);
        feed.setLastSuccessAt(lastSuccessAt);
        feedRepository.saveAndFlush(feed);
        return id;
    }

    private String connectFresh(String connectorId) {
        return connect(connectorId, NOW.minusHours(1));
    }

    private void put(String connectionId, String connectorId, String channel, String metric, String dimension,
                     LocalDate day, double value, boolean provisional) {
        dailyRepository.upsert(UUID.randomUUID().toString(), project.getId(), connectionId, connectorId, channel,
                metric, dimension, day, BigDecimal.valueOf(value), provisional);
    }

    private void put(String connectionId, String connectorId, String channel, String metric, LocalDate day,
                     double value) {
        put(connectionId, connectorId, channel, metric, "", day, value, false);
    }

    private static LocalDate day(int dayOfOctober) {
        return LocalDate.of(2026, 10, dayOfOctober);
    }

    private Overview overview7() {
        return service.overview(project.getId(), 7, TODAY, NOW);
    }

    private static ChannelView channel(Overview overview, String name) {
        return overview.channels().stream().filter(c -> c.channel().equals(name)).findFirst().orElseThrow();
    }

    private static MetricView metric(ChannelView channel, String key) {
        return channel.metrics().stream().filter(m -> m.metric().equals(key)).findFirst().orElseThrow();
    }

    // ---- tests ----------------------------------------------------------------------------------

    @Test
    void anOverviewWithNoDataClaimsNoEndDateAndNeverEndsOnTheCurrentUtcDay() {
        Overview overview = overview7();

        assertThat(overview.window().to()).isNull();
        assertThat(overview.window().dataCompleteThroughMin()).isNull();
        // a channel with no data falls back two days, so a window never ends on anyone's local today
        assertThat(channel(overview, "WEB").dataCompleteThrough()).isEqualTo(day(13));
    }

    @Test
    void theOverallRangeCoversOnlyChannelsThatHaveData() {
        String web = connectFresh("posthog");
        connectFresh("gsc"); // connected but empty: must not drag the range
        put(web, "posthog", "WEB", "visitors", day(14), 3);

        Overview overview = overview7();

        assertThat(overview.window().to()).isEqualTo(day(14));
        assertThat(overview.window().dataCompleteThroughMin()).isEqualTo(day(14));
        assertThat(overview.window().from()).isEqualTo(day(8));
        assertThat(overview.window().previousFrom()).isEqualTo(day(1));
        assertThat(overview.window().previousTo()).isEqualTo(day(7));
    }

    @Test
    void sevenDaysAreComparedWithThePreviousSevenAndTodayIsExcluded() {
        String web = connectFresh("posthog");
        for (int d = 1; d <= 7; d++) {
            put(web, "posthog", "WEB", "visitors", day(d), 5);
        }
        for (int d = 8; d <= 14; d++) {
            put(web, "posthog", "WEB", "visitors", day(d), 10);
        }
        put(web, "posthog", "WEB", "visitors", day(15), 1000); // today: partial, must not count

        MetricView visitors = metric(channel(overview7(), "WEB"), "visitors");

        assertThat(visitors.value()).isEqualTo(70.0);
        assertThat(visitors.previous()).isEqualTo(35.0);
        assertThat(visitors.change()).isEqualTo(35.0);
        assertThat(visitors.changePct()).isEqualTo(1.0);
        assertThat(visitors.series()).hasSize(7);
        assertThat(visitors.series().get(0).day()).isEqualTo(day(8));
        assertThat(visitors.series().get(6).value()).isEqualTo(10.0);
    }

    @Test
    void aPreviousWindowOnlyPartlyCoveredByHistoryGivesNoPercentChangeJustWhereHistoryStarts() {
        String web = connectFresh("posthog");
        // history starts on the 5th: the previous window (1st-7th) holds only three of its seven days
        for (int d = 5; d <= 7; d++) {
            put(web, "posthog", "WEB", "visitors", day(d), 1);
        }
        for (int d = 8; d <= 14; d++) {
            put(web, "posthog", "WEB", "visitors", day(d), 10);
        }

        MetricView visitors = metric(channel(overview7(), "WEB"), "visitors");

        assertThat(visitors.value()).isEqualTo(70.0);
        assertThat(visitors.previous()).isNull();
        assertThat(visitors.change()).isNull();
        assertThat(visitors.changePct()).isNull();
        assertThat(visitors.historyStartsOn()).isEqualTo(day(5));
    }

    @Test
    void theAverageRatingAcrossTwoAppsIsWeightedByEachAppsRatingCountWhileCountsAdd() {
        String a = connectFresh("app-store-connect");
        String b = connectFresh("app-store-connect");
        // previous snapshot (Oct 3): 3.5 x 10 and 4.5 x 10 -> 4.0
        put(a, "app-store-connect", "STORE", "rating_avg", day(3), 3.5);
        put(a, "app-store-connect", "STORE", "rating_count", day(3), 10);
        put(b, "app-store-connect", "STORE", "rating_avg", day(3), 4.5);
        put(b, "app-store-connect", "STORE", "rating_count", day(3), 10);
        // latest (Oct 10): 4.0 x 10 and 5.0 x 30 -> 4.75, never 9.0
        put(a, "app-store-connect", "STORE", "rating_avg", day(10), 4.0);
        put(a, "app-store-connect", "STORE", "rating_count", day(10), 10);
        put(b, "app-store-connect", "STORE", "rating_avg", day(10), 5.0);
        put(b, "app-store-connect", "STORE", "rating_count", day(10), 30);

        var detail = service.channel(project.getId(), MetricCatalog.channel("STORE").orElseThrow(), 7, TODAY, NOW);

        MetricView avg = metric(detail.channel(), "rating_avg");
        assertThat(avg.value()).isCloseTo(4.75, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(avg.previous()).isCloseTo(4.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(metric(detail.channel(), "rating_count").value()).isEqualTo(40.0);
        // every day of the series uses the same rule (Oct 9 still reads the Oct 3 snapshots)
        assertThat(avg.series()).filteredOn(p -> p.day().equals(day(9))).singleElement()
                .satisfies(p -> assertThat(p.value()).isCloseTo(4.0, org.assertj.core.data.Offset.offset(1e-9)));
        assertThat(avg.series()).filteredOn(p -> p.day().equals(day(10))).singleElement()
                .satisfies(p -> assertThat(p.value()).isCloseTo(4.75, org.assertj.core.data.Offset.offset(1e-9)));
    }

    @Test
    void theAverageRatingFallsBackToAPlainMeanWhenACountIsMissing() {
        String a = connectFresh("app-store-connect");
        String b = connectFresh("app-store-connect");
        put(a, "app-store-connect", "STORE", "rating_avg", day(10), 4.0);
        put(b, "app-store-connect", "STORE", "rating_avg", day(10), 5.0);
        put(b, "app-store-connect", "STORE", "rating_count", day(10), 30);

        var detail = service.channel(project.getId(), MetricCatalog.channel("STORE").orElseThrow(), 7, TODAY, NOW);

        assertThat(metric(detail.channel(), "rating_avg").value()).isEqualTo(4.5);
    }

    @Test
    void levelMetricsTakeTheLatestValuePerPlatformThenSumAcrossPlatforms() {
        String meta = connectFresh("meta");
        String youtube = connectFresh("youtube");
        // previous window (Oct 1-7): facebook 90, instagram 40
        put(meta, "meta", "SOCIAL", "followers", "facebook", day(5), 90, false);
        put(meta, "meta", "SOCIAL", "followers", "instagram", day(5), 40, false);
        // current window: facebook 100, instagram restated 50 then 60 (latest wins), youtube 30
        put(meta, "meta", "SOCIAL", "followers", "facebook", day(10), 100, false);
        put(meta, "meta", "SOCIAL", "followers", "instagram", day(10), 50, false);
        put(meta, "meta", "SOCIAL", "followers", "instagram", day(12), 60, false);
        put(youtube, "youtube", "SOCIAL", "followers", "youtube", day(12), 30, false);

        MetricView followers = metric(channel(overview7(), "SOCIAL"), "followers");

        assertThat(followers.value()).isEqualTo(190.0);
        assertThat(followers.previous()).isEqualTo(130.0);
        assertThat(followers.change()).isEqualTo(60.0);
    }

    @Test
    void derivedMetricsAreWeightedByTheirDenominatorNotAnAverageOfDailyRatios() {
        String gsc = connectFresh("gsc");
        // day A: 1 click / 10 impressions at position 2; day B: 9 clicks / 990 impressions at position 10
        put(gsc, "gsc", "SEARCH", "clicks", day(9), 1);
        put(gsc, "gsc", "SEARCH", "impressions", day(9), 10);
        put(gsc, "gsc", "SEARCH", "position_x_impressions", day(9), 20);
        put(gsc, "gsc", "SEARCH", "clicks", day(10), 9);
        put(gsc, "gsc", "SEARCH", "impressions", day(10), 990);
        put(gsc, "gsc", "SEARCH", "position_x_impressions", day(10), 9900);

        ChannelView search = channel(overview7(), "SEARCH");

        assertThat(metric(search, "ctr").value()).isCloseTo(0.01, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(metric(search, "position").value()).isCloseTo(9.92, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(search.metrics()).extracting(MetricView::metric).doesNotContain("position_x_impressions");
        assertThat(metric(search, "ctr").unit()).isEqualTo(MetricCatalog.Unit.PERCENT);
        assertThat(metric(search, "ctr").goodDirection()).isEqualTo("UP");
        assertThat(metric(search, "position").goodDirection()).isEqualTo("DOWN");
    }

    @Test
    void channelStatusIsLiveStaleOrNotConnected() {
        String web = connect("posthog", NOW.minusHours(1));          // within 2 x 360 min
        String search = connect("gsc", NOW.minusHours(13));          // past 2 x 360 min
        put(web, "posthog", "WEB", "visitors", day(10), 1);
        put(search, "gsc", "SEARCH", "clicks", day(10), 1);

        Overview overview = overview7();

        assertThat(channel(overview, "WEB").status()).isEqualTo("LIVE");
        assertThat(channel(overview, "WEB").connections()).singleElement()
                .satisfies(c -> assertThat(c.status()).isEqualTo("LIVE"));
        assertThat(channel(overview, "SEARCH").status()).isEqualTo("STALE");
        assertThat(channel(overview, "ADS").status()).isEqualTo("NOT_CONNECTED");
        assertThat(channel(overview, "ADS").connectHint().connectorId()).isEqualTo("apple-search-ads");
        assertThat(channel(overview, "ADS").connectHint().connectors()).singleElement()
                .satisfies(c -> assertThat(c.name()).isEqualTo("Apple Search Ads"));
        assertThat(channel(overview, "SOCIAL").connectHint().connectors())
                .extracting(c -> c.name()).containsExactly("Meta", "YouTube");
        assertThat(channel(overview, "STORE").status()).isEqualTo("NOT_CONNECTED");
        assertThat(channel(overview, "STORE").connectHint().connectors()).singleElement()
                .satisfies(c -> assertThat(c.name()).isEqualTo("App Store Connect"));
        assertThat(channel(overview, "WEB").lastFetchedAt()).isEqualTo(NOW.minusHours(1));
        assertThat(channel(overview, "PRODUCT").status()).isEqualTo("NOT_CONNECTED");
    }

    @Test
    void aSetupRequiredFeedMakesTheChannelStale() {
        String web = connectFresh("posthog");
        put(web, "posthog", "WEB", "visitors", day(10), 1);
        ConnectorFeed feed = feedRepository.findByConnectionId(web).get(0);
        feed.setStatus(ConnectorFeedStatus.SETUP_REQUIRED);
        feedRepository.saveAndFlush(feed);

        assertThat(channel(overview7(), "WEB").status()).isEqualTo("STALE");
    }

    @Test
    void theWindowEndsAtTheDataCompleteDayAndLaterProvisionalDaysAreShownButNotCounted() {
        String web = connectFresh("posthog");
        put(web, "posthog", "WEB", "visitors", "", day(12), 5, false);
        put(web, "posthog", "WEB", "visitors", "", day(13), 7, true);
        put(web, "posthog", "WEB", "visitors", "", day(14), 9, true);
        put(web, "posthog", "WEB", "visitors", "", day(3), 4, false);

        ChannelView web7 = channel(overview7(), "WEB");
        MetricView visitors = metric(web7, "visitors");

        assertThat(web7.dataCompleteThrough()).isEqualTo(day(12));
        assertThat(visitors.value()).isEqualTo(5.0);          // Oct 6-12; the provisional 7 and 9 are excluded
        assertThat(visitors.previous()).isNull();             // Sep 29 - Oct 5 is only partly covered: history starts Oct 3
        assertThat(visitors.historyStartsOn()).isEqualTo(day(3));
        assertThat(visitors.provisionalDays()).isEqualTo(2);
        assertThat(visitors.series()).hasSize(9);             // Oct 6-12 plus Oct 13 and 14
        assertThat(visitors.series().get(0).day()).isEqualTo(day(6));
        assertThat(visitors.series().get(6).provisional()).isFalse();
        assertThat(visitors.series().get(7).provisional()).isTrue();
        assertThat(visitors.series().get(8).value()).isEqualTo(9.0);
    }

    @Test
    void eachChannelEndsAtItsOwnDataCompleteDayAndTheOverallRangeSpansThem() {
        String web = connectFresh("posthog");
        String search = connectFresh("gsc");
        for (int d = 1; d <= 14; d++) {
            put(web, "posthog", "WEB", "visitors", "", day(d), 10, false);
            put(search, "gsc", "SEARCH", "clicks", "", day(d), 3, d > 11); // search lags three days
        }

        Overview overview = overview7();

        assertThat(channel(overview, "WEB").dataCompleteThrough()).isEqualTo(day(14));
        assertThat(channel(overview, "SEARCH").dataCompleteThrough()).isEqualTo(day(11));
        // a lagging source's seven complete days (Oct 5-11), not four settled ones against a full week
        assertThat(metric(channel(overview, "SEARCH"), "clicks").value()).isEqualTo(21.0);
        // Sep 28 - Oct 4 has rows only from Oct 1: partly covered, so no previous and no percent change
        assertThat(metric(channel(overview, "SEARCH"), "clicks").previous()).isNull();
        assertThat(metric(channel(overview, "SEARCH"), "clicks").historyStartsOn()).isEqualTo(day(1));
        assertThat(channel(overview, "ADS").dataCompleteThrough()).isEqualTo(day(13)); // no data: two days back
        // the overall range is the funnel's window (web is the only channel with funnel stages here)
        assertThat(overview.window().from()).isEqualTo(day(8));
        assertThat(overview.window().to()).isEqualTo(day(14));
        assertThat(overview.window().dataCompleteThroughMin()).isEqualTo(day(11));
        assertThat(overview.funnel().get(0).dataCompleteThrough()).isEqualTo(day(14));
    }

    @Test
    void everyFunnelStageUsesOneWindowEndingAtTheEarliestContributingChannelAndTheOverallRangeMatchesIt() {
        String web = connectFresh("posthog");
        String store = connectFresh("app-store-connect");
        put(web, "posthog", "WEB", "visitors", LocalDate.of(2026, 9, 30), 1);
        for (int d = 1; d <= 14; d++) {
            put(web, "posthog", "WEB", "visitors", day(d), d);          // complete through Oct 14
        }
        put(store, "app-store-connect", "STORE", "downloads", LocalDate.of(2026, 9, 30), 1);
        for (int d = 1; d <= 13; d++) {
            put(store, "app-store-connect", "STORE", "impressions", day(d), 100); // complete through Oct 13
            put(store, "app-store-connect", "STORE", "page_views", day(d), 10);
            put(store, "app-store-connect", "STORE", "downloads", day(d), 1);
        }

        Overview overview = overview7();

        // one window: Oct 7-13 against Sep 30 - Oct 6, no overlap, matching the funnel
        assertThat(overview.window().from()).isEqualTo(day(7));
        assertThat(overview.window().to()).isEqualTo(day(13));
        assertThat(overview.window().previousFrom()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(overview.window().previousTo()).isEqualTo(day(6));
        var visitors = overview.funnel().stream().filter(s -> s.key().equals("web_visitors")).findFirst().orElseThrow();
        var downloads = overview.funnel().stream().filter(s -> s.key().equals("store_downloads")).findFirst().orElseThrow();
        assertThat(visitors.value()).isEqualTo(70.0);           // 7+8+...+13, not Oct 8-14
        assertThat(visitors.previous()).isEqualTo(22.0);        // 1 + (1+2+...+6)
        assertThat(visitors.dataCompleteThrough()).isEqualTo(day(13));
        assertThat(downloads.value()).isEqualTo(7.0);
        assertThat(downloads.dataCompleteThrough()).isEqualTo(day(13));
        // the WEB card keeps its own window (Oct 8-14)
        assertThat(metric(channel(overview, "WEB"), "visitors").value()).isEqualTo(77.0);
        assertThat(channel(overview, "WEB").dataCompleteThrough()).isEqualTo(day(14));
        assertThat(channel(overview, "STORE").dataCompleteThrough()).isEqualTo(day(13));
    }

    @Test
    void theSlowestConnectionSetsTheDataCompleteDay() {
        String a = connectFresh("posthog");
        String b = connectFresh("posthog");
        put(a, "posthog", "WEB", "visitors", day(14), 1);
        put(b, "posthog", "WEB", "visitors", day(10), 1);

        assertThat(channel(overview7(), "WEB").dataCompleteThrough()).isEqualTo(day(10));
    }

    @Test
    void aSmallChangeIsNoiseAndALargeOneIsNot() {
        String web = connectFresh("posthog");
        for (int d = 1; d <= 7; d++) {
            put(web, "posthog", "WEB", "visitors", day(d), 10);
            put(web, "posthog", "WEB", "sessions", day(d), 10);
        }
        for (int d = 8; d <= 14; d++) {
            put(web, "posthog", "WEB", "visitors", day(d), 20);   // doubles
            put(web, "posthog", "WEB", "sessions", day(d), 10.5); // +5%
        }

        ChannelView web7 = channel(overview7(), "WEB");

        assertThat(metric(web7, "visitors").noise()).isFalse();
        assertThat(metric(web7, "sessions").noise()).isTrue();
    }

    @Test
    void optionalMetricsAreOmittedUntilTheyReportAndHeadlineIsCappedForTheOverview() {
        String web = connectFresh("posthog");
        put(web, "posthog", "WEB", "visitors", day(10), 1);

        ChannelView overviewWeb = channel(overview7(), "WEB");
        assertThat(overviewWeb.metrics()).extracting(MetricView::metric)
                .containsExactly("visitors", "sessions", "pageviews");

        put(web, "posthog", "WEB", "conversion_clicks", day(10), 2);
        assertThat(metric(channel(overview7(), "WEB"), "conversion_clicks").value()).isEqualTo(2.0);
    }

    @Test
    void funnelStagesAreOrderedAndUnavailableOnesCarryTheirReason() {
        String web = connectFresh("posthog");
        put(web, "posthog", "WEB", "visitors", day(10), 40);

        Overview overview = overview7();

        assertThat(overview.funnel()).extracting(s -> s.key()).containsExactly(
                "web_visitors", "web_conversion_clicks", "store_impressions", "store_page_views",
                "store_downloads", "ads_taps", "ads_installs", "product_stages");
        assertThat(overview.funnel().get(0).available()).isTrue();
        assertThat(overview.funnel().get(0).value()).isEqualTo(40.0);
        assertThat(overview.funnel().get(1).available()).isFalse();
        assertThat(overview.funnel().get(2).unavailableReason()).isEqualTo("Connect App Store Connect");
        assertThat(overview.funnel().get(7).unavailableReason()).isEqualTo("Connect product metrics");
        assertThat(overview.funnel().get(7).label()).isEqualTo("In-app stages (first open \u2192 paid)");
    }

    @Test
    void aChannelWhoseDataStoppedLongAgoIsStaleAndDoesNotHoldTheFunnelBack() {
        String web = connectFresh("posthog");
        String store = connectFresh("app-store-connect");
        for (int d = 1; d <= 10; d++) {
            put(web, "posthog", "WEB", "visitors", LocalDate.of(2026, 9, d), 5);
        }
        for (int d = 1; d <= 13; d++) {
            put(store, "app-store-connect", "STORE", "impressions", day(d), 100);
            put(store, "app-store-connect", "STORE", "page_views", day(d), 10);
            put(store, "app-store-connect", "STORE", "downloads", day(d), 1);
        }

        Overview overview = overview7();

        // the funnel and the overall range end at STORE's last settled day, not WEB's Sep 10
        assertThat(overview.window().to()).isEqualTo(day(13));
        assertThat(overview.window().from()).isEqualTo(day(7));
        var downloads = overview.funnel().stream().filter(s -> s.key().equals("store_downloads")).findFirst().orElseThrow();
        assertThat(downloads.available()).isTrue();
        assertThat(downloads.value()).isEqualTo(7.0);
        var visitors = overview.funnel().stream().filter(s -> s.key().equals("web_visitors")).findFirst().orElseThrow();
        assertThat(visitors.available()).isFalse();
        assertThat(visitors.unavailableReason()).isEqualTo("No Web data since Sep 10");
        assertThat(visitors.dataCompleteThrough()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(channel(overview, "WEB").status()).isEqualTo("STALE");
        assertThat(channel(overview, "STORE").status()).isEqualTo("LIVE");
    }

    @Test
    void channelsWithinAWeekOfEachOtherKeepTheCommonWindowAndStayLive() {
        String web = connectFresh("posthog");
        String store = connectFresh("app-store-connect");
        put(web, "posthog", "WEB", "visitors", day(7), 5);
        put(store, "app-store-connect", "STORE", "downloads", day(13), 1);

        Overview overview = overview7();

        assertThat(overview.window().to()).isEqualTo(day(7));
        assertThat(channel(overview, "WEB").status()).isEqualTo("LIVE");
        assertThat(channel(overview, "STORE").status()).isEqualTo("LIVE");
    }

    @Test
    void aConnectedChannelWithNoDataInTheFunnelWindowSaysSoInsteadOfAskingToConnect() {
        String web = connectFresh("posthog");
        String store = connectFresh("app-store-connect");
        put(web, "posthog", "WEB", "visitors", day(7), 5);
        for (int d = 10; d <= 13; d++) {
            put(store, "app-store-connect", "STORE", "downloads", day(d), 1);
        }

        Overview overview = overview7();

        // funnel window is Oct 1-7 (WEB's end); STORE only has Oct 10-13
        var downloads = overview.funnel().stream().filter(s -> s.key().equals("store_downloads")).findFirst().orElseThrow();
        assertThat(downloads.available()).isFalse();
        assertThat(downloads.unavailableReason()).isEqualTo("No App Store data for Oct 1 \u2013 Oct 7");
        assertThat(channel(overview, "STORE").status()).isEqualTo("LIVE");
    }

    @Test
    void multipleConnectionsForOneChannelAreSummed() {
        String a = connectFresh("posthog");
        String b = connectFresh("posthog");
        put(a, "posthog", "WEB", "visitors", day(10), 3);
        put(b, "posthog", "WEB", "visitors", day(10), 4);

        MetricView visitors = metric(channel(overview7(), "WEB"), "visitors");

        assertThat(visitors.value()).isEqualTo(7.0);
        assertThat(channel(overview7(), "WEB").connections()).hasSize(2);
    }

    @Test
    void dimensionTablesAreTopFiftyAggregatedWithCatalogRules() {
        String ads = connectFresh("apple-search-ads");
        for (int i = 0; i < 55; i++) {
            String key = "campaign:" + i + ":Camp " + i;
            put(ads, "apple-search-ads", "ADS", "spend", key, day(10), i + 1, false);
            put(ads, "apple-search-ads", "ADS", "spend", key, day(11), 1, false);
            put(ads, "apple-search-ads", "ADS", "installs", key, day(10), 2, false);
        }
        put(ads, "apple-search-ads", "ADS", "spend", "campaign:900:Brand: Spring", day(10), 1000, false);
        put(ads, "apple-search-ads", "ADS", "installs", "campaign:900:Brand: Spring", day(10), 10, false);

        MarketingOverviewQueryService.ChannelDetail detail = service.channel(project.getId(),
                MetricCatalog.channel("ads").orElseThrow(), 28, TODAY, NOW);

        MarketingOverviewQueryService.DimensionTable campaigns = detail.dimensions().get(0);
        assertThat(campaigns.key()).isEqualTo("campaigns");
        assertThat(campaigns.rows()).hasSize(50);
        assertThat(campaigns.rows().get(0).key()).isEqualTo("900");
        assertThat(campaigns.rows().get(0).label()).isEqualTo("Brand: Spring");
        assertThat(campaigns.rows().get(0).values().get(0)).isEqualTo(1000.0);
        // cost per install = spend / installs for the row
        assertThat(campaigns.rows().get(0).values().get(4)).isEqualTo(100.0);
        assertThat(campaigns.rows().get(1).key()).isEqualTo("54");
        assertThat(campaigns.rows().get(1).values().get(0)).isEqualTo(56.0);
        // the detail view carries every visible metric, not just the headline
        assertThat(detail.channel().metrics()).extracting(MetricView::metric)
                .contains("tap_through_rate", "impressions");
    }

    @Test
    void conversionRateIsDownloadsOverUniqueImpressionsAndUniqueImpressionsStayHidden() {
        String asc = connectFresh("app-store-connect");
        put(asc, "app-store-connect", "STORE", "downloads", day(9), 10);
        put(asc, "app-store-connect", "STORE", "downloads", day(10), 30);
        put(asc, "app-store-connect", "STORE", "impressions", day(9), 5000);
        put(asc, "app-store-connect", "STORE", "impressions", day(10), 5000);
        put(asc, "app-store-connect", "STORE", "impressions_unique", day(9), 400);
        put(asc, "app-store-connect", "STORE", "impressions_unique", day(10), 600);
        // per-source rows must not be added on top of the totals
        put(asc, "app-store-connect", "STORE", "downloads", "source:APP_STORE_SEARCH", day(10), 30, false);

        ChannelView store = service.channel(project.getId(), MetricCatalog.channel("STORE").orElseThrow(), 7, TODAY, NOW)
                .channel();

        assertThat(store.status()).isEqualTo("LIVE");
        assertThat(channel(overview7(), "STORE").metrics()).extracting(MetricView::metric)
                .containsExactly("impressions", "page_views", "downloads");
        assertThat(metric(store, "downloads").value()).isEqualTo(40.0);
        assertThat(metric(store, "conversion_rate").value()).isCloseTo(0.04, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(metric(store, "conversion_rate").unit()).isEqualTo(MetricCatalog.Unit.PERCENT);
        assertThat(store.metrics()).extracting(MetricView::metric)
                .doesNotContain("impressions_unique", "redownloads");
    }

    @Test
    void theSourcesTableLabelsEachSourceTypeAndCarriesADownloadsPerPageViewColumn() {
        String asc = connectFresh("app-store-connect");
        put(asc, "app-store-connect", "STORE", "impressions", "source:APP_STORE_SEARCH", day(9), 900, false);
        put(asc, "app-store-connect", "STORE", "page_views", "source:APP_STORE_SEARCH", day(9), 100, false);
        put(asc, "app-store-connect", "STORE", "downloads", "source:APP_STORE_SEARCH", day(9), 25, false);
        put(asc, "app-store-connect", "STORE", "impressions", "source:WEB_REFERRER", day(9), 100, false);
        put(asc, "app-store-connect", "STORE", "downloads", "source:WEB_REFERRER", day(9), 1, false);
        put(asc, "app-store-connect", "STORE", "impressions", day(9), 1000);
        put(asc, "app-store-connect", "STORE", "rating_count", day(10), 120);
        put(asc, "app-store-connect", "STORE", "rating_avg", day(10), 4.6);

        MarketingOverviewQueryService.ChannelDetail detail = service.channel(project.getId(),
                MetricCatalog.channel("STORE").orElseThrow(), 7, TODAY, NOW);

        MarketingOverviewQueryService.DimensionTable sources = detail.dimensions().get(0);
        assertThat(sources.key()).isEqualTo("sources");
        assertThat(sources.columns()).extracting(MarketingOverviewQueryService.ColumnView::key)
                .containsExactly("impressions", "page_views", "downloads", "download_rate");
        assertThat(sources.rows()).extracting(MarketingOverviewQueryService.DimensionRowView::label)
                .containsExactly("App Store search", "Web referrer");
        assertThat(sources.rows().get(0).key()).isEqualTo("APP_STORE_SEARCH");
        assertThat(sources.rows().get(0).values()).containsExactly(900.0, 100.0, 25.0, 0.25);
        assertThat(sources.rows().get(1).values().get(3)).isNull();
        assertThat(detail.channel().metrics()).extracting(MetricView::metric)
                .contains("rating_count", "rating_avg");
        assertThat(metric(detail.channel(), "rating_avg").value()).isEqualTo(4.6);
        assertThat(metric(detail.channel(), "rating_avg").unit()).isEqualTo(MetricCatalog.Unit.RATING);
        // the source rows do not inflate the total
        assertThat(metric(detail.channel(), "impressions").value()).isEqualTo(1000.0);
    }

    @Test
    void searchQueriesCarryAWeightedClickThroughRateColumn() {
        String gsc = connectFresh("gsc");
        put(gsc, "gsc", "SEARCH", "query_clicks", "best widget", day(9), 3, false);
        put(gsc, "gsc", "SEARCH", "query_clicks", "best widget", day(10), 1, false);
        put(gsc, "gsc", "SEARCH", "query_impressions", "best widget", day(9), 50, false);
        put(gsc, "gsc", "SEARCH", "query_impressions", "best widget", day(10), 150, false);

        MarketingOverviewQueryService.DimensionTable queries = service.channel(project.getId(),
                MetricCatalog.channel("SEARCH").orElseThrow(), 7, TODAY, NOW).dimensions().get(0);

        assertThat(queries.rows()).singleElement().satisfies(row -> {
            assertThat(row.label()).isEqualTo("best widget");
            assertThat(row.values()).containsExactly(4.0, 200.0, 0.02);
        });
    }

    @Test
    void aTargetIsIncludedOnItsMetricAndClearingItRemovesIt() {
        String web = connectFresh("posthog");
        put(web, "posthog", "WEB", "visitors", day(10), 1);

        MarketingOverviewQueryService.TargetView saved =
                service.setTarget(project.getId(), "web", "visitors", 500.0, null, user.getId());
        assertThat(saved.direction()).isEqualTo("UP");
        assertThat(metric(channel(overview7(), "WEB"), "visitors").target().value()).isEqualTo(500.0);

        service.setTarget(project.getId(), "WEB", "visitors", 800.0, "DOWN", user.getId());
        assertThat(metric(channel(overview7(), "WEB"), "visitors").target().value()).isEqualTo(800.0);
        assertThat(metric(channel(overview7(), "WEB"), "visitors").target().direction()).isEqualTo("DOWN");

        service.clearTarget(project.getId(), "WEB", "visitors");
        assertThat(metric(channel(overview7(), "WEB"), "visitors").target()).isNull();
    }

    @Test
    void targetsRejectUnknownChannelMetricAndBadValues() {
        assertThatThrownBy(() -> service.setTarget(project.getId(), "nope", "visitors", 1.0, null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Unknown channel");
        assertThatThrownBy(() -> service.setTarget(project.getId(), "WEB", "nope", 1.0, null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Unknown metric");
        assertThatThrownBy(() -> service.setTarget(project.getId(), "SEARCH", "position_x_impressions", 1.0, null, null))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Unknown metric");
        assertThatThrownBy(() -> service.setTarget(project.getId(), "WEB", "visitors", -1.0, null, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void windowsOutsideTheAllowedSetAreRejected() {
        assertThatThrownBy(() -> service.overview(project.getId(), "90d"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Unknown window");
        assertThatThrownBy(() -> service.overview(project.getId(), "week"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void snapshotsNeverHoldBackTheWindowAndTodaysSnapshotIsTheLatestLevel() {
        String meta = connectFresh("meta");
        // provisional snapshots written today, as the social feeds do
        put(meta, "meta", "SOCIAL", "followers", "facebook", day(15), 120, true);
        put(meta, "meta", "SOCIAL", "followers", "instagram", day(15), 80, true);

        ChannelView social = channel(overview7(), "SOCIAL");
        MetricView followers = metric(social, "followers");

        assertThat(social.dataCompleteThrough()).isEqualTo(day(15));
        assertThat(followers.value()).isEqualTo(200.0);
        assertThat(followers.previous()).isNull();
        assertThat(followers.historyStartsOn()).isEqualTo(day(15));
        assertThat(followers.asOf()).isEqualTo(day(15));
    }

    @Test
    void aSocialChannelWithFlowMetricsEndsAtTheirDataCompleteDay() {
        String meta = connectFresh("meta");
        put(meta, "meta", "SOCIAL", "website_clicks", "instagram", day(12), 4, false);
        put(meta, "meta", "SOCIAL", "website_clicks", "instagram", day(13), 6, true);
        put(meta, "meta", "SOCIAL", "followers", "instagram", day(10), 50, false);

        ChannelView social = channel(overview7(), "SOCIAL");

        assertThat(social.dataCompleteThrough()).isEqualTo(day(12));
        assertThat(metric(social, "website_clicks").value()).isEqualTo(4.0);
        assertThat(metric(social, "followers").value()).isEqualTo(50.0);
    }

    @Test
    void thePlatformsTableListsEveryConnectedPlatformWithItsLatestValues() {
        String meta = connectFresh("meta");
        String youtube = connectFresh("youtube");
        put(meta, "meta", "SOCIAL", "followers", "facebook", day(4), 90, false);
        put(meta, "meta", "SOCIAL", "followers", "facebook", day(15), 100, true);
        put(meta, "meta", "SOCIAL", "followers", "instagram", day(15), 200, true);
        put(meta, "meta", "SOCIAL", "website_clicks", "instagram", day(15), 7, true);
        put(youtube, "youtube", "SOCIAL", "followers", "youtube", day(15), 50, true);
        put(youtube, "youtube", "SOCIAL", "channel_views", "youtube", day(15), 999, true);

        MarketingOverviewQueryService.DimensionTable platforms = service.channel(project.getId(),
                MetricCatalog.channel("SOCIAL").orElseThrow(), 28, TODAY, NOW).dimensions().get(0);

        assertThat(platforms.rows()).extracting(r -> r.key())
                .containsExactly("instagram", "facebook", "youtube");
        assertThat(platforms.rows().get(1).values().get(0)).isEqualTo(100.0);
        assertThat(platforms.rows().get(2).values().get(2)).isEqualTo(999.0);
    }

    @Test
    void conversionsLinkOnlyStagesOnTheSamePathWhenBothAreAvailable() {
        String web = connectFresh("posthog");
        String ads = connectFresh("apple-search-ads");
        put(web, "posthog", "WEB", "visitors", day(10), 100);
        put(ads, "apple-search-ads", "ADS", "taps", day(10), 50);

        Overview onlyVisitors = overview7();
        assertThat(onlyVisitors.funnel()).filteredOn(s -> s.key().equals("web_conversion_clicks"))
                .singleElement().satisfies(s -> assertThat(s.conversionFromKey()).isNull());
        assertThat(onlyVisitors.funnel()).filteredOn(s -> s.key().equals("ads_installs"))
                .singleElement().satisfies(s -> assertThat(s.conversionFromKey()).isNull());

        put(web, "posthog", "WEB", "conversion_clicks", day(10), 10);
        put(ads, "apple-search-ads", "ADS", "installs", day(10), 5);
        Overview both = overview7();

        assertThat(both.funnel()).filteredOn(s -> s.key().equals("web_conversion_clicks"))
                .singleElement().satisfies(s -> {
                    assertThat(s.conversionFromKey()).isEqualTo("web_visitors");
                    assertThat(s.path()).isEqualTo("WEB");
                });
        assertThat(both.funnel()).filteredOn(s -> s.key().equals("ads_installs"))
                .singleElement().satisfies(s -> assertThat(s.conversionFromKey()).isEqualTo("ads_taps"));
        // path starts never convert from anything, and nothing converts across paths
        assertThat(both.funnel()).filteredOn(s -> s.key().equals("ads_taps") || s.key().equals("web_visitors"))
                .allSatisfy(s -> assertThat(s.conversionFromKey()).isNull());
    }

    @Test
    void levelsReadTheLatestSnapshotUpToTodayEvenWhenTheFlowWindowEndsEarlier() {
        String meta = connectFresh("meta");
        String youtube = connectFresh("youtube");
        // flows settle through Oct 13 only
        put(meta, "meta", "SOCIAL", "website_clicks", "instagram", day(13), 4, false);
        put(meta, "meta", "SOCIAL", "website_clicks", "instagram", day(14), 9, true);
        // snapshots: one week earlier, and today
        put(meta, "meta", "SOCIAL", "followers", "facebook", day(8), 90, false);
        put(meta, "meta", "SOCIAL", "followers", "instagram", day(8), 190, false);
        put(meta, "meta", "SOCIAL", "followers", "facebook", day(15), 100, false);
        put(meta, "meta", "SOCIAL", "followers", "instagram", day(15), 200, false);
        put(youtube, "youtube", "SOCIAL", "followers", "youtube", day(15), 50, false);
        put(youtube, "youtube", "SOCIAL", "channel_views", "youtube", day(15), 999, false);

        Overview overview = overview7();
        ChannelView social = channel(overview, "SOCIAL");
        MetricView followers = metric(social, "followers");

        assertThat(social.dataCompleteThrough()).isEqualTo(day(13));       // the flow window is unchanged
        assertThat(metric(social, "website_clicks").value()).isEqualTo(4.0);
        assertThat(followers.value()).isEqualTo(350.0);
        assertThat(followers.asOf()).isEqualTo(day(15));
        assertThat(followers.previous()).isEqualTo(280.0);                 // snapshots on or before Oct 8
        assertThat(followers.historyStartsOn()).isNull();
        assertThat(metric(social, "channel_views").value()).isEqualTo(999.0);
        assertThat(metric(social, "channel_views").previous()).isNull();
        assertThat(metric(social, "channel_views").historyStartsOn()).isEqualTo(day(15));
        assertThat(metric(social, "website_clicks").asOf()).isNull();

        MarketingOverviewQueryService.DimensionTable platforms = service.channel(project.getId(),
                MetricCatalog.channel("SOCIAL").orElseThrow(), 7, TODAY, NOW).dimensions().get(0);
        assertThat(platforms.rows()).extracting(r -> r.key())
                .containsExactlyInAnyOrder("instagram", "facebook", "youtube");
    }
}
