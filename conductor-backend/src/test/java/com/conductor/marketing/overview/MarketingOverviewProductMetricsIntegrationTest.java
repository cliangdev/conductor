package com.conductor.marketing.overview;

import com.conductor.entity.ChannelMetricDaily;
import com.conductor.entity.Connection;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.exception.ConflictException;
import com.conductor.integration.MetricDefinition;
import com.conductor.integration.ingest.ChannelMetricDefinitionStore;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.integration.ingest.ConnectorFeedRepository;
import com.conductor.integration.ingest.ConnectorFeedStatus;
import com.conductor.marketing.overview.MarketingOverviewQueryService.ChannelView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.MetricView;
import com.conductor.marketing.overview.MarketingOverviewQueryService.Overview;
import com.conductor.repository.ChannelMetricDailyRepository;
import com.conductor.repository.ChannelMetricDefinitionRepository;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The in-app (PRODUCT) channel of the marketing overview against real Postgres: definitions declared by a
 * product metrics feed drive the metrics and the funnel, RATIO metrics roll up as sum over sum, feed targets
 * are read-only, reference values flag a mismatch, and a small sample reads as insufficient data.
 *
 * <p>"Today" is pinned to 2026-10-15: the 7-day window is Oct 8-14, the previous one Oct 1-7.
 */
