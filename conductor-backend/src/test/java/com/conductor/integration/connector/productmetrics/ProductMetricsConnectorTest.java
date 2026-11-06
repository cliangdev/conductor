package com.conductor.integration.connector.productmetrics;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsPull;
import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.MetricChannel;
import com.conductor.integration.OutboundUrlGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.never;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ProductMetricsConnectorTest {

    private static final String URL = "https://metrics.example.test/v1/metrics";
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 14);

    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private ProductMetricsConnector connector;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        // the permissive guard allows the fake host; the strict one is exercised separately below
        connector = new ProductMetricsConnector(new ProductMetricsClient(mapper, new OutboundUrlGuard(true), restTemplate));
    }

    private static ConnectionContext ctx(String url) {
        return new ConnectionContext("proj", "product-metrics", "conn", "secret-key", null, null,
                Map.of("baseUrl", url), null);
    }

    private static String response(String version, String metrics, String points, String reference) {
        return """
                {"contractVersion":%s,"timezone":"America/Los_Angeles","dataCompleteThrough":"2026-10-12",
                 "restatementDays":3,"metrics":%s,"points":%s,"reference":%s}""".formatted(version, metrics, points, reference);
    }

    private static final String METRICS = """
            [{"key":"first_open","label":"First opens","unit":"COUNT","aggregation":"SUM","funnelOrder":1},
             {"key":"subscribers","label":"Subscribers","unit":"COUNT","aggregation":"LEVEL"},
             {"key":"spend","label":"Spend","unit":"CURRENCY","aggregation":"SUM","good":"DOWN"},
             {"key":"trials","label":"Trials","unit":"COUNT","aggregation":"SUM",
              "target":{"value":200,"direction":"UP","window":"28d"}},
             {"key":"cost_per_trial","label":"Cost per trial","unit":"CURRENCY","aggregation":"RATIO",
              "numerator":"spend","denominator":"trials","good":"DOWN"}]""";

    private static final String POINTS = """
            [{"metric":"first_open","day":"2026-10-11","value":118,"dimension":null,"sampleSize":118},
             {"metric":"first_open","day":"2026-10-11","value":40,"dimension":"source:apple_search_ads"},
             {"metric":"first_open","day":"2026-10-13","value":90,"dimension":"campaign:7:Brand: US"},
             {"metric":"subscribers","day":"2026-10-13","value":512},
             {"metric":"first_open","day":"2026-09-01","value":5}]""";

    private static final String REFERENCE = """
            [{"metric":"store_downloads","day":"2026-10-11","value":131,"dimension":null},
             {"metric":"ads_spend","day":"2026-10-11","value":212.4}]""";

    private void respond(String body) {
        server.expect(once(), requestTo(URL + "?from=2026-10-01&to=2026-10-14"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    // ---- shape ------------------------------------------------------------------------------------

    @Test
    void declaresAProductSourceWithTheDocumentedWindows() {
        assertThat(connector.getId()).isEqualTo("product-metrics");
        assertThat(connector.getMetadata().name()).isEqualTo("Product metrics");
        assertThat(connector.getMetadata().category().name()).isEqualTo("ANALYTICS");
        assertThat(connector.getSpec().singleInstance()).isFalse();
        assertThat(connector.channel()).isEqualTo(MetricChannel.PRODUCT);
        assertThat(connector.backfillDays()).isEqualTo(90);
        assertThat(connector.maxDaysPerPull()).isEqualTo(14);
        assertThat(connector.trailingDays()).isEqualTo(3);
        assertThat(connector.trailingDays((Integer) null)).isEqualTo(3);
        assertThat(connector.trailingDays(Integer.valueOf(7))).isEqualTo(7);
    }

    // ---- parsing ----------------------------------------------------------------------------------

    @Test
    void aValidResponseBecomesPointsDefinitionsAndAProvisionalBoundary() {
        respond(response("1", METRICS, POINTS, REFERENCE));

        ChannelMetricsPull pull = connector.pull(ctx(URL), FROM, TO);

        server.verify();
        assertThat(pull.provisionalFrom()).isEqualTo(LocalDate.of(2026, 10, 13)); // dataCompleteThrough + 1
        assertThat(pull.snapshotMetrics()).containsExactly("subscribers");
        assertThat(pull.definitions()).extracting(d -> d.metric())
                .containsExactly("first_open", "subscribers", "spend", "trials", "cost_per_trial");
        assertThat(pull.definitions()).filteredOn(d -> d.metric().equals("trials")).singleElement().satisfies(d -> {
            assertThat(d.targetValue()).isEqualByComparingTo("200");
            assertThat(d.targetDirection()).isEqualTo("UP");
            assertThat(d.targetWindow()).isEqualTo("28d");
        });
        assertThat(pull.definitions()).filteredOn(d -> d.metric().equals("cost_per_trial")).singleElement()
                .satisfies(d -> {
                    assertThat(d.aggregation()).isEqualTo("RATIO");
                    assertThat(d.numerator()).isEqualTo("spend");
                    assertThat(d.denominator()).isEqualTo("trials");
                    assertThat(d.good()).isEqualTo("DOWN");
                });
        assertThat(pull.definitions()).filteredOn(d -> d.metric().equals("first_open")).singleElement()
                .satisfies(d -> assertThat(d.good()).isEqualTo("UP")); // the default
        // points outside from..to are dropped; dimensions and sample sizes survive
        assertThat(pull.points()).extracting(ChannelMetricPoint::metric, ChannelMetricPoint::dimensionKey,
                        ChannelMetricPoint::day, ChannelMetricPoint::sampleSize)
                .contains(org.assertj.core.groups.Tuple.tuple("first_open", "", LocalDate.of(2026, 10, 11), 118L),
                        org.assertj.core.groups.Tuple.tuple("first_open", "source:apple_search_ads",
                                LocalDate.of(2026, 10, 11), null),
                        org.assertj.core.groups.Tuple.tuple("first_open", "campaign:7:Brand: US",
                                LocalDate.of(2026, 10, 13), null));
        assertThat(pull.points()).noneMatch(p -> p.day().equals(LocalDate.of(2026, 9, 1)));
        // reference values are stored as hidden ref_ metrics
        assertThat(pull.points()).filteredOn(p -> p.metric().startsWith("ref_"))
                .extracting(ChannelMetricPoint::metric, p -> p.value().doubleValue())
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("ref_store_downloads", 131.0),
                        org.assertj.core.groups.Tuple.tuple("ref_ads_spend", 212.4));
    }

    @Test
    void theNextTrailingWindowIsTheRestatementWindowOrTheLagBehindCompleteThroughIfLonger() {
        // complete through Oct 12, pull ends Oct 14: lag 2 < restatement 3
        respond(response("1", METRICS, "[]", "[]"));
        assertThat(connector.pull(ctx(URL), FROM, TO).trailingDays()).isEqualTo(3);

        // pull ends Oct 20: the backend is 8 days behind, so re-read 8 days to finalise what is still provisional
        server.reset();
        server.expect(once(), requestTo(URL + "?from=2026-10-07&to=2026-10-20"))
                .andRespond(withSuccess(response("1", METRICS, "[]", "[]"), MediaType.APPLICATION_JSON));
        assertThat(connector.pull(ctx(URL), LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 20)).trailingDays())
                .isEqualTo(8);
    }

    @Test
    void aBadRequestIsRetriedWithBackoffNotParkedAsSetupRequired() {
        server.expect(once(), requestTo(URL + "?from=2026-10-01&to=2026-10-14"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"the range is at most 31 days, got 46 (use several calls)\"}"));

        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class, e -> {
                    assertThat(e.health()).isEqualTo(ConnectorHealth.DEGRADED);
                    assertThat(e.getMessage()).contains("HTTP 400").contains("at most 31 days");
                });
    }

    @Test
    void aWrongContractVersionIsRefusedWithThePlainReason() {
        for (String version : new String[]{"4", "5", "0"}) {
            server.reset();
            respond(response(version, METRICS, "[]", "[]"));

            assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO)).as("version " + version)
                    .isInstanceOfSatisfying(ChannelMetricsSourceException.class, e -> {
                        assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
                        assertThat(e.getMessage()).isEqualTo("The server speaks product metrics contract version "
                                + version + "; Conductor reads versions 1, 2 and 3.");
                    });
        }
    }

    @Test
    void aMissingContractVersionNamesTheVersionsConductorReads() {
        respond("""
                {"timezone":"UTC","dataCompleteThrough":"2026-10-12","restatementDays":3,"metrics":[],"points":[]}""");

        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .hasMessage("The response has no contractVersion. Conductor reads versions 1, 2 and 3.");
    }

    @Test
    void anUnknownAggregationIsRefused() {
        respond(response("1", """
                [{"key":"opens","label":"Opens","unit":"COUNT","aggregation":"AVERAGE"}]""", "[]", "[]"));

        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .isInstanceOf(ChannelMetricsSourceException.class)
                .hasMessage("The metric 'opens' has an unknown aggregation 'AVERAGE'; expected SUM, LEVEL or RATIO.");
    }

    @Test
    void aRatioNamingAMissingKeyIsRefused() {
        respond(response("1", """
                [{"key":"spend","label":"Spend","unit":"CURRENCY","aggregation":"SUM"},
                 {"key":"cpt","label":"CPT","unit":"CURRENCY","aggregation":"RATIO","numerator":"spend","denominator":"trials"}]""",
                "[]", "[]"));

        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .isInstanceOf(ChannelMetricsSourceException.class)
                .hasMessage("Metric 'cpt' is a RATIO of 'trials', which the response does not define.");
    }

    @Test
    void aRatioWithoutAnyNumeratorAndDenominatorIsRefused() {
        respond(response("1", """
                [{"key":"cpt","label":"CPT","unit":"CURRENCY","aggregation":"RATIO"}]""", "[]", "[]"));

        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .hasMessage("The RATIO metric 'cpt' must name both a numerator and a denominator.");
    }

    @Test
    void aRatioOfARatioIsRefusedSoARollUpCanNeverLoop() {
        respond(response("1", """
                [{"key":"a","label":"A","unit":"COUNT","aggregation":"SUM"},
                 {"key":"r1","label":"R1","unit":"PERCENT","aggregation":"RATIO","numerator":"a","denominator":"a"},
                 {"key":"r2","label":"R2","unit":"PERCENT","aggregation":"RATIO","numerator":"r1","denominator":"a"}]""",
                "[]", "[]"));

        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .hasMessageContaining("which is itself a RATIO");
    }

    @Test
    void pointsForARatioAMetricNobodyDefinedAndPersonalDimensionsAreRefused() {
        String metrics = """
                [{"key":"a","label":"A","unit":"COUNT","aggregation":"SUM"},
                 {"key":"r","label":"R","unit":"PERCENT","aggregation":"RATIO","numerator":"a","denominator":"a"}]""";
        respond(response("1", metrics, """
                [{"metric":"r","day":"2026-10-11","value":0.5}]""", "[]"));
        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO)).hasMessageContaining("is a RATIO and is computed");

        server.reset();
        respond(response("1", metrics, """
                [{"metric":"ghost","day":"2026-10-11","value":1}]""", "[]"));
        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO)).hasMessageContaining("'ghost'");

        server.reset();
        respond(response("1", metrics, """
                [{"metric":"a","day":"2026-10-11","value":1,"dimension":"user:42"}]""", "[]"));
        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO)).hasMessageContaining("start with source: or campaign:");
    }

    @Test
    void reservedKeysAreRefused() {
        respond(response("1", """
                [{"key":"ref_x","label":"X","unit":"COUNT","aggregation":"SUM"}]""", "[]", "[]"));
        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO)).hasMessageContaining("not starting with ref_");
    }

    @Test
    void aReferenceValueWithoutAMetricNameIsRefused() {
        respond(response("1", METRICS, "[]", """
                [{"day":"2026-10-11","value":1}]"""));

        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .hasMessage("A reference value names 'null'; expected one of [ads_spend, store_downloads].");
    }

    // ---- contract v2 ------------------------------------------------------------------------------

    private static final String METRICS_V2 = """
            [{"key":"first_open","label":"First opens","unit":"COUNT","aggregation":"SUM","funnelOrder":1,
              "basis":"COHORT","area":"ACQUISITION","description":"People who opened the app for the first time"},
             {"key":"retained","label":"Retained","unit":"COUNT","aggregation":"SUM"},
             {"key":"matured","label":"Matured","unit":"COUNT","aggregation":"SUM"},
             {"key":"retention","label":"Retention","unit":"PERCENT","aggregation":"RATIO","numerator":"retained",
              "denominator":"matured","area":"RETENTION","headline":2,
              "target":{"value":0.25,"direction":"UP","watchBandPct":15}}]""";

    private static String v2(String restatementDays, String metrics, String points) {
        return """
                {"contractVersion":2,"timezone":"America/Los_Angeles","dataCompleteThrough":"2026-10-12",
                 "restatementDays":%s,"metrics":%s,"points":%s}""".formatted(restatementDays, metrics, points);
    }

    @Test
    void aVersion2ResponseCarriesBasisAreaDescriptionHeadlineAndTheWatchBand() {
        respond(v2("60", METRICS_V2, "[]"));

        ChannelMetricsPull pull = connector.pull(ctx(URL), FROM, TO);

        assertThat(pull.definitions()).filteredOn(d -> d.metric().equals("first_open")).singleElement()
                .satisfies(d -> {
                    assertThat(d.basis()).isEqualTo("COHORT");
                    assertThat(d.area()).isEqualTo("ACQUISITION");
                    assertThat(d.description()).isEqualTo("People who opened the app for the first time");
                });
        assertThat(pull.definitions()).filteredOn(d -> d.metric().equals("retention")).singleElement()
                .satisfies(d -> {
                    assertThat(d.basis()).isEqualTo("EVENT"); // the default
                    assertThat(d.headline()).isEqualTo(2);
                    assertThat(d.watchBandPct()).isEqualByComparingTo("15");
                    assertThat(d.targetValue()).isEqualByComparingTo("0.25");
                });
        assertThat(pull.trailingDays()).isEqualTo(60);
    }

    @Test
    void aVersion1ResponseStillReadsAsBeforeAndIgnoresVersion2Fields() {
        respond(response("1", """
                [{"key":"first_open","label":"First opens","unit":"COUNT","aggregation":"SUM","basis":"COHORT",
                  "area":"NONSENSE","headline":0}]""", "[]", "[]"));

        ChannelMetricsPull pull = connector.pull(ctx(URL), FROM, TO);

        assertThat(pull.definitions()).singleElement().satisfies(d -> {
            assertThat(d.basis()).isEqualTo("EVENT");
            assertThat(d.area()).isNull();
            assertThat(d.headline()).isNull();
        });
    }

    @Test
    void restatementIsCappedAt30OnVersion1And60OnVersion2() {
        respond(response("1", METRICS, "[]", "[]").replace("\"restatementDays\":3", "\"restatementDays\":31"));
        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .hasMessage("restatementDays must be a whole number from 0 to 30.");

        server.reset();
        respond(v2("61", METRICS_V2, "[]"));
        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .hasMessage("restatementDays must be a whole number from 0 to 60.");
    }

    @Test
    void aBadBasisAreaDescriptionOrHeadlineIsRefused() {
        String base = """
                [{"key":"a","label":"A","unit":"COUNT","aggregation":"SUM",%s}]""";
        respond(v2("3", base.formatted("\"basis\":\"WEEKLY\""), "[]"));
        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .hasMessage("The metric 'a' has basis 'WEEKLY'; expected EVENT or COHORT.");

        server.reset();
        respond(v2("3", base.formatted("\"area\":\"GROWTH\""), "[]"));
        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .hasMessageContaining("has area 'GROWTH'");

        server.reset();
        respond(v2("3", base.formatted("\"description\":\"" + "x".repeat(241) + "\""), "[]"));
        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO)).hasMessageContaining("longer than 240");

        server.reset();
        respond(v2("3", base.formatted("\"headline\":0"), "[]"));
        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO)).hasMessageContaining("headline");
    }

    @Test
    void keywordAndAdDimensionsAreAcceptedOnVersion2Only() {
        String metrics = """
                [{"key":"a","label":"A","unit":"COUNT","aggregation":"SUM"}]""";
        String points = """
                [{"metric":"a","day":"2026-10-11","value":1,"dimension":"keyword:77:meal planner: free"},
                 {"metric":"a","day":"2026-10-11","value":2,"dimension":"ad:9:Spring creative"}]""";
        respond(v2("3", metrics, points));

        assertThat(connector.pull(ctx(URL), FROM, TO).points()).extracting(ChannelMetricPoint::dimensionKey)
                .containsExactly("keyword:77:meal planner: free", "ad:9:Spring creative");

        server.reset();
        respond(response("1", metrics, points, "[]"));
        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .hasMessageContaining("start with source: or campaign:");

        server.reset();
        respond(v2("3", metrics, """
                [{"metric":"a","day":"2026-10-11","value":1,"dimension":"keyword:77"}]"""));
        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .hasMessageContaining("source:, campaign:, keyword: or ad:");
    }

    // ---- contract v3 ------------------------------------------------------------------------------

    private static final List<String> V3_PREFIXES = List.of("offer", "link", "message", "intent", "aso", "channel",
            "paywall");

    private static final String ALL_PREFIXES_V3 =
            "source:, campaign:, keyword:, ad:, offer:, link:, message:, intent:, aso:, channel: or paywall:";

    private static final String METRICS_V3 = """
            [{"key":"activated","label":"Activated installs","unit":"COUNT","aggregation":"SUM","area":"ACTIVATION",
              "exclusions":"Likely App Review installs (7 in the window)"},
             {"key":"aso_rank","label":"Organic rank","unit":"POSITION","aggregation":"LEVEL","good":"DOWN"},
             {"key":"offer_redemptions","label":"Offer redemptions","unit":"COUNT","aggregation":"SUM"}]""";

    private static String versioned(int version, String metrics, String points, String reference) {
        return """
                {"contractVersion":%d,"timezone":"America/Los_Angeles","dataCompleteThrough":"2026-10-12",
                 "restatementDays":14,"metrics":%s,"points":%s,"reference":%s}"""
                .formatted(version, metrics, points, reference);
    }

    private static String v3(String metrics, String points, String reference) {
        return versioned(3, metrics, points, reference);
    }

    private static String point(String dimension) {
        return """
                [{"metric":"activated","day":"2026-10-11","value":1,"dimension":"%s"}]""".formatted(dimension);
    }

    @Test
    void aVersion3ResponseReadsEveryNewDimensionPrefixAndBothNewReferenceMetrics() {
        respond(v3(METRICS_V3, """
                [{"metric":"activated","day":"2026-10-11","value":12,"dimension":"channel:email"},
                 {"metric":"offer_redemptions","day":"2026-10-11","value":3,"dimension":"offer:CREATOR10"},
                 {"metric":"activated","day":"2026-10-11","value":4,"dimension":"link:ab12cd"},
                 {"metric":"activated","day":"2026-10-11","value":5,"dimension":"message:fast weeknight dinners"},
                 {"metric":"activated","day":"2026-10-11","value":6,"dimension":"intent:plan meals"},
                 {"metric":"aso_rank","day":"2026-10-11","value":7,"dimension":"aso:meal planner"},
                 {"metric":"activated","day":"2026-10-11","value":8,"dimension":"paywall:Cook smarter: try it free"},
                 {"metric":"activated","day":"2026-10-11","value":9,"dimension":"keyword:77:meal planner"}]""", """
                [{"metric":"store_downloads","day":"2026-10-11","value":131},
                 {"metric":"ads_spend","day":"2026-10-11","value":212.4},
                 {"metric":"ads_tap_installs","day":"2026-10-11","value":58},
                 {"metric":"sales_first_time_downloads","day":"2026-10-11","value":129}]"""));

        ChannelMetricsPull pull = connector.pull(ctx(URL), FROM, TO);

        assertThat(pull.contractVersion()).isEqualTo(3);
        assertThat(pull.points()).filteredOn(p -> !p.metric().startsWith("ref_"))
                .extracting(ChannelMetricPoint::dimensionKey)
                .containsExactly("channel:email", "offer:CREATOR10", "link:ab12cd", "message:fast weeknight dinners",
                        "intent:plan meals", "aso:meal planner", "paywall:Cook smarter: try it free",
                        "keyword:77:meal planner");
        assertThat(pull.points()).filteredOn(p -> p.metric().startsWith("ref_"))
                .extracting(ChannelMetricPoint::metric, ChannelMetricPoint::dimensionKey,
                        p -> p.value().doubleValue())
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("ref_store_downloads", "", 131.0),
                        org.assertj.core.groups.Tuple.tuple("ref_ads_spend", "", 212.4),
                        org.assertj.core.groups.Tuple.tuple("ref_ads_tap_installs", "", 58.0),
                        org.assertj.core.groups.Tuple.tuple("ref_sales_first_time_downloads", "", 129.0));
        assertThat(pull.snapshotMetrics()).containsExactly("aso_rank");
    }

    @Test
    void aVersion3ResponseCarriesExclusionsAndThePositionUnit() {
        respond(v3(METRICS_V3, "[]", "[]"));

        ChannelMetricsPull pull = connector.pull(ctx(URL), FROM, TO);

        assertThat(pull.definitions()).filteredOn(d -> d.metric().equals("activated")).singleElement()
                .satisfies(d -> assertThat(d.exclusions()).isEqualTo("Likely App Review installs (7 in the window)"));
        assertThat(pull.definitions()).filteredOn(d -> d.metric().equals("aso_rank")).singleElement()
                .satisfies(d -> {
                    assertThat(d.unit()).isEqualTo("POSITION");
                    assertThat(d.good()).isEqualTo("DOWN");
                    assertThat(d.exclusions()).isNull();
                });
        assertThat(pull.definitions()).filteredOn(d -> d.metric().equals("offer_redemptions")).singleElement()
                .satisfies(d -> assertThat(d.exclusions()).isNull());
    }

    @Test
    void exclusionsAtTheLimitAreAcceptedAndALongerTextIsRefusedWithTheContractReason() {
        String metric = """
                [{"key":"a","label":"A","unit":"COUNT","aggregation":"SUM","exclusions":"%s"}]""";
        respond(v3(metric.formatted("x".repeat(240)), "[]", "[]"));
        assertThat(connector.pull(ctx(URL), FROM, TO).definitions()).singleElement()
                .satisfies(d -> assertThat(d.exclusions()).hasSize(240));

        server.reset();
        respond(v3(metric.formatted("x".repeat(241)), "[]", "[]"));
        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO)).isInstanceOfSatisfying(
                ChannelMetricsSourceException.class, e -> {
                    assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
                    assertThat(e.getMessage()).isEqualTo("The exclusions of 'a' are longer than 240 characters.");
                });
    }

    @Test
    void aVersion3DimensionAtTheLimitIsAcceptedAndALongerOrEmptyOneIsRefusedWithTheContractReason() {
        String metrics = """
                [{"key":"activated","label":"Activated","unit":"COUNT","aggregation":"SUM"}]""";
        for (String prefix : V3_PREFIXES) {
            server.reset();
            respond(v3(metrics, point(prefix + ":" + "x".repeat(190)), "[]"));
            assertThat(connector.pull(ctx(URL), FROM, TO).points()).as(prefix + " at 190").hasSize(1);

            for (String bad : new String[]{prefix + ":" + "x".repeat(191), prefix + ":"}) {
                server.reset();
                respond(v3(metrics, point(bad), "[]"));
                assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO)).as(bad.length() + " chars")
                        .isInstanceOfSatisfying(ChannelMetricsSourceException.class, e -> {
                            assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
                            assertThat(e.getMessage()).startsWith("A dimension must be empty or start with "
                                    + ALL_PREFIXES_V3 + "; got '" + prefix + ":");
                        });
            }
        }
    }

    @Test
    void anUnknownPrefixIsStillRefusedOnVersion3AndReferenceValuesStayTotals() {
        String metrics = """
                [{"key":"activated","label":"Activated","unit":"COUNT","aggregation":"SUM"}]""";
        respond(v3(metrics, point("user:42"), "[]"));
        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .hasMessage("A dimension must be empty or start with " + ALL_PREFIXES_V3 + "; got 'user:42'.");

        server.reset();
        respond(v3(metrics, "[]", """
                [{"metric":"ads_tap_installs","day":"2026-10-11","value":5,"dimension":"offer:X"}]"""));
        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .hasMessage("Reference values are totals; leave dimension empty.");
    }

    @Test
    void aVersion2ResponseIgnoresVersion3FieldsAndRefusesTheVersion3OnlyPrefixesAndUnit() {
        respond(versioned(2, """
                [{"key":"a","label":"A","unit":"COUNT","aggregation":"SUM","exclusions":"Likely App Review installs"}]""",
                "[]", """
                [{"metric":"store_downloads","day":"2026-10-11","value":131},
                 {"metric":"ads_tap_installs","day":"2026-10-11","value":58},
                 {"metric":"sales_first_time_downloads","day":"2026-10-11","value":129}]"""));
        ChannelMetricsPull pull = connector.pull(ctx(URL), FROM, TO);
        assertThat(pull.definitions()).singleElement().satisfies(d -> assertThat(d.exclusions()).isNull());
        assertThat(pull.points()).extracting(ChannelMetricPoint::metric).containsExactly("ref_store_downloads");
        assertThat(pull.contractVersion()).isEqualTo(2);

        for (String prefix : V3_PREFIXES) {
            server.reset();
            respond(versioned(2, """
                    [{"key":"activated","label":"Activated","unit":"COUNT","aggregation":"SUM"}]""",
                    point(prefix + ":x"), "[]"));
            assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO)).as(prefix)
                    .hasMessage("A dimension must be empty or start with source:, campaign:, keyword: or ad:; got '"
                            + prefix + ":x'.");
        }

        server.reset();
        respond(versioned(2, """
                [{"key":"aso_rank","label":"Rank","unit":"POSITION","aggregation":"LEVEL"}]""", "[]", "[]"));
        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO)).hasMessage(
                "The metric 'aso_rank' has an unknown unit 'POSITION'; expected COUNT, CURRENCY or PERCENT.");
    }

    @Test
    void aVersion1ResponseIgnoresVersion3FieldsAndRefusesTheVersion3OnlyPrefixesAndUnit() {
        respond(versioned(1, """
                [{"key":"a","label":"A","unit":"COUNT","aggregation":"SUM","exclusions":"Likely App Review installs"}]""",
                "[]", """
                [{"metric":"ads_spend","day":"2026-10-11","value":212.4},
                 {"metric":"ads_tap_installs","day":"2026-10-11","value":58}]"""));
        ChannelMetricsPull pull = connector.pull(ctx(URL), FROM, TO);
        assertThat(pull.definitions()).singleElement().satisfies(d -> assertThat(d.exclusions()).isNull());
        assertThat(pull.points()).extracting(ChannelMetricPoint::metric).containsExactly("ref_ads_spend");

        server.reset();
        respond(versioned(1, """
                [{"key":"activated","label":"Activated","unit":"COUNT","aggregation":"SUM"}]""",
                point("offer:CREATOR10"), "[]"));
        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .hasMessage("A dimension must be empty or start with source: or campaign:; got 'offer:CREATOR10'.");

        server.reset();
        respond(versioned(1, """
                [{"key":"aso_rank","label":"Rank","unit":"POSITION","aggregation":"LEVEL"}]""", "[]", "[]"));
        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO)).hasMessageContaining("unknown unit 'POSITION'");
    }

    @Test
    void anUnknownReferenceMetricIsIgnoredAndLoggedOnceInEveryVersion() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProductMetricsContract.class);
        Level before = logger.getLevel();
        logger.setLevel(Level.ALL);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            for (int version = 1; version <= 3; version++) {
                server.reset();
                logs.list.clear();
                respond(versioned(version, METRICS, "[]", """
                        [{"metric":"revenue","day":"2026-10-10","value":1},
                         {"metric":"revenue","day":"2026-10-11","value":2},
                         {"metric":"revenue","day":"2026-10-12","value":3},
                         {"metric":"store_downloads","day":"2026-10-11","value":131}]"""));

                ChannelMetricsPull pull = connector.pull(ctx(URL), FROM, TO);

                assertThat(pull.points()).as("v" + version).extracting(ChannelMetricPoint::metric)
                        .containsExactly("ref_store_downloads");
                assertThat(logs.list).as("v" + version)
                        .filteredOn(e -> e.getFormattedMessage().contains("revenue")).hasSize(1);
            }
        } finally {
            logger.detachAppender(logs);
            logger.setLevel(before);
        }
    }

    // ---- HTTP -------------------------------------------------------------------------------------

    @Test
    void theKeyGoesInAsABearerTokenAndTheWindowAsQueryParameters() {
        server.expect(once(), requestTo(URL + "?from=2026-10-01&to=2026-10-14"))
                .andExpect(header("Authorization", "Bearer secret-key"))
                .andExpect(header("Accept", "application/json"))
                .andRespond(withSuccess(response("1", METRICS, "[]", "[]"), MediaType.APPLICATION_JSON));

        connector.pull(ctx(URL), FROM, TO);

        server.verify();
    }

    @Test
    void anExistingQueryStringOnTheUrlIsKept() {
        server.expect(once(), requestTo(URL + "?app=main&from=2026-10-01&to=2026-10-14"))
                .andRespond(withSuccess(response("1", METRICS, "[]", "[]"), MediaType.APPLICATION_JSON));

        connector.pull(ctx(URL + "?app=main"), FROM, TO);

        server.verify();
    }

    @Test
    void aRejectedKeyIsReportedInPlainWordsWithoutTheKey() {
        server.expect(once(), requestTo(URL + "?from=2026-10-01&to=2026-10-14")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class, e -> {
                    assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
                    assertThat(e.getMessage()).startsWith("The server rejected the API key (HTTP 401).")
                            .doesNotContain("secret-key");
                });
    }

    @Test
    void aMissingEndpointIsReported() {
        server.expect(once(), requestTo(URL + "?from=2026-10-01&to=2026-10-14")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .hasMessage("The server has no metrics endpoint at that URL (HTTP 404). Check the URL.");
    }

    @Test
    void aServerErrorIsTransientAndShowsTheProblemDetail() {
        server.expect(once(), requestTo(URL + "?from=2026-10-01&to=2026-10-14"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                        .body("{\"title\":\"Boom\",\"detail\":\"warehouse is down\"}"));

        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class, e -> {
                    assertThat(e.health()).isEqualTo(ConnectorHealth.DEGRADED);
                    assertThat(e.getMessage()).isEqualTo("The metrics server answered HTTP 500: warehouse is down");
                });
    }

    @Test
    void aRedirectIsNotFollowed() {
        server.expect(once(), requestTo(URL + "?from=2026-10-01&to=2026-10-14"))
                .andRespond(withStatus(HttpStatus.FOUND).header("Location", "https://elsewhere.example.test/"));

        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .hasMessageContaining("redirected the request");
    }

    @Test
    void aBodyThatIsNotJsonIsReported() {
        respond("<html>nope</html>");

        assertThatThrownBy(() -> connector.pull(ctx(URL), FROM, TO))
                .hasMessage("The server did not return valid JSON.");
    }

    // ---- guarded URLs -----------------------------------------------------------------------------

    @Test
    void aPlainHttpUrlIsRefusedBeforeAnyRequestIsMade() {
        ProductMetricsConnector strict = strictConnector();
        server.expect(never(), requestTo("http://metrics.example.test/v1/metrics"));

        assertThatThrownBy(() -> strict.pull(ctx("http://metrics.example.test/v1/metrics"), FROM, TO))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class, e -> {
                    assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
                    assertThat(e.getMessage()).isEqualTo("The server URL must start with https://.");
                });
        server.verify();
    }

    @Test
    void theCloudMetadataAddressAndOtherPrivateAddressesAreRefused() {
        ProductMetricsConnector strict = strictConnector();

        for (String url : new String[]{"https://169.254.169.254/latest/meta-data", "https://127.0.0.1/x",
                "https://10.0.0.5/x", "https://[::1]/x"}) {
            assertThatThrownBy(() -> strict.pull(ctx(url), FROM, TO)).as(url)
                    .isInstanceOf(ChannelMetricsSourceException.class)
                    .hasMessage("The server URL points at a private or internal address, which is not allowed.");
        }
    }

    @Test
    void aMissingUrlOrKeyNeedsSetup() {
        assertThatThrownBy(() -> connector.pull(new ConnectionContext("p", "product-metrics", "c", "k", null, null,
                Map.of(), null), FROM, TO)).hasMessage("No metrics URL is configured for this connection.");
        assertThatThrownBy(() -> connector.pull(new ConnectionContext("p", "product-metrics", "c", null, null, null,
                Map.of("baseUrl", URL), null), FROM, TO)).hasMessage("No API key is stored for this connection.");
    }

    private ProductMetricsConnector strictConnector() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        return new ProductMetricsConnector(new ProductMetricsClient(mapper, new OutboundUrlGuard(false), restTemplate));
    }

    // ---- health and creation ----------------------------------------------------------------------

    @Test
    void healthIsAOneDayPullThatValidatesTheContract() {
        server.expect(once(), r -> assertThat(r.getURI().getQuery()).matches("from=\\d{4}-\\d{2}-\\d{2}&to=\\d{4}-\\d{2}-\\d{2}"))
                .andRespond(withSuccess(response("1", METRICS, "[]", "[]"), MediaType.APPLICATION_JSON));

        assertThat(connector.checkHealth(ctx(URL))).isEqualTo(ConnectorHealth.HEALTHY);
    }

    @Test
    void healthIsSetupRequiredForAWrongVersionAndDegradedForAServerError() {
        server.expect(once(), r -> { }).andRespond(withSuccess(response("4", METRICS, "[]", "[]"), MediaType.APPLICATION_JSON));
        assertThat(connector.checkHealth(ctx(URL))).isEqualTo(ConnectorHealth.SETUP_REQUIRED);

        server.reset();
        server.expect(once(), r -> { }).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThat(connector.checkHealth(ctx(URL))).isEqualTo(ConnectorHealth.DEGRADED);
    }

    @Test
    void aConnectionThatCannotWorkIsRefusedWithTheReason() {
        server.expect(once(), r -> { }).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThat(connector.verifyCredentials(ctx(URL))).hasValueSatisfying(m ->
                assertThat(m).startsWith("The server rejected the API key (HTTP 403)."));
    }

    @Test
    void aConnectionIsIdentifiedByItsNormalizedUrl() {
        assertThat(connector.identityKey(ctx("https://API.example.com/v1/metrics/")))
                .isEqualTo(connector.identityKey(ctx("https://api.example.com/v1/metrics")));
        assertThat(connector.identityKey(ctx("https://api.example.com/v1/metrics")))
                .isNotEqualTo(connector.identityKey(ctx("https://api.example.com/v1/other")));
    }

    @Test
    void aWorkingConnectionIsAccepted() {
        server.expect(once(), r -> { }).andRespond(withSuccess(response("1", METRICS, "[]", "[]"), MediaType.APPLICATION_JSON));

        assertThat(connector.verifyCredentials(ctx(URL))).isEmpty();

        server.verify();
    }
}
