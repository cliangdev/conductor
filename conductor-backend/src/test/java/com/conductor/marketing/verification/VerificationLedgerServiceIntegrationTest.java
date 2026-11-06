package com.conductor.marketing.verification;

import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.integration.MetricChannel;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.integration.ingest.ConnectorFeedStatus;
import com.conductor.marketing.overview.MarketingOverviewQueryService;
import com.conductor.marketing.overview.MarketingOverviewQueryService.ChannelView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.MismatchView;
import com.conductor.marketing.overview.MetricCatalog;
import com.conductor.marketing.verification.VerificationLedgerService.Ledger;
import com.conductor.marketing.verification.VerificationLedgerService.LedgerRow;
import com.conductor.marketing.verification.VerificationLedgerService.RecordedRow;
import com.conductor.marketing.verification.VerificationLedgerService.Verdict;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The verification ledger against real Postgres: verdicts follow the C3 table, a platform read is compared over
 * its own days, reads are append-only, and {@code verified} is true only for a number a second source confirmed.
 *
 * <p>"Today" is pinned to 2026-10-29, so the reference window 2026-09-29..2026-10-26 is a fixed window whose last
 * day is three days back (Apple Ads restates the last three days, which stay provisional here).
 */
@Transactional
class VerificationLedgerServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);
    private static final OffsetDateTime NOW = TODAY.atTime(12, 0).atOffset(ZoneOffset.UTC);
    private static final LocalDate FROM = LocalDate.of(2026, 9, 29);
    private static final LocalDate TO = LocalDate.of(2026, 10, 26);
    private static final String WINDOW = "2026-09-29..2026-10-26";

    @Autowired private VerificationLedgerService service;
    @Autowired private MarketingOverviewQueryService overviewService;
    @Autowired private MarketingPlatformReadRepository readRepository;
    @Autowired private ChannelMetricDailyRepository dailyRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private ConnectorFeedRepository feedRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;

    private Project project;
    private User user;
    private String ads;
    private String store;
    private String gsc;
    private String social;
    private String web;
    private String product;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setFirebaseUid("test-uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Ledger Test");
        user = userRepository.save(user);
        project = new Project();
        project.setName("Ledger Test Project");
        project.setKey("LG" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
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

    /** Every day of the reference window settled with a little spend, and the last three days restating. */
    private void connectAds() {
        ads = connect("apple-search-ads");
        for (LocalDate d = FROM; !d.isAfter(TO); d = d.plusDays(1)) {
            put(ads, "apple-search-ads", "ADS", "spend", d, 5.82);
        }
        for (LocalDate d = TO.plusDays(1); !d.isAfter(TODAY.minusDays(1)); d = d.plusDays(1)) {
            put(ads, "apple-search-ads", "ADS", "spend", "", d, 5.82, true);
        }
    }

    private void connectProduct() {
        product = connect("product-metrics");
    }

    private void adsInstalls(double... perDayFromWindowStart) {
        for (int i = 0; i < perDayFromWindowStart.length; i++) {
            put(ads, "apple-search-ads", "ADS", "installs", FROM.plusDays(i), perDayFromWindowStart[i]);
        }
    }

    private void backendReference(String metric, double... perDayFromWindowStart) {
        for (int i = 0; i < perDayFromWindowStart.length; i++) {
            put(product, "product-metrics", "PRODUCT", metric, FROM.plusDays(i), perDayFromWindowStart[i]);
        }
    }

    private void connectSearch() {
        gsc = connect("gsc");
        for (LocalDate d = FROM; !d.isAfter(TO); d = d.plusDays(1)) {
            put(gsc, "gsc", "SEARCH", "clicks", d, d.equals(FROM) || d.equals(FROM.plusDays(9)) || d.equals(TO) ? 1 : 0);
            put(gsc, "gsc", "SEARCH", "impressions", d, d.isBefore(FROM.plusDays(2)) ? 15 : 20);
        }
    }

    private Ledger ledger() {
        return service.ledger(project.getId(), WINDOW, TODAY, NOW);
    }

    private LedgerRow row(String metricRef) {
        return ledger().find(metricRef).orElseThrow();
    }

    private RecordedRow read(String channel, String metric, LocalDate from, LocalDate to, double value, String source) {
        return service.record(project.getId(), channel, metric, from, to, value, source, null, user.getId(), TODAY, NOW);
    }

    // ---- verdicts -------------------------------------------------------------------------------

    @Test
    void aMetricWithNoSecondSourceIsUncheckedAndNeverVerified() {
        connectSearch();

        LedgerRow clicks = row("SEARCH.clicks");

        assertThat(clicks.verdict()).isEqualTo(Verdict.UNCHECKED);
        assertThat(clicks.mismatch()).isFalse();
        assertThat(clicks.backend()).isNull();
        assertThat(clicks.platformRead()).isNull();
        assertThat(clicks.conductorValue()).isEqualTo(3.0);
        assertThat(clicks.trustNote()).contains("no second source");
        assertThat(service.verified(project.getId(), "SEARCH.clicks", WINDOW, TODAY, NOW)).isFalse();
    }

    @Test
    void matchingPlatformReadsOverTheReferenceWindowGiveSearchClicksAndImpressionsBoth() {
        connectSearch();
        double impressions = row("SEARCH.impressions").conductorValue();
        assertThat(impressions).isEqualTo(550.0); // 26 days x 20 + 2 days x 15

        RecordedRow clicks = read("SEARCH", "clicks", FROM, TO, 3, "Search Console UI");
        RecordedRow shown = read("SEARCH", "impressions", FROM, TO, 550, "Search Console UI");

        assertThat(clicks.row().verdict()).isEqualTo(Verdict.BOTH);
        assertThat(shown.row().verdict()).isEqualTo(Verdict.BOTH);
        assertThat(clicks.row().mismatch()).isFalse();
        assertThat(row("SEARCH.clicks").platformRead()).satisfies(r -> {
            assertThat(r.value()).isEqualTo(3.0);
            assertThat(r.conductorValueOverReadDays()).isEqualTo(3.0);
            assertThat(r.matches()).isTrue();
            assertThat(r.sourceName()).isEqualTo("Search Console UI");
            assertThat(r.from()).isEqualTo(FROM);
            assertThat(r.to()).isEqualTo(TO);
        });
        assertThat(service.verified(project.getId(), "SEARCH.clicks", WINDOW, TODAY, NOW)).isTrue();
        assertThat(service.verified(project.getId(), "SEARCH.impressions", WINDOW, TODAY, NOW)).isTrue();
    }

    @Test
    void aSocialLevelIsUncheckedUntilADirectReadAgrees() {
        social = connect("meta");
        for (LocalDate d = FROM; !d.isAfter(TODAY.minusDays(1)); d = d.plusDays(1)) {
            put(social, "meta", "SOCIAL", "followers", "instagram", d, 100 + d.getDayOfMonth(), false);
        }
        assertThat(row("SOCIAL.followers").verdict()).isEqualTo(Verdict.UNCHECKED);
        assertThat(service.verified(project.getId(), "SOCIAL.followers", WINDOW, TODAY, NOW)).isFalse();

        read("SOCIAL", "followers", TO, TO, 126, "Instagram app"); // 100 + 26

        assertThat(row("SOCIAL.followers").verdict()).isEqualTo(Verdict.BOTH);
        assertThat(service.verified(project.getId(), "SOCIAL.followers", WINDOW, TODAY, NOW)).isTrue();
    }

    @Test
    void aPlatformReadThatDisagreesIsPlatformAndMakesTheChannelAMismatch() {
        connectSearch();

        RecordedRow recorded = read("SEARCH", "clicks", FROM, TO, 9, "Search Console UI");

        assertThat(recorded.row().verdict()).isEqualTo(Verdict.PLATFORM);
        assertThat(recorded.row().mismatch()).isTrue();
        assertThat(recorded.row().trustNote()).contains("Search Console UI").contains("9");
        assertThat(service.verified(project.getId(), "SEARCH.clicks", WINDOW, TODAY, NOW)).isFalse();

        ChannelView search = overviewService.snapshot(project.getId(), WINDOW, TODAY, NOW)
                .channels().get(MetricChannel.SEARCH).view();
        assertThat(search.status()).isEqualTo("MISMATCH");
        assertThat(search.mismatches()).singleElement().satisfies(m -> {
            assertThat(m.kind()).isEqualTo("PLATFORM");
            assertThat(m.metric()).isEqualTo("clicks");
            assertThat(m.referenceValue()).isEqualTo(9.0);
            assertThat(m.channelValue()).isEqualTo(3.0);
            assertThat(m.referenceSource()).isEqualTo("Search Console UI");
            assertThat(m.from()).isEqualTo(FROM);
            assertThat(m.to()).isEqualTo(TO);
        });
        assertThat(search.mismatch()).isEqualTo(search.mismatches().get(0));
    }

    @Test
    void sevenAgainstTwentyThreeIsAMismatchTrustedToTheConnectorUntilAReadSaysOtherwise() {
        connectAds();
        connectProduct();
        adsInstalls(1, 1, 1, 1, 1, 1, 1);
        backendReference("ref_ads_tap_installs", 3, 3, 3, 3, 3, 4, 4);

        LedgerRow before = row("ADS.installs");

        assertThat(before.verdict()).isEqualTo(Verdict.CONNECTOR);
        assertThat(before.mismatch()).isTrue();
        assertThat(before.backend()).satisfies(b -> {
            assertThat(b.value()).isEqualTo(23.0);
            assertThat(b.conductorValue()).isEqualTo(7.0);
            assertThat(b.daysCompared()).isEqualTo(7);
            assertThat(b.matches()).isFalse();
            assertThat(b.source()).isEqualTo("product-metrics account");
        });
        assertThat(service.verified(project.getId(), "ADS.installs", WINDOW, TODAY, NOW)).isFalse();

        ChannelView adsView = overviewService.snapshot(project.getId(), WINDOW, TODAY, NOW)
                .channels().get(MetricChannel.ADS).view();
        assertThat(adsView.status()).isEqualTo("MISMATCH");
        assertThat(adsView.mismatches()).singleElement().satisfies(m -> {
            assertThat(m.kind()).isEqualTo("BACKEND");
            assertThat(m.metric()).isEqualTo("installs");
            assertThat(m.referenceValue()).isEqualTo(23.0);
            assertThat(m.channelValue()).isEqualTo(7.0);
        });
        assertThat(adsView.mismatch()).isEqualTo(adsView.mismatches().get(0));
    }

    @Test
    void aReadThatAgreesWithTheConnectorKeepsTrustInItWhileTheBackendDisagrees() {
        connectAds();
        connectProduct();
        adsInstalls(1, 1, 1, 1, 1, 1, 1);
        backendReference("ref_ads_tap_installs", 3, 3, 3, 3, 3, 4, 4);

        RecordedRow recorded = read("ADS", "installs", FROM, TO, 7, "Apple Ads console");

        assertThat(recorded.row().verdict()).isEqualTo(Verdict.CONNECTOR);
        assertThat(recorded.row().mismatch()).isTrue(); // the backend is still off: the channel stays MISMATCH
        assertThat(recorded.row().trustNote()).contains("Apple Ads console").contains("backend");
    }

    @Test
    void aReadThatAgreesWithTheBackendButNotTheConnectorIsBackend() {
        connectAds();
        connectProduct();
        adsInstalls(1, 1, 1, 1, 1, 1, 1);
        backendReference("ref_ads_tap_installs", 3, 3, 3, 3, 3, 4, 4);

        RecordedRow recorded = read("ADS", "installs", FROM, TO, 23, "Apple Ads console");

        assertThat(recorded.row().verdict()).isEqualTo(Verdict.BACKEND);
        assertThat(recorded.row().mismatch()).isTrue();
        assertThat(recorded.row().trustNote()).contains("backend").contains("23");
    }

    @Test
    void aReadThatDisagreesWithTheConnectorAndTheBackendIsPlatform() {
        connectAds();
        connectProduct();
        adsInstalls(1, 1, 1, 1, 1, 1, 1);
        backendReference("ref_ads_tap_installs", 3, 3, 3, 3, 3, 4, 4);

        assertThat(read("ADS", "installs", FROM, TO, 60, "Apple Ads console").row().verdict())
                .isEqualTo(Verdict.PLATFORM);
    }

    @Test
    void everySecondSourceAgreeingIsBoth() {
        connectAds();
        connectProduct();
        adsInstalls(1, 1, 1, 1, 1, 1, 1);
        backendReference("ref_ads_tap_installs", 1, 1, 1, 1, 1, 1, 2); // 8 vs 7: one unit apart, inside the band

        assertThat(row("ADS.installs").verdict()).isEqualTo(Verdict.BOTH);
        assertThat(row("ADS.installs").mismatch()).isFalse();
        assertThat(read("ADS", "installs", FROM, TO, 7, "Apple Ads console").row().verdict()).isEqualTo(Verdict.BOTH);
        assertThat(service.verified(project.getId(), "ADS.installs", WINDOW, TODAY, NOW)).isTrue();
    }

    @Test
    void moneyIsJudgedByTwoPercentSoSpendOf162Point96Against164IsBoth() {
        connectAds();
        connectProduct();
        for (LocalDate d = FROM; !d.isAfter(TO); d = d.plusDays(1)) {
            put(product, "product-metrics", "PRODUCT", "ref_ads_spend", d, 164.0 / 28);
        }

        LedgerRow spend = row("ADS.spend");

        assertThat(spend.conductorValue()).isCloseTo(162.96, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(spend.verdict()).isEqualTo(Verdict.BOTH);
        assertThat(spend.backend().matches()).isTrue();
    }

    @Test
    void aReadOverDaysConductorHasNotSettledCannotBeComparedAndLeavesTheMetricUnchecked() {
        connectSearch();

        RecordedRow recorded = read("SEARCH", "clicks", TO, TO.plusDays(2), 5, "Search Console UI");

        assertThat(recorded.row().verdict()).isEqualTo(Verdict.UNCHECKED);
        assertThat(recorded.row().platformRead().matches()).isNull();
        assertThat(recorded.row().platformRead().conductorValueOverReadDays()).isNull();
        assertThat(recorded.row().mismatch()).isFalse();
    }

    @Test
    void uniqueVisitorsAreReadOverAWindowOfAStoredLength() {
        web = connect("posthog");
        for (LocalDate d = FROM; !d.isAfter(TO); d = d.plusDays(1)) {
            put(web, "posthog", "WEB", "visitors", d, 3);
            put(web, "posthog", "WEB", "sessions", d, 4);
        }
        put(web, "posthog", "WEB", "visitors_unique_28d", "", TO, 49, false);
        put(web, "posthog", "WEB", "visitors_unique_7d", "", TO, 20, false);

        RecordedRow month = read("WEB", "visitors", FROM, TO, 49, "PostHog");
        RecordedRow fortnight = read("WEB", "visitors", TO.minusDays(13), TO, 30, "PostHog");

        assertThat(month.row().platformRead().conductorValueOverReadDays()).isEqualTo(49.0);
        assertThat(month.row().verdict()).isEqualTo(Verdict.BOTH);
        assertThat(fortnight.row().platformRead().conductorValueOverReadDays()).isNull();
        assertThat(fortnight.row().verdict()).isEqualTo(Verdict.UNCHECKED);
    }

    @Test
    void aReadOlderThanSevenDaysIsStale() {
        connectSearch();
        service.record(project.getId(), "SEARCH", "clicks", FROM, TO, 3, "Search Console UI", null, user.getId(),
                TODAY, NOW.minusDays(8));

        LedgerRow clicks = row("SEARCH.clicks");

        assertThat(clicks.readStale()).isTrue();
        assertThat(clicks.verdict()).isEqualTo(Verdict.BOTH);
        assertThat(clicks.trustNote()).contains("8 days old");
        assertThat(ledger().summary().staleReads()).isEqualTo(1);
    }

    @Test
    void theNewestReadWins() {
        connectSearch();
        read("SEARCH", "clicks", FROM, TO, 9, "Search Console UI");
        assertThat(row("SEARCH.clicks").verdict()).isEqualTo(Verdict.PLATFORM);

        service.record(project.getId(), "SEARCH", "clicks", FROM, TO, 3, "Search Console UI", "rechecked",
                user.getId(), TODAY, NOW.plusMinutes(5));

        assertThat(row("SEARCH.clicks").verdict()).isEqualTo(Verdict.BOTH);
        assertThat(row("SEARCH.clicks").platformRead().note()).isEqualTo("rechecked");
    }

    @Test
    void theSummaryCountsRowsByVerdict() {
        connectSearch();
        connectAds();
        connectProduct();
        adsInstalls(1, 1, 1, 1, 1, 1, 1);
        backendReference("ref_ads_tap_installs", 3, 3, 3, 3, 3, 4, 4);
        read("SEARCH", "clicks", FROM, TO, 3, "Search Console UI");

        VerificationLedgerService.Summary summary = ledger().summary();

        assertThat(summary.both()).isEqualTo(1);
        assertThat(summary.connector()).isEqualTo(1);
        assertThat(summary.mismatched()).isEqualTo(1);
        assertThat(summary.unchecked()).isEqualTo(ledger().rows().size() - 2);
        assertThat(summary.backend() + summary.platform()).isZero();
    }

    @Test
    void derivedRatiosAndUnconnectedChannelsAreNotInTheLedger() {
        connectSearch();

        List<String> refs = ledger().rows().stream().map(LedgerRow::metricRef).toList();

        assertThat(refs).contains("SEARCH.clicks", "SEARCH.impressions")
                .doesNotContain("SEARCH.ctr", "SEARCH.position")
                .noneMatch(ref -> ref.startsWith("ADS.") || ref.startsWith("STORE."));
    }

    @Test
    void anUnknownMetricRefHasNoRowAndIsNeverVerified() {
        connectSearch();

        assertThat(service.row(project.getId(), "SEARCH.nope", WINDOW, TODAY, NOW)).isEqualTo(Optional.empty());
        assertThat(service.verified(project.getId(), "SEARCH.nope", WINDOW, TODAY, NOW)).isFalse();
        assertThat(service.verified(project.getId(), "nonsense", WINDOW, TODAY, NOW)).isFalse();
    }

    // ---- recording ------------------------------------------------------------------------------

    @Test
    void aRecordedReadKeepsWhatConductorAndTheBackendSaidAtTheTime() {
        connectAds();
        connectProduct();
        adsInstalls(1, 1, 1, 1, 1, 1, 1);
        backendReference("ref_ads_tap_installs", 3, 3, 3, 3, 3, 4, 4);

        read("ADS", "installs", FROM, TO, 23, "Apple Ads console");

        MarketingPlatformRead stored = readRepository.findLatestByProject(project.getId()).get(0);
        assertThat(stored.getChannel()).isEqualTo("ADS");
        assertThat(stored.getMetric()).isEqualTo("installs");
        assertThat(stored.getValue()).isEqualByComparingTo("23");
        assertThat(stored.getConductorValue()).isEqualByComparingTo("7");
        assertThat(stored.getBackendValue()).isEqualByComparingTo("23");
        assertThat(stored.getVerdict()).isEqualTo("BACKEND");
        assertThat(stored.getRecordedBy()).isEqualTo(user.getId());
        assertThat(stored.getDayFrom()).isEqualTo(FROM);
        assertThat(stored.getDayTo()).isEqualTo(TO);
    }

    @Test
    void readsAreListedNewestFirstAndEveryOneIsKept() {
        connectSearch();
        service.record(project.getId(), "SEARCH", "clicks", FROM, TO, 9, "Search Console UI", "first", null, TODAY,
                NOW.minusMinutes(10));
        service.record(project.getId(), "SEARCH", "clicks", FROM, TO, 3, "Search Console UI", "second", null, TODAY,
                NOW);
        service.record(project.getId(), "SEARCH", "impressions", FROM, TO, 550, "Search Console UI", null, null, TODAY,
                NOW.minusMinutes(5));

        List<VerificationLedgerService.ReadEntry> clicks = service.reads(project.getId(), "search", "clicks", null);
        List<VerificationLedgerService.ReadEntry> all = service.reads(project.getId(), null, null, null);

        assertThat(clicks).extracting(VerificationLedgerService.ReadEntry::note).containsExactly("second", "first");
        assertThat(clicks.get(1).verdict()).isEqualTo("PLATFORM");
        assertThat(clicks.get(0).verdict()).isEqualTo("BOTH");
        assertThat(clicks.get(0).recordedBy()).isNull();
        assertThat(all).hasSize(3);
        assertThat(service.reads(project.getId(), null, null, 2)).hasSize(2);
    }

    @Test
    void thereIsNoWayToUpdateOrDeleteAReadInCodeOrStorage() {
        List<String> names = Arrays.stream(MarketingPlatformReadRepository.class.getMethods()).map(Method::getName)
                .toList();
        assertThat(names).noneMatch(n -> n.startsWith("delete") || n.startsWith("remove") || n.startsWith("update"));
        assertThat(MarketingPlatformReadRepository.class.getInterfaces())
                .as("a bare Repository marker, not JpaRepository's delete and flush surface")
                .containsExactly(org.springframework.data.repository.Repository.class);
        assertThat(MarketingPlatformRead.class.isAnnotationPresent(org.hibernate.annotations.Immutable.class)).isTrue();
        assertThat(Arrays.stream(VerificationLedgerService.class.getMethods()).map(Method::getName))
                .noneMatch(n -> n.startsWith("delete") || n.startsWith("update") || n.startsWith("remove"));
    }

    @Test
    void aReadMustEndYesterdayOrEarlierStartBeforeItEndsAndSpanAtMostNinetyTwoDays() {
        connectSearch();

        assertThatThrownBy(() -> read("SEARCH", "clicks", FROM, TODAY, 3, "x"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("yesterday");
        assertThatThrownBy(() -> read("SEARCH", "clicks", TO, FROM, 3, "x"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("after");
        assertThatThrownBy(() -> read("SEARCH", "clicks", TO.minusDays(92), TO, 3, "x"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("92");
        assertThat(read("SEARCH", "clicks", TO.minusDays(91), TO, 3, "x").row().platformRead()).isNotNull();
    }

    @Test
    void aReadNeedsAKnownConnectedChannelAndAReadableMetricAndASaneFigure() {
        connectSearch();

        assertThatThrownBy(() -> read("NOPE", "clicks", FROM, TO, 3, "x"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Unknown channel");
        assertThatThrownBy(() -> read("SEARCH", "nope", FROM, TO, 3, "x"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Unknown metric");
        assertThatThrownBy(() -> read("SEARCH", "ctr", FROM, TO, 0.1, "x"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("ratio");
        assertThatThrownBy(() -> read("ADS", "installs", FROM, TO, 3, "x"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("no connection");
        assertThatThrownBy(() -> read("SEARCH", "clicks", FROM, TO, -1, "x"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("zero or more");
        assertThatThrownBy(() -> read("SEARCH", "clicks", FROM, TO, Double.NaN, "x"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("zero or more");
        assertThatThrownBy(() -> read("SEARCH", "clicks", FROM, TO, 3, "  "))
                .isInstanceOf(BusinessException.class).hasMessageContaining("sourceName");
        assertThatThrownBy(() -> read("SEARCH", "clicks", FROM, TO, 3, "s".repeat(121)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("120");
        assertThat(readRepository.findLatestByProject(project.getId())).isEmpty();
    }
}