@Transactional
class MarketingOverviewProductMetricsIntegrationTest extends AbstractNoneWebIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final OffsetDateTime NOW = TODAY.atTime(12, 0).atOffset(ZoneOffset.UTC);
    private static final String CONNECTOR = "product-metrics";

    @Autowired private MarketingOverviewQueryService service;
    @Autowired private ChannelMetricDailyRepository dailyRepository;
    @Autowired private ChannelMetricDefinitionRepository definitionRepository;
    @Autowired private ChannelMetricDefinitionStore definitionStore;
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
        user.setName("Product Metrics Test");
        user = userRepository.save(user);
        project = new Project();
        project.setName("Product Metrics Project");
        project.setKey("PM" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        project = projectRepository.save(project);
    }

    // ---- fixtures -------------------------------------------------------------------------------

    private String connect(String connectorId, String authType) {
        Connection connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId(connectorId);
        connection.setDisplayLabel(connectorId + " account");
        connection.setAuthType(authType);
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

    private static MetricDefinition sum(String key, String label, Integer funnelOrder) {
        return new MetricDefinition(key, label, "COUNT", "SUM", null, null, "UP", funnelOrder, null, null, null);
    }

    private static MetricDefinition ratio(String key, String numerator, String denominator) {
        return new MetricDefinition(key, key, "CURRENCY", "RATIO", numerator, denominator, "DOWN", null, null, null,
                null);
    }

    private void put(String connectionId, String connectorId, String channel, String metric, LocalDate day,
                     double value, Long sampleSize, boolean provisional) {
        ChannelMetricDaily row = new ChannelMetricDaily();
        row.setProjectId(project.getId());
        row.setConnectionId(connectionId);
        row.setConnectorId(connectorId);
        row.setChannel(channel);
        row.setMetric(metric);
        row.setDimensionKey("");
        row.setDay(day);
        row.setValue(BigDecimal.valueOf(value));
        row.setProvisional(provisional);
        row.setSampleSize(sampleSize);
        dailyRepository.saveAndFlush(row);
    }

    private void put(String connectionId, String metric, LocalDate day, double value) {
        put(connectionId, CONNECTOR, "PRODUCT", metric, day, value, null, false);
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
    void withoutAProductConnectionTheChannelIsNotConnectedAndTheFunnelKeepsItsPlaceholder() {
        Overview overview = overview7();

        assertThat(channel(overview, "PRODUCT").status()).isEqualTo("NOT_CONNECTED");
        assertThat(channel(overview, "PRODUCT").label()).isEqualTo("In-app");
        assertThat(channel(overview, "PRODUCT").connectHint().connectors())
                .extracting(c -> c.connectorId(), c -> c.name())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(CONNECTOR, "Product metrics"));
        assertThat(overview.funnel()).filteredOn(s -> s.key().equals("product_stages")).singleElement()
                .satisfies(s -> {
                    assertThat(s.available()).isFalse();
                    assertThat(s.unavailableReason()).isEqualTo("Connect product metrics");
                });
    }

    @Test
    void declaredFunnelMetricsBecomeStagesAfterStoreDownloadsInTheirOrder() {
        String pm = connect(CONNECTOR, "API_KEY");
        // declared out of order on purpose
        definitionStore.replace(project.getId(), pm, List.of(
                sum("paid", "Paid", 3), sum("first_open", "First opens", 1), sum("trial_started", "Trials", 2),
                sum("revenue_events", "Revenue events", null)));
        for (int d = 8; d <= 14; d++) {
            put(pm, "first_open", day(d), 100);
            put(pm, "trial_started", day(d), 10);
            put(pm, "paid", day(d), 4);
        }

        Overview overview = overview7();

        List<String> keys = overview.funnel().stream().map(s -> s.key()).toList();
        int downloads = keys.indexOf("store_downloads");
        assertThat(keys.subList(downloads, downloads + 4))
                .containsExactly("store_downloads", "product_first_open", "product_trial_started", "product_paid");
        assertThat(keys).doesNotContain("product_stages").doesNotContain("product_revenue_events");
        assertThat(overview.funnel()).filteredOn(s -> s.key().equals("product_trial_started")).singleElement()
                .satisfies(s -> {
                    assertThat(s.value()).isEqualTo(70.0);
                    assertThat(s.path()).isEqualTo("PRODUCT");
                    assertThat(s.source()).isEqualTo("PRODUCT");
                    assertThat(s.conversionFromKey()).isEqualTo("product_first_open");
                });
        // downloads has no data (no store connector yet), so the first in-app stage shows no conversion
        assertThat(overview.funnel()).filteredOn(s -> s.key().equals("product_first_open")).singleElement()
                .satisfies(s -> {
                    assertThat(s.available()).isTrue();
                    assertThat(s.conversionFromKey()).isNull();
                });
        ChannelView product = channel(overview, "PRODUCT");
        assertThat(product.status()).isEqualTo("LIVE");
        // the card leads with the last two funnel stages, then fills with the rest in stage order
        assertThat(product.metrics()).extracting(m -> m.metric()).containsExactly("trial_started", "paid", "first_open", "revenue_events");
        // only the declared funnel stages carry a funnelOrder
        assertThat(product.metrics()).filteredOn(m -> m.funnelOrder() != null).extracting(m -> m.metric())
                .containsExactlyInAnyOrder("first_open", "trial_started", "paid");
    }

    @Test
    void aRatioOverAWindowIsTheSumOfItsNumeratorOverTheSumOfItsDenominator() {
        String pm = connect(CONNECTOR, "API_KEY");
        definitionStore.replace(project.getId(), pm, List.of(
                sum("paid_spend", "Paid spend", null), sum("paid_trials", "Paid trials", null),
                ratio("cost_per_trial", "paid_spend", "paid_trials")));
        // Oct 8: $10 for 1 trial ($10 each), Oct 9: $90 for 9 trials ($10 each), Oct 10: $20 for 1 trial.
        // An average of daily ratios would say (10 + 10 + 20) / 3; sum over sum says 120 / 11.
        put(pm, "paid_spend", day(8), 10);
        put(pm, "paid_trials", day(8), 1);
        put(pm, "paid_spend", day(9), 90);
        put(pm, "paid_trials", day(9), 9);
        put(pm, "paid_spend", day(10), 20);
        put(pm, "paid_trials", day(10), 1);
        // previous window: $50 over 5 trials
        put(pm, "paid_spend", day(3), 50);
        put(pm, "paid_trials", day(3), 5);

        MetricView costPerTrial = metric(channel(overview7(), "PRODUCT"), "cost_per_trial");

        assertThat(costPerTrial.value()).isCloseTo(120.0 / 11.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(costPerTrial.previous()).isEqualTo(10.0);
        assertThat(costPerTrial.goodDirection()).isEqualTo("DOWN");
        assertThat(costPerTrial.series()).filteredOn(p -> p.day().equals(day(9))).singleElement()
                .satisfies(p -> assertThat(p.value()).isEqualTo(10.0));
    }

    @Test
    void aFeedTargetIsReadOnlyAndTheWorkspaceCannotSetOrClearIt() {
        String pm = connect(CONNECTOR, "API_KEY");
        definitionStore.replace(project.getId(), pm, List.of(
                new MetricDefinition("trial_started", "Trials", "COUNT", "SUM", null, null, "UP", 1,
                        new BigDecimal("200"), "UP", "28d")));
        put(pm, "trial_started", day(10), 5);

        MetricView trials = metric(channel(overview7(), "PRODUCT"), "trial_started");

        assertThat(trials.target()).isNotNull().satisfies(t -> {
            assertThat(t.value()).isEqualTo(200.0);
            assertThat(t.direction()).isEqualTo("UP");
            assertThat(t.readOnly()).isTrue();
            assertThat(t.source()).isEqualTo("Set in product-metrics account");
        });
        assertThatThrownBy(() -> service.setTarget(project.getId(), "PRODUCT", "trial_started", 50.0, null, "u"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("set in your product metrics connection");
        assertThatThrownBy(() -> service.clearTarget(project.getId(), "product", "trial_started"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void referenceSpendThatDisagreesWithTheAdsConnectorMarksTheAdsChannelAsAMismatch() {
        String pm = connect(CONNECTOR, "API_KEY");
        String ads = connect("apple-search-ads", "API_KEY");
        definitionStore.replace(project.getId(), pm, List.of(sum("first_open", "First opens", 1)));
        for (int d = 8; d <= 14; d++) {
            put(ads, "apple-search-ads", "ADS", "spend", day(d), 100, null, false);
            put(pm, CONNECTOR, "PRODUCT", "ref_ads_spend", day(d), 100.5, null, false); // 0.5% off: fine
        }
        assertThat(channel(overview7(), "ADS").status()).isEqualTo("LIVE");
        assertThat(channel(overview7(), "ADS").mismatch()).isNull();

        for (int d = 8; d <= 14; d++) {
            dailyRepository.deleteAll(dailyRepository.findByProjectIdAndChannelAndMetricInAndDayBetween(
                    project.getId(), "PRODUCT", List.of("ref_ads_spend"), day(d), day(d)));
        }
        dailyRepository.flush();
        for (int d = 8; d <= 14; d++) {
            put(pm, CONNECTOR, "PRODUCT", "ref_ads_spend", day(d), 110, null, false);
        }

        Overview overview = overview7();
        ChannelView adsChannel = channel(overview, "ADS");

        assertThat(adsChannel.status()).isEqualTo("MISMATCH");
        assertThat(adsChannel.mismatch()).satisfies(m -> {
            assertThat(m.referenceValue()).isEqualTo(770.0);
            assertThat(m.channelValue()).isEqualTo(700.0);
            assertThat(m.gapPct()).isCloseTo(70.0 / 770.0, org.assertj.core.data.Offset.offset(1e-9));
            assertThat(m.from()).isEqualTo(day(8));
            assertThat(m.to()).isEqualTo(day(14));
        });
        // the metrics still read normally, and reference values are never listed as metrics
        assertThat(metric(adsChannel, "spend").value()).isEqualTo(700.0);
        assertThat(channel(overview, "PRODUCT").metrics()).extracting(m -> m.metric()).containsExactly("first_open");
    }

    @Test
    void provisionalReferenceDaysAreNotComparedAndAMissingOwnDayIsSkipped() {
        String pm = connect(CONNECTOR, "API_KEY");
        String ads = connect("apple-search-ads", "API_KEY");
        definitionStore.replace(project.getId(), pm, List.of(sum("first_open", "First opens", 1)));
        for (int d = 8; d <= 14; d++) {
            put(ads, "apple-search-ads", "ADS", "spend", day(d), 100, null, false);
        }
        put(pm, CONNECTOR, "PRODUCT", "ref_ads_spend", day(14), 999, null, true); // provisional: ignored
        put(pm, CONNECTOR, "PRODUCT", "ref_ads_spend", day(2), 999, null, false); // outside the window: ignored

        assertThat(channel(overview7(), "ADS").status()).isEqualTo("LIVE");
    }

    @Test
    void aSmallSampleSizeReadsAsInsufficientDataAndNoiseWhileEnoughSamplesDoNot() {
        String pm = connect(CONNECTOR, "API_KEY");
        definitionStore.replace(project.getId(), pm, List.of(
                sum("trial_started", "Trials", 1), sum("first_open", "First opens", 2)));
        for (int d = 1; d <= 14; d++) {
            double growing = d >= 8 ? 40 : 10; // a big move that the change detector alone would not call noise
            put(pm, CONNECTOR, "PRODUCT", "trial_started", day(d), growing, 2L, false);  // 14 observations a week
            put(pm, CONNECTOR, "PRODUCT", "first_open", day(d), growing, 10L, false);    // 70 a week
        }

        ChannelView product = channel(overview7(), "PRODUCT");

        assertThat(metric(product, "trial_started").insufficientData()).isTrue();
        assertThat(metric(product, "trial_started").noise()).isTrue();
        assertThat(metric(product, "first_open").insufficientData()).isFalse();
        assertThat(metric(product, "first_open").noise()).isFalse();
    }

    @Test
    void breakdownRowsBecomeSourceAndCampaignTables() {
        String pm = connect(CONNECTOR, "API_KEY");
        definitionStore.replace(project.getId(), pm, List.of(
                sum("first_open", "First opens", 1), sum("paid_spend", "Paid spend", null),
                sum("paid_trials", "Paid trials", null), ratio("cost_per_trial", "paid_spend", "paid_trials")));
        put(pm, "first_open", day(10), 30);
        for (String[] row : new String[][]{
                {"first_open", "source:apple_search_ads", "12"}, {"first_open", "source:organic", "18"},
                {"paid_spend", "campaign:1001:Brand: US", "40"}, {"paid_trials", "campaign:1001:Brand: US", "4"}}) {
            ChannelMetricDaily r = new ChannelMetricDaily();
            r.setProjectId(project.getId());
            r.setConnectionId(pm);
            r.setConnectorId(CONNECTOR);
            r.setChannel("PRODUCT");
            r.setMetric(row[0]);
            r.setDimensionKey(row[1]);
            r.setDay(day(10));
            r.setValue(new BigDecimal(row[2]));
            dailyRepository.saveAndFlush(r);
        }

        var detail = service.channel(project.getId(), MetricCatalog.channel("PRODUCT").orElseThrow(), 7, TODAY, NOW);

        assertThat(detail.dimensions()).extracting(d -> d.key()).containsExactly("sources", "campaigns");
        var sources = detail.dimensions().get(0);
        assertThat(sources.rows()).extracting(r -> r.key(), r -> r.values().get(0))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("organic", 18.0),
                        org.assertj.core.groups.Tuple.tuple("apple_search_ads", 12.0));
        var campaigns = detail.dimensions().get(1);
        assertThat(campaigns.rows()).singleElement().satisfies(r -> {
            assertThat(r.key()).isEqualTo("1001");
            assertThat(r.label()).isEqualTo("Brand: US");
        });
        assertThat(campaigns.columns()).extracting(c -> c.key()).contains("cost_per_trial");
    }

    // ---- definitions store ----------------------------------------------------------------------

    @Test
    void definitionsAreUpsertedOnEachPullAndMetricsNoLongerSentAreRemoved() {
        String pm = connect(CONNECTOR, "API_KEY");
        definitionStore.replace(project.getId(), pm, List.of(
                sum("first_open", "First opens", 1), sum("paid", "Paid", 2)));
        assertThat(definitionRepository.findByConnectionId(pm)).extracting(r -> r.getMetric())
                .containsExactlyInAnyOrder("first_open", "paid");

        definitionStore.replace(project.getId(), pm, List.of(
                new MetricDefinition("first_open", "Opens", "COUNT", "SUM", null, null, "UP", 3,
                        new BigDecimal("500"), "UP", "28d"),
                sum("trial_started", "Trials", 4)));

        var rows = definitionRepository.findByConnectionId(pm);
        assertThat(rows).extracting(r -> r.getMetric()).containsExactlyInAnyOrder("first_open", "trial_started");
        assertThat(rows).filteredOn(r -> r.getMetric().equals("first_open")).singleElement().satisfies(r -> {
            assertThat(r.getLabel()).isEqualTo("Opens");
            assertThat(r.getFunnelOrder()).isEqualTo(3);
            assertThat(r.getTargetValue()).isEqualByComparingTo("500");
            assertThat(r.getTargetWindow()).isEqualTo("28d");
        });
    }
}
