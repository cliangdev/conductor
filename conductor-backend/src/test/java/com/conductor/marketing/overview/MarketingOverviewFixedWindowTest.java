package com.conductor.marketing.overview;

import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.integration.MetricChannel;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.integration.ingest.ConnectorFeedStatus;
import com.conductor.marketing.overview.MarketingOverviewQueryService.ChannelView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.MetricView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.Overview;
import com.conductor.marketing.overview.MarketingOverviewQueryService.WindowSpec;
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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A window named by its days ({@code YYYY-MM-DD..YYYY-MM-DD}): parsing and its refusals, every channel summing
 * exactly those days, the previous window of the same length, days still being restated left out, and a channel
 * whose settled data stops short flagged {@code partial}.
 *
 * <p>"Today" is pinned to 2026-10-29, so the reference window 2026-09-29..2026-10-26 ends three days back.
 */
@Transactional
class MarketingOverviewFixedWindowTest extends AbstractNoneWebIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);
    private static final OffsetDateTime NOW = TODAY.atTime(12, 0).atOffset(ZoneOffset.UTC);
    private static final LocalDate FROM = LocalDate.of(2026, 9, 29);
    private static final LocalDate TO = LocalDate.of(2026, 10, 26);
    private static final String WINDOW = "2026-09-29..2026-10-26";

    @Autowired private MarketingOverviewQueryService service;
    @Autowired private ChannelMetricDailyRepository dailyRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ConnectorFeedRepository feedRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;

    private Project project;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Fixed Window Test");
        user = userRepository.save(user);
        project = new Project();
        project.setName("Fixed Window Project");
        project.setKey("FW" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        project = projectRepository.save(project);
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private String connect(String connectorId) {
        Connection connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId(connectorId);
        connection.setDisplayLabel(connectorId + " account");
        connection.setAuthType("API_KEY");
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
        feed.setLastSuccessAt(NOW.minusHours(1));
        feedRepository.saveAndFlush(feed);
        return id;
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

    private Overview overview(String window) {
        return service.overview(project.getId(), window, TODAY, NOW);
    }

    private static ChannelView channel(Overview overview, String name) {
        return overview.channels().stream().filter(c -> c.channel().equals(name)).findFirst().orElseThrow();
    }

    private static MetricView metric(ChannelView channel, String key) {
        return channel.metrics().stream().filter(m -> m.metric().equals(key)).findFirst().orElseThrow();
    }

    // ---- parsing --------------------------------------------------------------------------------

    @Test
    void aFixedRangeParsesToItsDaysAndCount() {
        WindowSpec spec = MarketingOverviewQueryService.parseWindow(WINDOW, Set.of(7, 28), TODAY);

        assertThat(spec.fixed()).isTrue();
        assertThat(spec.from()).isEqualTo(FROM);
        assertThat(spec.to()).isEqualTo(TO);
        assertThat(spec.days()).isEqualTo(28);
    }

    @Test
    void theRollingWindowsParseAsBefore() {
        assertThat(MarketingOverviewQueryService.parseWindow("7d", Set.of(7, 28), TODAY))
                .isEqualTo(WindowSpec.rolling(7));
        assertThat(MarketingOverviewQueryService.parseWindow(null, Set.of(7, 28), TODAY))
                .isEqualTo(WindowSpec.rolling(28));
        assertThat(MarketingOverviewQueryService.parseWindow(" 90D ", Set.of(7, 28, 90), TODAY))
                .isEqualTo(WindowSpec.rolling(90));
        assertThatThrownBy(() -> MarketingOverviewQueryService.parseWindow("90d", Set.of(7, 28), TODAY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Unknown window '90d'");
    }

    @Test
    void aFixedRangeMayEndYesterdayButNotTodayAndMayNotRunBackwardsOrPastNinetyTwoDays() {
        assertThat(MarketingOverviewQueryService.parseWindow("2026-10-28..2026-10-28", Set.of(7, 28), TODAY).days())
                .isEqualTo(1);
        assertThatThrownBy(() -> overview("2026-10-01..2026-10-29"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("2026-10-28");
        assertThatThrownBy(() -> overview("2026-10-01..2026-11-15"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("only days up to");
        assertThatThrownBy(() -> overview("2026-10-26..2026-09-29"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("after it ends");
        assertThatThrownBy(() -> overview("2026-07-28..2026-10-28"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("93 days");
        assertThat(MarketingOverviewQueryService.parseWindow("2026-07-29..2026-10-28", Set.of(7, 28), TODAY).days())
                .isEqualTo(92);
        assertThatThrownBy(() -> overview("2026-02-30..2026-03-05"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("calendar day");
        assertThatThrownBy(() -> overview("last week"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("YYYY-MM-DD..YYYY-MM-DD");
    }

    // ---- values ---------------------------------------------------------------------------------

    @Test
    void everyChannelSumsExactlyTheFixedDaysNotItsOwnLatestSettledWindow() {
        String web = connect("posthog");
        for (LocalDate d = FROM.minusDays(28); !d.isAfter(TODAY.minusDays(1)); d = d.plusDays(1)) {
            boolean inside = !d.isBefore(FROM) && !d.isAfter(TO);
            put(web, "posthog", "WEB", "sessions", d, inside ? 2 : 1000); // outside the window must never count
        }

        Overview overview = overview(WINDOW);
        MetricView sessions = metric(channel(overview, "WEB"), "sessions");

        assertThat(sessions.value()).isEqualTo(56.0); // 28 days x 2
        assertThat(sessions.previous()).isEqualTo(28_000.0); // Sep 1 - Sep 28
        assertThat(sessions.series()).hasSize(28);
        assertThat(sessions.series().get(0).day()).isEqualTo(FROM);
        assertThat(sessions.series().get(27).day()).isEqualTo(TO);
        assertThat(overview.window().from()).isEqualTo(FROM);
        assertThat(overview.window().to()).isEqualTo(TO);
        assertThat(overview.window().previousFrom()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(overview.window().previousTo()).isEqualTo(LocalDate.of(2026, 9, 28));
        assertThat(channel(overview, "WEB").dataCompleteThrough()).isEqualTo(TO);
        assertThat(channel(overview, "WEB").partial()).isNull();
    }

    @Test
    void theSameDaysGiveTheSameNumbersWhenNamedAsAFixedWindowOrAsTheRollingWindowThatEndsThere() {
        String web = connect("posthog");
        for (LocalDate d = FROM.minusDays(28); !d.isAfter(TO); d = d.plusDays(1)) {
            put(web, "posthog", "WEB", "sessions", d, d.getDayOfMonth());
        }
        // yesterday-relative: pin today to Oct 27 so the rolling 28d window ends Oct 26 as well
        LocalDate today = TO.plusDays(1);
        OffsetDateTime now = today.atTime(12, 0).atOffset(ZoneOffset.UTC);

        MetricView rolling = metric(channel(service.overview(project.getId(), 28, today, now), "WEB"), "sessions");
        MetricView fixed = metric(channel(service.overview(project.getId(), WINDOW, today, now), "WEB"), "sessions");

        assertThat(fixed.value()).isEqualTo(rolling.value());
        assertThat(fixed.previous()).isEqualTo(rolling.previous());
        assertThat(fixed.change()).isEqualTo(rolling.change());
        assertThat(fixed.series()).isEqualTo(rolling.series());
    }

    @Test
    void aWindowLongPastStillHasItsDaysAndItsPreviousWindowLoaded() {
        String web = connect("posthog");
        for (LocalDate d = LocalDate.of(2026, 1, 1); d.isBefore(LocalDate.of(2026, 3, 1)); d = d.plusDays(1)) {
            put(web, "posthog", "WEB", "sessions", d, 3);
        }
        for (LocalDate d = TODAY.minusDays(20); d.isBefore(TODAY); d = d.plusDays(1)) {
            put(web, "posthog", "WEB", "sessions", d, 1); // keeps the channel current, so only the window is "old"
        }

        MetricView sessions = metric(channel(overview("2026-02-01..2026-02-14"), "WEB"), "sessions");

        assertThat(sessions.value()).isEqualTo(42.0);
        assertThat(sessions.previous()).isEqualTo(42.0); // Jan 18 - Jan 31
    }

    // ---- partial --------------------------------------------------------------------------------

    @Test
    void aChannelWhoseSettledDataStopsShortOfTheWindowIsPartialCountsOnlySettledDaysAndWithholdsThePrevious() {
        String ads = connect("apple-search-ads");
        String web = connect("posthog");
        for (LocalDate d = FROM.minusDays(28); !d.isAfter(TO); d = d.plusDays(1)) {
            put(web, "posthog", "WEB", "sessions", d, 2);
        }
        // Apple Ads settled through Oct 23; Oct 24-26 are still being restated
        for (LocalDate d = FROM.minusDays(28); !d.isAfter(TO); d = d.plusDays(1)) {
            put(ads, "apple-search-ads", "ADS", "spend", "", d, d.isAfter(LocalDate.of(2026, 10, 23)) ? 999 : 1,
                    d.isAfter(LocalDate.of(2026, 10, 23)));
        }

        Overview overview = overview(WINDOW);
        ChannelView adsView = channel(overview, "ADS");
        MetricView spend = metric(adsView, "spend");

        assertThat(adsView.partial()).isTrue();
        assertThat(adsView.dataCompleteThrough()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(spend.value()).isEqualTo(25.0); // Sep 29 - Oct 23, the restating days left out
        assertThat(spend.previous()).as("25 days against a full 28 is no comparison").isNull();
        assertThat(spend.change()).isNull();
        assertThat(spend.series()).hasSize(28);
        assertThat(spend.series().get(24).provisional()).isFalse(); // Oct 23
        assertThat(spend.series().get(25).provisional()).isTrue(); // Oct 24 is drawn, flagged, never counted
        assertThat(spend.provisionalDays()).isEqualTo(3);
        assertThat(channel(overview, "WEB").partial()).isNull();
        assertThat(metric(channel(overview, "WEB"), "sessions").previous()).isEqualTo(56.0);
    }

    @Test
    void aChannelWithNoDataAtAllIsNotCalledPartial() {
        connect("apple-search-ads");

        ChannelView ads = channel(overview(WINDOW), "ADS");

        assertThat(ads.partial()).isNull();
        assertThat(ads.metrics()).allSatisfy(m -> assertThat(m.value()).isNull());
    }

    @Test
    void aChannelWhoseDataEndsBeforeTheWindowBeginsHasNoValueAndIsPartial() {
        String web = connect("posthog");
        for (LocalDate d = LocalDate.of(2026, 8, 1); d.isBefore(FROM.minusDays(3)); d = d.plusDays(1)) {
            put(web, "posthog", "WEB", "sessions", d, 5);
        }

        ChannelView view = channel(overview(WINDOW), "WEB");

        assertThat(view.partial()).isTrue();
        assertThat(metric(view, "sessions").value()).isNull();
        assertThat(metric(view, "sessions").previous()).isNull();
    }

    @Test
    void theFunnelWithholdsItsPreviousWhenItsDaysStopShortOfAFixedWindow() {
        String store = connect("app-store-connect");
        for (LocalDate d = FROM.minusDays(28); !d.isAfter(TO); d = d.plusDays(1)) {
            put(store, "app-store-connect", "STORE", "impressions", "", d, 10, d.isAfter(LocalDate.of(2026, 10, 23)));
        }

        Overview overview = overview(WINDOW);

        assertThat(overview.window().to()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(overview.funnel()).filteredOn(s -> s.key().equals("store_impressions")).singleElement()
                .satisfies(s -> {
                    assertThat(s.available()).isTrue();
                    assertThat(s.value()).isEqualTo(250.0); // Sep 29 - Oct 23
                    assertThat(s.previous()).as("25 days against a full 28 is no comparison").isNull();
                });
    }

    // ---- levels and distinct counts -------------------------------------------------------------

    @Test
    void aLevelReadsItsLatestValueAtOrBeforeTheLastDayAndTheOneBeforeTheFirst() {
        String social = connect("meta");
        for (LocalDate d = FROM.minusDays(10); !d.isAfter(TODAY.minusDays(1)); d = d.plusDays(1)) {
            put(social, "meta", "SOCIAL", "followers", "instagram", d, 100 + d.getDayOfMonth(), false);
        }

        MetricView followers = metric(channel(overview(WINDOW), "SOCIAL"), "followers");

        assertThat(followers.value()).isEqualTo(126.0); // Oct 26, not yesterday's 128
        assertThat(followers.previous()).isEqualTo(128.0); // Sep 28 -> 100 + 28
        assertThat(followers.asOf()).isEqualTo(TO);
    }

    @Test
    void uniqueVisitorsReadTheStoredCountOnlyForAFixedWindowOfAStoredLength() {
        String web = connect("posthog");
        for (LocalDate d = FROM.minusDays(40); !d.isAfter(TODAY.minusDays(1)); d = d.plusDays(1)) {
            put(web, "posthog", "WEB", "visitors", "", d, 3, false);
            put(web, "posthog", "WEB", "visitors_unique_28d", "", d, 49, false);
            put(web, "posthog", "WEB", "visitors_unique_7d", "", d, 20, false);
        }

        MetricView month = metric(channel(overview(WINDOW), "WEB"), "visitors");
        MetricView fortnight = metric(channel(overview("2026-10-13..2026-10-26"), "WEB"), "visitors");
        MetricView week = metric(channel(overview("2026-10-20..2026-10-26"), "WEB"), "visitors");

        assertThat(month.value()).isEqualTo(49.0);
        assertThat(month.note()).isNull();
        assertThat(fortnight.value()).isNull();
        assertThat(fortnight.note()).isEqualTo(MarketingOverviewQueryService.WINDOW_UNIQUE_NOTE);
        assertThat(week.value()).isEqualTo(20.0);
    }

    // ---- channel detail and snapshot ------------------------------------------------------------

    @Test
    void theChannelDetailAndTheSnapshotReadTheSameFixedDays() {
        String web = connect("posthog");
        for (LocalDate d = FROM.minusDays(28); !d.isAfter(TODAY.minusDays(1)); d = d.plusDays(1)) {
            put(web, "posthog", "WEB", "sessions", d, 1);
        }

        var detail = service.channel(project.getId(), MetricCatalog.channel("WEB").orElseThrow(), WINDOW, TODAY, NOW);
        var snapshot = service.snapshot(project.getId(), WINDOW, TODAY, NOW);

        assertThat(detail.window().from()).isEqualTo(FROM);
        assertThat(detail.window().to()).isEqualTo(TO);
        assertThat(metric(detail.channel(), "sessions").value()).isEqualTo(28.0);
        assertThat(snapshot.overview().window().from()).isEqualTo(FROM);
        assertThat(snapshot.overview().window().to()).isEqualTo(TO);
        assertThat(metric(snapshot.channels().get(MetricChannel.WEB).view(), "sessions").value()).isEqualTo(28.0);
        assertThat(snapshot.channels().get(MetricChannel.WEB).window().to()).isEqualTo(TO);
        assertThat(snapshot.channels().get(MetricChannel.WEB).settledThrough()).isEqualTo(TODAY.minusDays(1));
        assertThat(snapshot.dailyValues(MetricChannel.WEB, "sessions", "")).containsKey(TO);
    }

    @Test
    void aFixedWindowCarriesNoTopPostsBecauseThatRankingIsTrailingFromNow() {
        connect("meta");

        var detail = service.channel(project.getId(), MetricCatalog.channel("SOCIAL").orElseThrow(), WINDOW, TODAY, NOW);

        assertThat(detail.topPosts()).isEmpty();
    }

    @Test
    void aRollingOverviewIsUnchangedByTheNewFields() {
        String web = connect("posthog");
        for (int d = 1; d <= 14; d++) {
            put(web, "posthog", "WEB", "sessions", LocalDate.of(2026, 10, d), 5);
        }
        LocalDate today = LocalDate.of(2026, 10, 15);

        ChannelView view = channel(service.overview(project.getId(), 7, today,
                today.atTime(12, 0).atOffset(ZoneOffset.UTC)), "WEB");

        assertThat(view.partial()).isNull();
        assertThat(view.mismatches()).isEmpty();
        assertThat(view.mismatch()).isNull();
        assertThat(metric(view, "sessions").value()).isEqualTo(35.0);
        assertThat(view.dataCompleteThrough()).isEqualTo(LocalDate.of(2026, 10, 14));
    }
}
