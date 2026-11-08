package com.conductor.marketing.overview;

import com.conductor.entity.ChannelMetricDefinitionRow;
import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.generated.v2.model.MarketingDefinitionEntry;
import com.conductor.generated.v2.model.OverviewMetric;
import com.conductor.integration.MetricChannel;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.integration.ingest.ConnectorFeedStatus;
import com.conductor.marketing.overview.MarketingOverviewQueryService.ChannelView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.DimensionTable;
import com.conductor.marketing.overview.MarketingOverviewQueryService.MetricView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.Overview;
import com.conductor.marketing.overview.MarketingOverviewQueryService.Snapshot;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ChannelMetricDefinitionRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.ProjectSecurityService;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.conductor.v2.controller.MarketingDefinitionsController;
import com.conductor.v2.controller.MarketingOverviewController;
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
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;

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
    @Autowired private ChannelMetricDefinitionRepository definitionRepository;
    @Autowired private com.conductor.integration.ingest.ChannelMetricDailyStore store;
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager em;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ConnectorFeedRepository feedRepository;
    @Autowired private com.conductor.integration.ingest.ChannelDimensionStateStore stateStore;
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
        put(web, "posthog", "WEB", "sessions", day(14), 3);

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
            put(web, "posthog", "WEB", "sessions", day(d), 5);
        }
        for (int d = 8; d <= 14; d++) {
            put(web, "posthog", "WEB", "sessions", day(d), 10);
        }
        put(web, "posthog", "WEB", "sessions", day(15), 1000); // today: partial, must not count

        MetricView sessions = metric(channel(overview7(), "WEB"), "sessions");

        assertThat(sessions.value()).isEqualTo(70.0);
        assertThat(sessions.previous()).isEqualTo(35.0);
        assertThat(sessions.change()).isEqualTo(35.0);
        assertThat(sessions.changePct()).isEqualTo(1.0);
        assertThat(sessions.series()).hasSize(7);
        assertThat(sessions.series().get(0).day()).isEqualTo(day(8));
        assertThat(sessions.series().get(6).value()).isEqualTo(10.0);
    }

    @Test
    void aPreviousWindowOnlyPartlyCoveredByHistoryGivesNoPercentChangeJustWhereHistoryStarts() {
        String web = connectFresh("posthog");
        // history starts on the 5th: the previous window (1st-7th) holds only three of its seven days
        for (int d = 5; d <= 7; d++) {
            put(web, "posthog", "WEB", "sessions", day(d), 1);
        }
        for (int d = 8; d <= 14; d++) {
            put(web, "posthog", "WEB", "sessions", day(d), 10);
        }

        MetricView sessions = metric(channel(overview7(), "WEB"), "sessions");

        assertThat(sessions.value()).isEqualTo(70.0);
        assertThat(sessions.previous()).isNull();
        assertThat(sessions.change()).isNull();
        assertThat(sessions.changePct()).isNull();
        assertThat(sessions.historyStartsOn()).isEqualTo(day(5));
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
    void aChannelWithOnlyPointInTimeReadingsIsCompleteThroughItsLatestReadingAtMostYesterday() {
        String meta = connectFresh("meta");
        put(meta, "meta", "SOCIAL", "followers", "facebook", day(12), 100, false);
        assertThat(channel(overview7(), "SOCIAL").dataCompleteThrough()).isEqualTo(day(12));

        // a reading taken today is not a complete day
        put(meta, "meta", "SOCIAL", "followers", "facebook", day(15), 105, false);
        assertThat(channel(overview7(), "SOCIAL").dataCompleteThrough()).isEqualTo(day(14));
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
        put(web, "posthog", "WEB", "sessions", day(10), 1);
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
                .extracting(c -> c.name()).containsExactly("Meta", "YouTube", "TikTok");
        assertThat(channel(overview, "STORE").status()).isEqualTo("NOT_CONNECTED");
        assertThat(channel(overview, "STORE").connectHint().connectors()).singleElement()
                .satisfies(c -> assertThat(c.name()).isEqualTo("App Store Connect"));
        assertThat(channel(overview, "WEB").lastFetchedAt()).isEqualTo(NOW.minusHours(1));
        assertThat(channel(overview, "PRODUCT").status()).isEqualTo("NOT_CONNECTED");
    }

    @Test
    void aSetupRequiredFeedMakesTheChannelStale() {
        String web = connectFresh("posthog");
        put(web, "posthog", "WEB", "sessions", day(10), 1);
        ConnectorFeed feed = feedRepository.findByConnectionId(web).get(0);
        feed.setStatus(ConnectorFeedStatus.SETUP_REQUIRED);
        feedRepository.saveAndFlush(feed);

        assertThat(channel(overview7(), "WEB").status()).isEqualTo("STALE");
    }

    @Test
    void theWindowEndsAtTheDataCompleteDayAndLaterProvisionalDaysAreShownButNotCounted() {
        String web = connectFresh("posthog");
        put(web, "posthog", "WEB", "sessions", "", day(12), 5, false);
        put(web, "posthog", "WEB", "sessions", "", day(13), 7, true);
        put(web, "posthog", "WEB", "sessions", "", day(14), 9, true);
        put(web, "posthog", "WEB", "sessions", "", day(3), 4, false);

        ChannelView web7 = channel(overview7(), "WEB");
        MetricView sessions = metric(web7, "sessions");

        assertThat(web7.dataCompleteThrough()).isEqualTo(day(12));
        assertThat(sessions.value()).isEqualTo(5.0);          // Oct 6-12; the provisional 7 and 9 are excluded
        assertThat(sessions.previous()).isNull();             // Sep 29 - Oct 5 is only partly covered: history starts Oct 3
        assertThat(sessions.historyStartsOn()).isEqualTo(day(3));
        assertThat(sessions.provisionalDays()).isEqualTo(2);
        assertThat(sessions.series()).hasSize(9);             // Oct 6-12 plus Oct 13 and 14
        assertThat(sessions.series().get(0).day()).isEqualTo(day(6));
        assertThat(sessions.series().get(6).provisional()).isFalse();
        assertThat(sessions.series().get(7).provisional()).isTrue();
        assertThat(sessions.series().get(8).value()).isEqualTo(9.0);
    }

    @Test
    void eachChannelEndsAtItsOwnDataCompleteDayAndTheOverallRangeSpansThem() {
        String web = connectFresh("posthog");
        String search = connectFresh("gsc");
        for (int d = 1; d <= 14; d++) {
            put(web, "posthog", "WEB", "visitors", "", day(d), 10, false);
            put(web, "posthog", "WEB", "visitors_unique_7d", "", day(d), 10, false);
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
            put(web, "posthog", "WEB", "visitors_unique_7d", "", day(d), 100 + d, false);
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
        assertThat(visitors.value()).isEqualTo(113.0);          // unique over Oct 7-13, read on Oct 13, not Oct 14
        assertThat(visitors.previous()).isEqualTo(106.0);       // unique over Sep 30 - Oct 6, read on Oct 6
        assertThat(visitors.dataCompleteThrough()).isEqualTo(day(13));
        assertThat(downloads.value()).isEqualTo(7.0);
        assertThat(downloads.dataCompleteThrough()).isEqualTo(day(13));
        // the WEB card keeps its own window (Oct 8-14)
        assertThat(metric(channel(overview, "WEB"), "visitors").value()).isEqualTo(114.0);
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
            put(web, "posthog", "WEB", "pageviews", day(d), 10);
            put(web, "posthog", "WEB", "sessions", day(d), 10);
        }
        for (int d = 8; d <= 14; d++) {
            put(web, "posthog", "WEB", "pageviews", day(d), 20);  // doubles
            put(web, "posthog", "WEB", "sessions", day(d), 10.5); // +5%
        }

        ChannelView web7 = channel(overview7(), "WEB");

        assertThat(metric(web7, "pageviews").noise()).isFalse();
        assertThat(metric(web7, "sessions").noise()).isTrue();
    }

    @Test
    void optionalMetricsAreOmittedUntilTheyReportAndHeadlineIsCappedForTheOverview() {
        String web = connectFresh("posthog");
        put(web, "posthog", "WEB", "sessions", day(10), 1);

        ChannelView overviewWeb = channel(overview7(), "WEB");
        assertThat(overviewWeb.metrics()).extracting(MetricView::metric)
                .containsExactly("visitors", "sessions", "pageviews");

        put(web, "posthog", "WEB", "conversion_clicks", day(10), 2);
        assertThat(metric(channel(overview7(), "WEB"), "conversion_clicks").value()).isEqualTo(2.0);
    }

    @Test
    void theAlertsViewListsEveryMetricSoATargetPastTheCardsFirstFewIsStillJudged() {
        String web = connectFresh("posthog");
        put(web, "posthog", "WEB", "sessions", day(10), 1);
        put(web, "posthog", "WEB", "conversion_clicks", day(10), 2);
        // the fifth Web metric: past the card's four
        put(web, "posthog", "WEB", "conversion_sessions", day(10), 2);
        service.setTarget(project.getId(), "WEB", "conversion_sessions", 50.0, null, user.getId());

        java.util.List<String> card = channel(service.overview(project.getId(), "7d", TODAY, NOW), "WEB").metrics().stream()
                .map(MetricView::metric).toList();
        MetricView everyMetric = metric(channel(service.overviewWithEveryMetric(project.getId(), "7d", TODAY, NOW),
                "WEB"), "conversion_sessions");

        assertThat(card).hasSize(4).doesNotContain("conversion_sessions");
        assertThat(everyMetric.target().value()).isEqualTo(50.0);
        assertThat(everyMetric.status()).isEqualTo("OFF_TRACK");
    }

    @Test
    void anOptionalTargetedMetricThatStoppedReportingStaysInTheAlertsViewOnly() {
        String web = connectFresh("posthog");
        put(web, "posthog", "WEB", "sessions", day(10), 1);
        // conversion_sessions (optional) has a target but no rows: the card hides it, the alerts still see it
        service.setTarget(project.getId(), "WEB", "conversion_sessions", 50.0, null, user.getId());

        java.util.List<String> page = channel(service.overview(project.getId(), "7d", TODAY, NOW), "WEB").metrics()
                .stream().map(MetricView::metric).toList();
        MetricView alerts = metric(channel(service.overviewWithEveryMetric(project.getId(), "7d", TODAY, NOW),
                "WEB"), "conversion_sessions");

        assertThat(page).doesNotContain("conversion_sessions");
        assertThat(alerts.value()).isNull();
        assertThat(alerts.status()).isEqualTo("TOO_EARLY");
    }

    @Test
    void funnelStagesAreOrderedAndUnavailableOnesCarryTheirReason() {
        String web = connectFresh("posthog");
        put(web, "posthog", "WEB", "visitors", day(10), 40);
        put(web, "posthog", "WEB", "visitors_unique_7d", "", day(10), 40, false);

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
            put(web, "posthog", "WEB", "sessions", LocalDate.of(2026, 9, d), 5);
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
        put(web, "posthog", "WEB", "visitors_unique_7d", "", day(7), 5, false);
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
        put(web, "posthog", "WEB", "visitors_unique_7d", "", day(7), 5, false);
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
    void theChannelDetailAppliesTheSameLagRuleAsTheOverview() {
        String web = connectFresh("posthog");
        String store = connectFresh("app-store-connect");
        for (int d = 1; d <= 10; d++) {
            put(web, "posthog", "WEB", "sessions", LocalDate.of(2026, 9, d), 5);
        }
        for (int d = 1; d <= 13; d++) {
            put(store, "app-store-connect", "STORE", "downloads", day(d), 1);
        }

        var webDetail = service.channel(project.getId(), MetricCatalog.channel("WEB").orElseThrow(), 7, TODAY, NOW);
        var storeDetail = service.channel(project.getId(), MetricCatalog.channel("STORE").orElseThrow(), 7, TODAY, NOW);

        assertThat(webDetail.channel().status()).isEqualTo("STALE");
        assertThat(storeDetail.channel().status()).isEqualTo("LIVE");
    }

    /** One daily pull of a trailing-3 connector the way the puller issues it: window [d-3, d], provisional from d-2. */
    private void dailyPull(String connectionId, LocalDate pullDay) {
        java.util.List<com.conductor.integration.ChannelMetricPoint> points = new java.util.ArrayList<>();
        for (int i = 3; i >= 0; i--) {
            points.add(com.conductor.integration.ChannelMetricPoint.total("downloads", pullDay.minusDays(i), 10));
        }
        store.replaceWindow(project.getId(), connectionId, "app-store-connect", "STORE", pullDay.minusDays(3), pullDay,
                pullDay.minusDays(3).plusDays(1), points);
    }

    @Test
    void consecutiveDailyPullsAdvanceDataCompleteThroughByOneDayEach() {
        String store = connectFresh("app-store-connect");
        dailyPull(store, day(10));
        dailyPull(store, day(11));
        dailyPull(store, day(12));
        dailyPull(store, day(13));
        dailyPull(store, day(14));

        // pulled on Oct 14: Oct 11 (the oldest re-read day) is settled, Oct 12-14 are still restating
        assertThat(service.overview(project.getId(), 7, day(15), NOW).channels().stream()
                .filter(c -> c.channel().equals("STORE")).findFirst().orElseThrow().dataCompleteThrough())
                .isEqualTo(day(11));
        dailyPull(store, day(15));
        em.clear();
        assertThat(service.overview(project.getId(), 7, day(16), NOW.plusDays(1)).channels().stream()
                .filter(c -> c.channel().equals("STORE")).findFirst().orElseThrow().dataCompleteThrough())
                .isEqualTo(day(12));
    }

    @Test
    void aPullSettlesRowsAnEarlierPullLeftProvisionalButNeverTouchesOtherConnections() {
        String stuck = connectFresh("app-store-connect");
        String other = connectFresh("apple-search-ads");
        for (int d = 1; d <= 12; d++) {
            put(stuck, "app-store-connect", "STORE", "downloads", "", day(d), 1, true);
            put(other, "apple-search-ads", "ADS", "spend", "", day(d), 1, true);
        }

        dailyPull(stuck, day(14));
        dailyPull(stuck, day(14));

        em.clear();
        var rows = dailyRepository.findByConnectionIdOrderByDayAscMetricAscDimensionKeyAsc(stuck);
        assertThat(rows).filteredOn(r -> r.getDay().isBefore(day(12))).allMatch(r -> !r.isProvisional());
        assertThat(rows).filteredOn(r -> r.getDay().isAfter(day(11))).allMatch(r -> r.isProvisional());
        assertThat(dailyRepository.findByConnectionIdOrderByDayAscMetricAscDimensionKeyAsc(other))
                .allMatch(r -> r.isProvisional());
    }

    @Test
    void theChannelDetailReportsTheRealLastSettledDayAndStaleWhenTheLookbackHoldsNoSettledRows() {
        String web = connectFresh("posthog");
        for (int d = 1; d <= 10; d++) {
            put(web, "posthog", "WEB", "visitors", LocalDate.of(2026, 8, d), 5);
        }

        var detail = service.channel(project.getId(), MetricCatalog.channel("WEB").orElseThrow(), 7, TODAY, NOW);

        assertThat(detail.channel().status()).isEqualTo("STALE");
        assertThat(detail.channel().dataCompleteThrough()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(detail.window().to()).isEqualTo(LocalDate.of(2026, 8, 10));
    }

    @Test
    void aChannelWhoseRealSettledEndIsTwoDaysBackStaysLiveWithThatWindow() {
        String store = connectFresh("app-store-connect");
        for (int d = 1; d <= 13; d++) {
            put(store, "app-store-connect", "STORE", "downloads", day(d), 1);
        }

        var detail = service.channel(project.getId(), MetricCatalog.channel("STORE").orElseThrow(), 7, TODAY, NOW);

        assertThat(detail.channel().status()).isEqualTo("LIVE");
        assertThat(detail.channel().dataCompleteThrough()).isEqualTo(day(13));
        assertThat(channel(overview7(), "STORE").status()).isEqualTo("LIVE");
    }

    @Test
    void aConnectedChannelWithOnlyOldSettledRowsIsStaleOnTheOverviewToo() {
        String web = connectFresh("posthog");
        put(web, "posthog", "WEB", "visitors", LocalDate.of(2026, 8, 10), 5);

        ChannelView view = channel(overview7(), "WEB");

        assertThat(view.status()).isEqualTo("STALE");
        assertThat(view.dataCompleteThrough()).isEqualTo(LocalDate.of(2026, 8, 10));
        assertThat(view.noDataYet()).isNull();
    }

    @Test
    void aConnectedChannelWithNoRowsAtAllKeepsTheTwoDayFallbackAndSaysItHasNoDataYet() {
        connectFresh("posthog");

        ChannelView view = channel(overview7(), "WEB");

        assertThat(view.dataCompleteThrough()).isEqualTo(day(13));
        assertThat(view.noDataYet()).isTrue();
    }

    @Test
    void aChannelWithSettledDataHasData() {
        String web = connectFresh("posthog");
        put(web, "posthog", "WEB", "visitors", day(13), 5);

        assertThat(channel(overview7(), "WEB").noDataYet()).isNull();
    }

    @Test
    void aConnectedChannelMissingOneFunnelMetricSaysNoDataNotConnect() {
        String web = connectFresh("posthog");
        String store = connectFresh("app-store-connect");
        put(web, "posthog", "WEB", "visitors", day(10), 5);
        // STORE reports downloads and page views but never impressions
        for (int d = 5; d <= 13; d++) {
            put(store, "app-store-connect", "STORE", "downloads", day(d), 1);
            put(store, "app-store-connect", "STORE", "page_views", day(d), 3);
        }

        Overview overview = overview7();

        var impressions = overview.funnel().stream().filter(s -> s.key().equals("store_impressions")).findFirst().orElseThrow();
        assertThat(impressions.available()).isFalse();
        assertThat(impressions.unavailableReason()).startsWith("No App Store data for ");
        assertThat(overview.funnel().stream().filter(s -> s.key().equals("ads_taps")).findFirst().orElseThrow()
                .unavailableReason()).isEqualTo("Connect an ads account");
    }

    @Test
    void multipleConnectionsForOneChannelAreSummed() {
        String a = connectFresh("posthog");
        String b = connectFresh("posthog");
        put(a, "posthog", "WEB", "sessions", day(10), 3);
        put(b, "posthog", "WEB", "sessions", day(10), 4);

        MetricView sessions = metric(channel(overview7(), "WEB"), "sessions");

        assertThat(sessions.value()).isEqualTo(7.0);
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
    void keywordAndAdTablesAreSeparateFromCampaignsAndCarryTheirCurrentState() {
        String ads = connectFresh("apple-search-ads");
        String kw = "keyword:77:meal planner";
        put(ads, "apple-search-ads", "ADS", "impressions", kw, day(10), 1000, false);
        put(ads, "apple-search-ads", "ADS", "taps", kw, day(10), 50, false);
        put(ads, "apple-search-ads", "ADS", "installs", kw, day(10), 10, false);
        put(ads, "apple-search-ads", "ADS", "spend", kw, day(10), 25, false);
        put(ads, "apple-search-ads", "ADS", "impressions", "keyword:78:no state", day(10), 10, false);
        put(ads, "apple-search-ads", "ADS", "spend", "campaign:5:Brand", day(10), 99, false);
        put(ads, "apple-search-ads", "ADS", "impressions", "ad:55:9:Variant A", day(10), 400, false);
        put(ads, "apple-search-ads", "ADS", "impressions", "ad:55:10:Variant: B", day(10), 300, false);
        stateStore.upsert(ads, java.util.List.of(new com.conductor.integration.DimensionState(kw,
                java.util.Map.of("status", "ACTIVE", "bidAmount", 1.5, "matchType", "EXACT",
                        "adGroupName", "Core", "campaignName", "Brand"))));

        MarketingOverviewQueryService.ChannelDetail detail = service.channel(project.getId(),
                MetricCatalog.channel("ads").orElseThrow(), 28, TODAY, NOW);

        assertThat(detail.dimensions()).extracting(MarketingOverviewQueryService.DimensionTable::key)
                .containsExactly("campaigns", "keywords", "ads", "searchterms");
        assertThat(detail.dimensions().get(0).rows()).extracting(MarketingOverviewQueryService.DimensionRowView::key)
                .containsExactly("5");
        MarketingOverviewQueryService.DimensionTable keywords = detail.dimensions().get(1);
        assertThat(keywords.columns()).extracting(MarketingOverviewQueryService.ColumnView::key)
                .containsExactly("impressions", "taps", "tap_through_rate", "installs", "new_downloads",
                        "install_rate", "spend", "cost_per_install");
        MarketingOverviewQueryService.DimensionRowView row = keywords.rows().get(0);
        assertThat(row.key()).isEqualTo("77");
        assertThat(row.label()).isEqualTo("meal planner");
        assertThat(row.values()).containsExactly(1000.0, 50.0, 0.05, 10.0, null, 0.2, 25.0, 2.5);
        assertThat(row.state()).containsEntry("status", "ACTIVE").containsEntry("bidAmount", 1.5)
                .containsEntry("matchType", "EXACT").containsEntry("adGroupName", "Core");
        assertThat(keywords.rows().get(1).state()).isNull();
        assertThat(detail.dimensions().get(2).rows()).extracting(MarketingOverviewQueryService.DimensionRowView::label)
                .containsExactly("Variant A", "Variant: B");
        assertThat(detail.dimensions().get(2).rows()).extracting(MarketingOverviewQueryService.DimensionRowView::key)
                .containsExactly("55:9", "55:10");
        // keyword and ad rows never inflate the channel totals
        assertThat(metric(detail.channel(), "impressions").value()).isNull();
    }

    @Test
    void dimensionStateIsUpsertedInPlace() {
        String ads = connectFresh("apple-search-ads");
        stateStore.upsert(ads, java.util.List.of(new com.conductor.integration.DimensionState("keyword:1:a",
                java.util.Map.of("status", "ACTIVE", "bidAmount", 1.0))));
        stateStore.upsert(ads, java.util.List.of(new com.conductor.integration.DimensionState("keyword:1:a",
                java.util.Map.of("status", "PAUSED", "bidAmount", 2.0))));

        java.util.Map<String, java.util.Map<String, Object>> found = stateStore.find(java.util.List.of(ads), "keyword:");

        assertThat(found).hasSize(1);
        assertThat(found.get("keyword:1:a")).containsEntry("status", "PAUSED").containsEntry("bidAmount", 2.0);
        assertThat(stateStore.find(java.util.List.of(ads), "ad:")).isEmpty();
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

        // a reading taken today is the latest level, but today is not a complete day
        assertThat(social.dataCompleteThrough()).isEqualTo(day(14));
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
        put(web, "posthog", "WEB", "visitors_unique_7d", "", day(10), 100, false);
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

    @Test
    void aTargetedSnapshotTheFeedStoppedSendingIsStoppedNotJudgedAndSaysWhenItWasLastReported() {
        String meta = connectFresh("meta");
        put(meta, "meta", "SOCIAL", "website_clicks", "instagram", day(13), 4, false);
        // ten days behind the flows: more than a week
        put(meta, "meta", "SOCIAL", "followers", "instagram", day(3), 50, false);
        service.setTarget(project.getId(), "SOCIAL", "followers", 1000.0, null, user.getId());

        MetricView followers = metric(channel(overview7(), "SOCIAL"), "followers");

        assertThat(followers.value()).isEqualTo(50.0);
        assertThat(followers.asOf()).isEqualTo(day(3));
        assertThat(followers.status()).isEqualTo(MarketingOverviewQueryService.STOPPED);
        assertThat(followers.missPct()).isNull();
        assertThat(followers.note()).isEqualTo("Last reported 2026-10-03; the rest of the Social data runs to 2026-10-13.");
    }

    @Test
    void aSnapshotWithinAWeekOfItsChannelIsStillJudged() {
        String meta = connectFresh("meta");
        put(meta, "meta", "SOCIAL", "website_clicks", "instagram", day(13), 4, false);
        put(meta, "meta", "SOCIAL", "followers", "instagram", day(6), 50, false);
        service.setTarget(project.getId(), "SOCIAL", "followers", 1000.0, null, user.getId());

        MetricView followers = metric(channel(overview7(), "SOCIAL"), "followers");

        assertThat(followers.status()).isEqualTo("OFF_TRACK");
        assertThat(followers.note()).isNull();
    }

    @Test
    void aSnapshotLastReportedBeforeTheLoadedHistoryKeepsThatDayAndStaysStopped() {
        String meta = connectFresh("meta");
        put(meta, "meta", "SOCIAL", "website_clicks", "instagram", day(13), 4, false);
        // the 7-day overview loads 42 days of history, back to Sep 3
        put(meta, "meta", "SOCIAL", "followers", "instagram", LocalDate.of(2026, 8, 12), 40, false);
        put(meta, "meta", "SOCIAL", "followers", "facebook", LocalDate.of(2026, 8, 20), 50, false);
        service.setTarget(project.getId(), "SOCIAL", "followers", 1000.0, null, user.getId());

        MetricView followers = metric(channel(overview7(), "SOCIAL"), "followers");

        assertThat(followers.value()).isNull();
        assertThat(followers.asOf()).isNull();
        assertThat(followers.lastReportedOn()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(followers.status()).isEqualTo(MarketingOverviewQueryService.STOPPED);
        assertThat(followers.note()).startsWith("Last reported 2026-08-20;");
    }

    @Test
    void aTargetedFlowWithNoRowsInTheWindowIsTooEarlyNotStoppedSinceItMayHaveHadNoEvents() {
        // not every feed writes zero days, so a flow's missing rows are no proof the feed stopped sending it
        String ads = connectFresh("apple-search-ads");
        put(ads, "apple-search-ads", "ADS", "taps", day(13), 40);
        put(ads, "apple-search-ads", "ADS", "installs", LocalDate.of(2026, 9, 20), 5);
        service.setTarget(project.getId(), "ADS", "installs", 60.0, null, user.getId());

        MetricView installs = metric(channel(overview7(), "ADS"), "installs");

        assertThat(installs.value()).isNull();
        assertThat(installs.status()).isEqualTo("TOO_EARLY");
        assertThat(installs.lastReportedOn()).isNull();
    }

    @Test
    void aTargetedFlowNeverReportedIsTooEarlyNotStopped() {
        String ads = connectFresh("apple-search-ads");
        put(ads, "apple-search-ads", "ADS", "taps", day(13), 40);
        service.setTarget(project.getId(), "ADS", "installs", 60.0, null, user.getId());

        MetricView installs = metric(channel(overview7(), "ADS"), "installs");

        assertThat(installs.status()).isEqualTo("TOO_EARLY");
        assertThat(installs.note()).isNull();
    }

    // ---- RE-99 T3.1: definitions, window-unique visitors, per-source conversion, new metrics ------------

    private static final String UNSUPPORTED_NOTE = "Unique visitors are only available for 7, 28 or 90-day windows.";

    private static String notStoredNote(int days) {
        return "No unique visitors count is stored for these " + days + " days yet.";
    }
    private static final org.assertj.core.data.Offset<Double> TINY = org.assertj.core.data.Offset.offset(1e-9);

    private void putUnique(String web, int windowDays, LocalDate day, double value) {
        put(web, "posthog", "WEB", "visitors_unique_" + windowDays + "d", "", day, value, false);
    }

    private static DimensionTable table(MarketingOverviewQueryService.ChannelDetail detail, String key) {
        return detail.dimensions().stream().filter(t -> t.key().equals(key)).findFirst().orElseThrow();
    }

    private static Double cell(DimensionTable table, MarketingOverviewQueryService.DimensionRowView row, String column) {
        for (int i = 0; i < table.columns().size(); i++) {
            if (table.columns().get(i).key().equals(column)) {
                return row.values().get(i);
            }
        }
        throw new AssertionError("no column " + column);
    }

    private MarketingOverviewQueryService.ChannelDetail detail(String channel, int days) {
        return service.channel(project.getId(), MetricCatalog.channel(channel).orElseThrow(), days, TODAY, NOW);
    }

    @Test
    void webVisitorsAreTheStoredWindowUniqueCountAtTheWindowEndNeverTheSumOfDailyCounts() {
        String web = connectFresh("posthog");
        LocalDate first = day(14).minusDays(27);
        for (LocalDate d = first.minusDays(28); !d.isAfter(day(14)); d = d.plusDays(1)) {
            // the current window is 27 days of 2 and one of 3: 57 daily visits by 49 distinct people
            put(web, "posthog", "WEB", "visitors", d, d.equals(day(14)) ? 3 : 2);
        }
        putUnique(web, 28, day(14), 49);
        putUnique(web, 28, day(13), 50);                  // another day's count must not stand in
        putUnique(web, 28, first.minusDays(1), 40);       // the previous window's last day
        putUnique(web, 7, day(14), 20);                   // another window length must not stand in

        MetricView visitors = metric(channel(service.overview(project.getId(), 28, TODAY, NOW), "WEB"), "visitors");

        assertThat(visitors.value()).isEqualTo(49.0);
        assertThat(visitors.previous()).isEqualTo(40.0);
        assertThat(visitors.change()).isEqualTo(9.0);
        assertThat(visitors.note()).isNull();
        assertThat(visitors.aggregation()).isEqualTo("WINDOW_UNIQUE");
        assertThat(visitors.series()).hasSize(28);
        assertThat(visitors.series().stream().mapToDouble(MarketingOverviewQueryService.Point::value).sum())
                .isEqualTo(57.0);
    }

    @Test
    void theWindowLengthPicksWhichStoredUniqueSeriesIsRead() {
        String web = connectFresh("posthog");
        put(web, "posthog", "WEB", "visitors", day(14), 5);
        putUnique(web, 7, day(14), 11);
        putUnique(web, 28, day(14), 22);
        putUnique(web, 90, day(14), 33);

        assertThat(metric(channel(overview7(), "WEB"), "visitors").value()).isEqualTo(11.0);
        assertThat(metric(channel(service.overview(project.getId(), 28, TODAY, NOW), "WEB"), "visitors").value())
                .isEqualTo(22.0);
        assertThat(metric(detail("WEB", 90).channel(), "visitors").value()).isEqualTo(33.0);
    }

    @Test
    void visitorsAreNullWithANoteWhenNoUniqueCountIsStoredForTheWindowAndDailyCountsAreNeverAdded() {
        String web = connectFresh("posthog");
        for (int d = 1; d <= 14; d++) {
            put(web, "posthog", "WEB", "visitors", day(d), 3);
        }
        putUnique(web, 28, day(14), 49);                  // the wrong length for a 7-day window
        putUnique(web, 7, day(13), 10);                   // the right length, the wrong day

        MetricView sevenDay = metric(channel(overview7(), "WEB"), "visitors");

        assertThat(sevenDay.value()).isNull();
        assertThat(sevenDay.previous()).isNull();
        assertThat(sevenDay.change()).isNull();
        assertThat(sevenDay.note()).isEqualTo(notStoredNote(7));
        assertThat(sevenDay.series()).hasSize(7);         // the chart still draws the daily rows
        assertThat(sevenDay.series().get(0).value()).isEqualTo(3.0);

        // 14 days is not a stored length, even with all three stored series present
        putUnique(web, 7, day(14), 10);
        putUnique(web, 90, day(14), 60);
        MetricView fortnight = metric(channel(service.overview(project.getId(), 14, TODAY, NOW), "WEB"), "visitors");
        assertThat(fortnight.value()).isNull();
        assertThat(fortnight.note()).isEqualTo(UNSUPPORTED_NOTE);
    }

    @Test
    void aVisitorCountWithNoPreviousCountStillShowsItsValueWithoutANote() {
        String web = connectFresh("posthog");
        put(web, "posthog", "WEB", "visitors", day(14), 3);
        putUnique(web, 7, day(14), 10);

        MetricView visitors = metric(channel(overview7(), "WEB"), "visitors");

        assertThat(visitors.value()).isEqualTo(10.0);
        assertThat(visitors.previous()).isNull();
        assertThat(visitors.note()).isNull();
    }

    @Test
    void conversionSessionsAreAnOptionalMetricShownOnceTheConnectorWritesThem() {
        String web = connectFresh("posthog");
        put(web, "posthog", "WEB", "sessions", day(10), 68);
        assertThat(detail("WEB", 7).channel().metrics()).extracting(MetricView::metric)
                .doesNotContain("conversion_sessions");

        put(web, "posthog", "WEB", "conversion_sessions", day(10), 12);

        MetricView sessions = metric(detail("WEB", 7).channel(), "conversion_sessions");
        assertThat(sessions.value()).isEqualTo(12.0);
        assertThat(sessions.label()).isEqualTo("Conversion sessions");
    }

    @Test
    void perSourceConversionIsTotalDownloadsOverThatSourcesUniqueImpressionsAloneAndTheAllSourceRateStaysFirstTime() {
        String asc = connectFresh("app-store-connect");
        put(asc, "app-store-connect", "STORE", "downloads", day(10), 20);
        put(asc, "app-store-connect", "STORE", "downloads", day(11), 10);
        put(asc, "app-store-connect", "STORE", "impressions_unique", day(10), 3000);
        put(asc, "app-store-connect", "STORE", "impressions_unique", day(11), 2652);
        put(asc, "app-store-connect", "STORE", "total_downloads", day(10), 99);   // the all-source total reaches no source
        String search = "source:APP_STORE_SEARCH";
        put(asc, "app-store-connect", "STORE", "total_downloads", search, day(10), 20, false);
        put(asc, "app-store-connect", "STORE", "total_downloads", search, day(11), 13, false);
        put(asc, "app-store-connect", "STORE", "impressions_unique", search, day(10), 3000, false);
        put(asc, "app-store-connect", "STORE", "impressions_unique", search, day(11), 2639, false);
        put(asc, "app-store-connect", "STORE", "downloads", search, day(10), 29, false); // first-time: not the numerator
        String browse = "source:APP_STORE_BROWSE";
        put(asc, "app-store-connect", "STORE", "total_downloads", browse, day(10), 2, false);
        put(asc, "app-store-connect", "STORE", "impressions_unique", browse, day(10), 50, false);
        // impressions but no downloads for this source: its rate cannot be computed, so it is not shown
        put(asc, "app-store-connect", "STORE", "impressions_unique", "source:APP_REFERRER", day(10), 80, false);

        ChannelView store = detail("STORE", 7).channel();

        assertThat(metric(store, "conversion_rate_search").value()).isCloseTo(33.0 / 5639.0, TINY);
        assertThat(metric(store, "conversion_rate_search").unit()).isEqualTo(MetricCatalog.Unit.PERCENT);
        assertThat(metric(store, "conversion_rate_browse").value()).isCloseTo(0.04, TINY);
        assertThat(metric(store, "conversion_rate").value()).isCloseTo(30.0 / 5652.0, TINY);
        assertThat(store.metrics()).extracting(MetricView::metric).doesNotContain("conversion_rate_referrer",
                "conversion_rate_web", "total_downloads", "search_total_downloads", "search_impressions_unique");
    }

    @Test
    void downloadsOfRecordAreAnOptionalSumShownOnceSalesReportsAreRead() {
        String asc = connectFresh("app-store-connect");
        put(asc, "app-store-connect", "STORE", "downloads", day(10), 5);
        assertThat(detail("STORE", 7).channel().metrics()).extracting(MetricView::metric)
                .doesNotContain("downloads_of_record", "sales_report_days", "total_downloads");

        put(asc, "app-store-connect", "STORE", "downloads_of_record", day(10), 4);
        put(asc, "app-store-connect", "STORE", "downloads_of_record", day(11), 3);
        put(asc, "app-store-connect", "STORE", "sales_report_days", day(10), 1);
        put(asc, "app-store-connect", "STORE", "sales_report_days", day(11), 1);

        ChannelView store = detail("STORE", 7).channel();
        assertThat(metric(store, "downloads_of_record").value()).isEqualTo(7.0);
        assertThat(metric(store, "downloads_of_record").label()).isEqualTo("Downloads of record");
        assertThat(store.metrics()).extracting(MetricView::metric).doesNotContain("sales_report_days");
    }

    @Test
    void viewThroughInstallsAndFirstTimeDownloadsAppearOnlyOnceTheirRowsExistAndAreNeverAddedToInstalls() {
        String ads = connectFresh("apple-search-ads");
        put(ads, "apple-search-ads", "ADS", "installs", day(10), 23);
        put(ads, "apple-search-ads", "ADS", "spend", day(10), 162.86);

        ChannelView before = detail("ADS", 28).channel();
        assertThat(before.metrics()).extracting(MetricView::metric).doesNotContain("view_installs", "new_downloads");

        put(ads, "apple-search-ads", "ADS", "view_installs", day(10), 1);
        put(ads, "apple-search-ads", "ADS", "new_downloads", day(10), 20);

        ChannelView after = detail("ADS", 28).channel();
        assertThat(metric(after, "view_installs").value()).isEqualTo(1.0);
        assertThat(metric(after, "view_installs").label()).isEqualTo("View-through installs");
        assertThat(metric(after, "new_downloads").value()).isEqualTo(20.0);
        assertThat(metric(after, "new_downloads").label()).isEqualTo("First-time downloads");
        assertThat(metric(after, "installs").value()).isEqualTo(23.0);
        assertThat(metric(after, "cost_per_install").value()).isCloseTo(162.86 / 23, TINY);
    }

    @Test
    void keywordAndAdTablesCountTapInstallsLikeCampaignsAndListFirstTimeDownloadsInTheirOwnColumn() {
        String ads = connectFresh("apple-search-ads");
        String kw = "keyword:77:meal planner";
        put(ads, "apple-search-ads", "ADS", "taps", kw, day(10), 50, false);
        put(ads, "apple-search-ads", "ADS", "installs", kw, day(10), 10, false);
        put(ads, "apple-search-ads", "ADS", "new_downloads", kw, day(10), 7, false);
        put(ads, "apple-search-ads", "ADS", "spend", kw, day(10), 25, false);
        String ad = "ad:55:9:Variant A";
        put(ads, "apple-search-ads", "ADS", "installs", ad, day(10), 4, false);
        put(ads, "apple-search-ads", "ADS", "new_downloads", ad, day(10), 3, false);

        var detail = detail("ADS", 28);

        for (String key : java.util.List.of("keywords", "ads")) {
            DimensionTable t = table(detail, key);
            assertThat(t.columns()).filteredOn(c -> c.key().equals("installs"))
                    .singleElement().satisfies(c -> assertThat(c.label()).isEqualTo("Installs"));
            assertThat(t.columns()).filteredOn(c -> c.key().equals("new_downloads"))
                    .singleElement().satisfies(c -> assertThat(c.label()).isEqualTo("First-time downloads"));
        }
        DimensionTable keywords = table(detail, "keywords");
        assertThat(cell(keywords, keywords.rows().get(0), "installs")).isEqualTo(10.0);
        assertThat(cell(keywords, keywords.rows().get(0), "new_downloads")).isEqualTo(7.0);
        assertThat(cell(keywords, keywords.rows().get(0), "cost_per_install")).isEqualTo(2.5);
        DimensionTable ads2 = table(detail, "ads");
        assertThat(cell(ads2, ads2.rows().get(0), "new_downloads")).isEqualTo(3.0);
    }

    @Test
    void theSearchTermsTableListsSearchTermRowsPerCampaignAndTheyNeverReachOtherTablesOrTotals() {
        String ads = connectFresh("apple-search-ads");
        String planner = "searchterm:11:meal planner";
        String samePlannerElsewhere = "searchterm:12:meal planner";
        String lowVolume = "searchterm:11:(low volume)";
        for (String key : java.util.List.of(planner, samePlannerElsewhere, lowVolume)) {
            double scale = key.equals(planner) ? 1 : key.equals(samePlannerElsewhere) ? 0.2 : 0.06;
            put(ads, "apple-search-ads", "ADS", "impressions", key, day(10), 500 * scale, false);
            put(ads, "apple-search-ads", "ADS", "taps", key, day(10), 20 * scale, false);
            put(ads, "apple-search-ads", "ADS", "installs", key, day(10), 4 * scale, false);
            put(ads, "apple-search-ads", "ADS", "spend", key, day(10), 10 * scale, false);
        }
        put(ads, "apple-search-ads", "ADS", "spend", "keyword:1:exact", day(10), 5, false);
        put(ads, "apple-search-ads", "ADS", "spend", "campaign:11:Brand", day(10), 12, false);

        var detail = detail("ADS", 28);

        DimensionTable terms = table(detail, "searchterms");
        assertThat(terms.label()).isEqualTo("Search terms");
        assertThat(terms.columns()).extracting(MarketingOverviewQueryService.ColumnView::key)
                .containsExactly("impressions", "taps", "installs", "spend", "cost_per_install");
        assertThat(terms.rows()).extracting(MarketingOverviewQueryService.DimensionRowView::key)
                .containsExactly("11:meal planner", "12:meal planner", "11:(low volume)");
        assertThat(terms.rows()).extracting(MarketingOverviewQueryService.DimensionRowView::label)
                .containsExactly("meal planner", "meal planner", "(low volume)");
        assertThat(terms.rows().get(0).values()).containsExactly(500.0, 20.0, 4.0, 10.0, 2.5);
        assertThat(table(detail, "keywords").rows()).extracting(MarketingOverviewQueryService.DimensionRowView::key)
                .containsExactly("1");
        assertThat(table(detail, "campaigns").rows()).extracting(MarketingOverviewQueryService.DimensionRowView::key)
                .containsExactly("11");
        assertThat(metric(detail.channel(), "spend").value()).isNull();
    }

    @Test
    void tikTokFeedsSocialAndLikesAreAnOptionalLevelWithALikesColumnInThePlatformsTable() {
        String tiktok = connectFresh("tiktok");
        put(tiktok, "tiktok", "SOCIAL", "followers", "tiktok", day(12), 300, false);
        put(tiktok, "tiktok", "SOCIAL", "media_count", "tiktok", day(12), 12, false);
        assertThat(detail("SOCIAL", 7).channel().metrics()).extracting(MetricView::metric).doesNotContain("likes");

        put(tiktok, "tiktok", "SOCIAL", "likes", "tiktok", day(12), 1500, false);

        var detail = detail("SOCIAL", 7);
        assertThat(detail.channel().connections()).extracting(MarketingOverviewQueryService.ConnectionView::connectorId)
                .containsExactly("tiktok");
        assertThat(metric(detail.channel(), "followers").value()).isEqualTo(300.0);
        assertThat(metric(detail.channel(), "likes").value()).isEqualTo(1500.0);
        assertThat(metric(detail.channel(), "likes").label()).isEqualTo("Likes");
        DimensionTable platforms = table(detail, "platforms");
        assertThat(platforms.rows()).singleElement().satisfies(r -> {
            assertThat(r.key()).isEqualTo("tiktok");
            assertThat(cell(platforms, r, "likes")).isEqualTo(1500.0);
            assertThat(cell(platforms, r, "followers")).isEqualTo(300.0);
        });
    }

    @Test
    void dailyValuesAreSettledPerDayValuesSummedAcrossConnectionsOverTheSnapshotsHistory() {
        String a = connectFresh("apple-search-ads");
        String b = connectFresh("apple-search-ads");
        put(a, "apple-search-ads", "ADS", "new_downloads", "", day(8), 2, false);
        put(b, "apple-search-ads", "ADS", "new_downloads", "", day(8), 3, false);
        put(a, "apple-search-ads", "ADS", "new_downloads", "", day(9), 4, false);
        put(b, "apple-search-ads", "ADS", "new_downloads", "", day(9), 5, true);   // one side still restating
        put(a, "apple-search-ads", "ADS", "new_downloads", "", day(10), 7, true);  // provisional
        put(a, "apple-search-ads", "ADS", "new_downloads", "campaign:1:X", day(8), 99, false);
        String asc = connectFresh("app-store-connect");
        put(asc, "app-store-connect", "STORE", "downloads", "source:APP_STORE_SEARCH", day(8), 6, false);
        put(asc, "app-store-connect", "STORE", "downloads", "source:APP_STORE_SEARCH", day(9), 1, true);
        put(asc, "app-store-connect", "STORE", "downloads", "source:APP_STORE_SEARCH", day(15), 8, false); // today

        Snapshot snapshot = service.snapshot(project.getId(), 7, TODAY, NOW);

        assertThat(snapshot.dailyValues(MetricChannel.ADS, "new_downloads", "")).containsExactly(entry(day(8), 5.0));
        assertThat(snapshot.dailyValues(MetricChannel.ADS, "new_downloads", "campaign:1:X")).isEmpty();
        assertThat(snapshot.dailyValues(MetricChannel.STORE, "downloads", "source:APP_STORE_SEARCH"))
                .containsExactly(entry(day(8), 6.0));
        assertThat(snapshot.dailyValues(MetricChannel.STORE, "downloads", "")).isEmpty();
        assertThat(snapshot.dailyValues(MetricChannel.ADS, "no_such_metric", "")).isEmpty();
        assertThat(snapshot.dailyValues(MetricChannel.WEB, "visitors", "")).isEmpty();
    }

    @Test
    void everyMetricOnTheOverviewTheChannelDetailAndTheSnapshotCarriesItsDefinition() {
        String web = connectFresh("posthog");
        String gsc = connectFresh("gsc");
        String meta = connectFresh("meta");
        String asc = connectFresh("app-store-connect");
        String ads = connectFresh("apple-search-ads");
        put(web, "posthog", "WEB", "sessions", day(10), 3);
        put(gsc, "gsc", "SEARCH", "clicks", day(10), 3);
        put(meta, "meta", "SOCIAL", "followers", "facebook", day(10), 3, false);
        put(asc, "app-store-connect", "STORE", "downloads", day(10), 3);
        put(ads, "apple-search-ads", "ADS", "spend", day(10), 3);
        put(ads, "apple-search-ads", "ADS", "view_installs", day(10), 1);

        java.util.List<MetricView> shown = new java.util.ArrayList<>();
        overview7().channels().forEach(c -> shown.addAll(c.metrics()));
        for (String channel : java.util.List.of("WEB", "SEARCH", "SOCIAL", "STORE", "ADS")) {
            shown.addAll(detail(channel, 7).channel().metrics());
        }
        service.snapshot(project.getId(), 7, TODAY, NOW).channels().values()
                .forEach(c -> shown.addAll(c.view().metrics()));

        assertThat(shown).isNotEmpty().allSatisfy(m -> {
            assertThat(m.definition()).as(m.metric()).isNotNull();
            assertThat(m.definition().source()).as(m.metric()).isNotBlank();
            assertThat(MetricCatalog.isMeasure(m.definition().numerator())).as(m.metric()).isTrue();
        });
        assertThat(metric(detail("ADS", 7).channel(), "installs").definition().timeZone()).isEqualTo("UTC");
    }

    @Test
    void theResponsesCarryDefinitionsAndTheVisitorNoteAndTheDefinitionsEndpointListsEveryVisibleMetric() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        OffsetDateTime now = OffsetDateTime.now();
        String web = connect("posthog", now.minusMinutes(5));
        for (int i = 2; i <= 40; i++) {
            put(web, "posthog", "WEB", "visitors", today.minusDays(i), 2);   // daily counts, no stored window-unique one
        }
        String productConnection = connect("product-metrics", now.minusMinutes(5));
        ChannelMetricDefinitionRow trials = new ChannelMetricDefinitionRow();
        trials.setConnectionId(productConnection);
        trials.setProjectId(project.getId());
        trials.setMetric("trial_started");
        trials.setLabel("Trials started");
        trials.setUnit("COUNT");
        trials.setAggregation("SUM");
        trials.setGood("UP");
        trials.setExclusions("Likely App Review installs");
        definitionRepository.saveAndFlush(trials);
        ProjectSecurityService security = mock(ProjectSecurityService.class);

        var channel = new MarketingOverviewController(service, security)
                .getMarketingChannel(project.getId(), "WEB", "28d").getBody().getChannel();
        OverviewMetric visitors = channel.getMetrics().stream().filter(m -> m.getMetric().equals("visitors"))
                .findFirst().orElseThrow();
        assertThat(visitors.getValue()).isNull();
        assertThat(visitors.getNote()).isEqualTo(notStoredNote(28));
        assertThat(visitors.getDefinition().getNumerator()).isEqualTo("visitors");
        assertThat(visitors.getDefinition().getSource()).startsWith("PostHog");
        assertThat(visitors.getDefinition().getExclusions()).contains("Internal-users cohort");
        assertThat(channel.getMetrics()).allSatisfy(m -> assertThat(m.getDefinition()).isNotNull());

        java.util.List<MarketingDefinitionEntry> entries = new MarketingDefinitionsController(service, security)
                .getMarketingDefinitions(project.getId()).getBody().getDefinitions();
        java.util.Map<String, MarketingDefinitionEntry> byRef = new java.util.HashMap<>();
        entries.forEach(e -> byRef.put(e.getMetricRef(), e));
        assertThat(byRef).containsKeys("WEB.visitors", "SEARCH.ctr", "STORE.conversion_rate_search", "ADS.installs",
                "ADS.view_installs", "SOCIAL.likes", "PRODUCT.trial_started");
        assertThat(byRef).doesNotContainKeys("STORE.total_downloads", "SEARCH.position_x_impressions",
                "STORE.search_total_downloads");
        MarketingDefinitionEntry installs = byRef.get("ADS.installs");
        assertThat(installs.getChannel()).isEqualTo("ADS");
        assertThat(installs.getKey()).isEqualTo("installs");
        assertThat(installs.getLabel()).isEqualTo("Installs");
        assertThat(installs.getUnit()).isEqualTo(MarketingDefinitionEntry.UnitEnum.COUNT);
        assertThat(installs.getAggregation()).isEqualTo("SUM");
        assertThat(installs.getDefinition().getNumerator()).isEqualTo("tap_installs");
        assertThat(byRef.get("WEB.visitors").getAggregation()).isEqualTo("WINDOW_UNIQUE");
        MarketingDefinitionEntry search = byRef.get("STORE.conversion_rate_search");
        assertThat(search.getDefinition().getNumerator()).isEqualTo("total_downloads");
        assertThat(search.getDefinition().getDenominator()).isEqualTo("unique_impressions");
        assertThat(search.getDefinition().getSourceType()).isEqualTo("APP_STORE_SEARCH");
        MarketingDefinitionEntry product = byRef.get("PRODUCT.trial_started");
        assertThat(product.getDefinition().getNumerator()).isEqualTo("product:trial_started");
        assertThat(product.getDefinition().getExclusions()).isEqualTo("Likely App Review installs");
        assertThat(product.getDefinition().getSource()).isEqualTo("Product metrics feed (product-metrics account)");
        long visible = MetricCatalog.channels().stream().filter(c -> c.channel() != MetricChannel.PRODUCT)
                .mapToLong(c -> c.visibleMetrics().size()).sum();
        assertThat(entries).hasSize((int) visible + 1);
    }

    @Test
    void theDefinitionsListHasNoProductEntriesWithoutAProductConnection() {
        var entries = service.definitions(project.getId());

        assertThat(entries).isNotEmpty();
        assertThat(entries).extracting(MarketingOverviewQueryService.MetricDefinitionView::channel)
                .doesNotContain("PRODUCT");
        assertThat(entries).allSatisfy(e -> {
            assertThat(e.metricRef()).isEqualTo(e.channel() + "." + e.key());
            assertThat(e.definition().source()).isNotBlank();
        });
    }
}
