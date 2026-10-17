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
    void channelStatusIsLiveStaleNotConnectedOrComingSoon() {
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
        assertThat(channel(overview, "STORE").status()).isEqualTo("COMING_SOON");
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
        assertThat(visitors.previous()).isEqualTo(4.0);       // Sep 29 - Oct 5
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
        assertThat(metric(channel(overview, "SEARCH"), "clicks").previous()).isEqualTo(12.0); // Sep 28 - Oct 4; only Oct 1-4 have rows
        assertThat(channel(overview, "ADS").dataCompleteThrough()).isEqualTo(day(13)); // no data: two days back
        assertThat(overview.window().from()).isEqualTo(day(5));
        assertThat(overview.window().to()).isEqualTo(day(14));
        assertThat(overview.window().dataCompleteThroughMin()).isEqualTo(day(11));
        assertThat(overview.funnel().get(0).dataCompleteThrough()).isEqualTo(day(14));
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
        assertThat(overview.funnel().get(2).unavailableReason()).isEqualTo("Connect App Store Connect (coming soon)");
        assertThat(overview.funnel().get(7).unavailableReason()).isEqualTo("Connect product metrics");
        assertThat(overview.funnel().get(7).label()).isEqualTo("In-app stages (first open \u2192 paid)");
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
