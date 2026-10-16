package com.conductor.integration.connector.appstoreconnect;

import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.MetricChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AppStoreConnectChannelMetricsTest {

    private static final String API = "https://api.appstoreconnect.apple.com";
    private static final String REQUESTS = API + "/v1/apps/12345/analyticsReportRequests?filter%5BaccessType%5D=ONGOING";
    private static final String DISCOVERY_LOOKUP = API + "/v1/analyticsReportRequests/REQ1/reports?filter%5Bname%5D="
            + "App%20Store%20Discovery%20and%20Engagement%20Standard";
    private static final String DOWNLOADS_LOOKUP = API + "/v1/analyticsReportRequests/REQ1/reports?filter%5Bname%5D="
            + "App%20Downloads%20Standard";
    private static final String DISCOVERY_INSTANCES = API
            + "/v1/analyticsReports/R1/instances?filter%5Bgranularity%5D=DAILY&limit=200";
    private static final String DOWNLOADS_INSTANCES = API
            + "/v1/analyticsReports/R2/instances?filter%5Bgranularity%5D=DAILY&limit=200";

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 2);

    private MockRestServiceServer server;
    private AppStoreConnectConnector connector;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        AppStoreConnectTokenService tokens = new AppStoreConnectTokenService() {
            @Override public String token(String connectionId, Credentials creds) { return "jwt-token"; }
        };
        connector = new AppStoreConnectConnector(restTemplate, tokens);
    }

    private static ConnectionContext ctx(String ratingsCountry) {
        Map<String, Object> config = new HashMap<>();
        config.put("issuerId", "ISSUER");
        config.put("keyId", "KEY123");
        config.put("appId", "12345");
        if (ratingsCountry != null) config.put("ratingsCountry", ratingsCountry);
        return new ConnectionContext("proj", "app-store-connect", "conn", "-----BEGIN PRIVATE KEY-----\nfake\n-----END PRIVATE KEY-----",
                null, null, config, null);
    }

    // -- helpers --------------------------------------------------------------------------------------

    private void expectJson(String url, String body) {
        server.expect(once(), requestTo(url)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer jwt-token"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void expectSegment(String url, byte[] body) {
        server.expect(once(), requestTo(url)).andExpect(method(HttpMethod.GET))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_OCTET_STREAM));
    }

    private void expectSegmentList(String instanceId, String... urls) {
        String data = java.util.Arrays.stream(urls)
                .map(u -> "{\"id\":\"s\",\"attributes\":{\"url\":\"" + u + "\"}}")
                .collect(Collectors.joining(","));
        expectJson(API + "/v1/analyticsReportInstances/" + instanceId + "/segments", "{\"data\":[" + data + "]}");
    }

    private void expectRequestAndReports() {
        expectJson(REQUESTS, "{\"data\":[{\"id\":\"REQ1\"}]}");
        expectJson(DISCOVERY_LOOKUP, "{\"data\":[{\"id\":\"R1\"}]}");
        expectJson(DOWNLOADS_LOOKUP, "{\"data\":[{\"id\":\"R2\"}]}");
    }

    private static byte[] gzip(String text) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] zip(String text) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("report.tsv"));
                zip.write(text.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Double> byKey(List<ChannelMetricPoint> points) {
        Map<String, Double> m = new HashMap<>();
        for (ChannelMetricPoint p : points) {
            m.put(p.metric() + "|" + p.dimensionKey() + "|" + p.day(), p.value().doubleValue());
        }
        assertThat(m).hasSameSizeAs(points); // no duplicate keys
        return m;
    }

    // -- source declaration -----------------------------------------------------------------------------

    @Test
    void declaresAStoreSourceWithTheDocumentedWindowsAndSnapshotRatings() {
        assertThat(connector.channel()).isEqualTo(MetricChannel.STORE);
        assertThat(connector.backfillDays()).isEqualTo(90);
        assertThat(connector.trailingDays()).isEqualTo(3);
        assertThat(connector.maxDaysPerPull()).isEqualTo(14);
        assertThat(connector.snapshot("rating_count")).isTrue();
        assertThat(connector.snapshot("rating_avg")).isTrue();
        assertThat(connector.snapshot("downloads")).isFalse();
        assertThat(connector.getSpec().singleInstance()).isFalse();
        assertThat(connector.getSpec().fields()).extracting(f -> f.key())
                .containsExactly("issuerId", "keyId", "appId", "ratingsCountry", "apiKey");
    }

    // -- the pull ---------------------------------------------------------------------------------------

    @Test
    void pullsBothReportsParsesByHeaderSumsSegmentsAndLetsTheLatestInstanceWin() {
        expectRequestAndReports();

        // Discovery: two pages of DAILY instances. A is old and superseded by B for 2026-10-02; E predates the
        // window (its segments must not even be requested); C only carries a day after the window.
        expectJson(DISCOVERY_INSTANCES, """
                {"data":[{"id":"A","attributes":{"processingDate":"2026-10-03"}},
                         {"id":"E","attributes":{"processingDate":"2026-09-20"}}],
                 "links":{"next":"%s&cursor=page2"}}""".formatted(DISCOVERY_INSTANCES));
        expectJson(DISCOVERY_INSTANCES + "&cursor=page2", """
                {"data":[{"id":"B","attributes":{"processingDate":"2026-10-05"}},
                         {"id":"C","attributes":{"processingDate":"2026-10-04"}}]}""");

        expectSegmentList("A", "https://segments.example.com/a1?sig=x%2By");
        expectSegment("https://segments.example.com/a1?sig=x%2By", gzip(
                "Counts\tDate\tEvent\tUnique Counts\tSource Type\tPage Type\n"
                        + "100\t2026-10-02\tImpression\t80\tApp Store Search\tProduct page\n"
                        + "10\t2026-10-02\tPage View\t9\tApp Store Search\tProduct page\n"));
        expectSegmentList("C", "https://segments.example.com/c1");
        expectSegment("https://segments.example.com/c1", gzip(
                "Date\tEvent\tSource Type\tCounts\tUnique Counts\n2026-10-03\tImpression\tApp Store Search\t999\t999\n"));
        // B has two segments (a zip and a plain body) whose rows add up.
        expectSegmentList("B", "https://segments.example.com/b1", "https://segments.example.com/b2");
        expectSegment("https://segments.example.com/b1", zip(
                "Date\tEvent\tSource Type\tCounts\tUnique Counts\n"
                        + "2026-10-02\tImpression\tApp Store Search\t30\t25\n"
                        + "2026-10-02\tImpression\tApp Store Browse\t20\t15\n"
                        + "2026-10-02\tPage View\tApp Store Search\t5\t4\n"));
        expectSegment("https://segments.example.com/b2",
                ("Date\tEvent\tSource Type\tCounts\tUnique Counts\n"
                        + "2026-10-02\tImpression\tApp Store Search\t10\t5\n"
                        + "2026-10-02\tImpression\tWeb Referrer\t7\t6\n"
                        + "2026-10-02\tImpression\tSomething New\t1\t1\n"
                        + "2026-10-02\tTap\tApp Store Search\t50\t50\n").getBytes(StandardCharsets.UTF_8));

        // Downloads: one instance, columns in a different order, one day before the window.
        expectJson(DOWNLOADS_INSTANCES, "{\"data\":[{\"id\":\"D\",\"attributes\":{\"processingDate\":\"2026-10-04\"}}]}");
        expectSegmentList("D", "https://segments.example.com/d1");
        expectSegment("https://segments.example.com/d1", gzip(
                "Download Type\tSource Type\tPage Type\tCounts\tDate\n"
                        + "First-time download\tApp Store Search\tProduct page\t3\t2026-10-02\n"
                        + "Redownload\tApp Store Search\tProduct page\t2\t2026-10-02\n"
                        + "First-time download\tApp Store Browse\tProduct page\t1\t2026-10-02\n"
                        + "Manual update\tApp Store Search\tProduct page\t40\t2026-10-02\n"
                        + "First-time download\tApp Store Search\tProduct page\t9\t2026-10-01\n"
                        + "First-time download\tApp Store Search\tProduct page\t77\t2026-09-30\n"));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(null), FROM, TO);

        server.verify();
        Map<String, Double> m = byKey(points);
        // 2026-10-02 impressions come only from B (A is superseded), both segments summed.
        assertThat(m).containsEntry("impressions||2026-10-02", 68.0)
                .containsEntry("impressions|source:APP_STORE_SEARCH|2026-10-02", 40.0)
                .containsEntry("impressions|source:APP_STORE_BROWSE|2026-10-02", 20.0)
                .containsEntry("impressions|source:WEB_REFERRER|2026-10-02", 7.0)
                .containsEntry("impressions|source:OTHER|2026-10-02", 1.0)
                .containsEntry("impressions_unique||2026-10-02", 52.0)
                .containsEntry("page_views||2026-10-02", 5.0)
                .containsEntry("page_views_unique||2026-10-02", 4.0)
                .containsEntry("downloads||2026-10-02", 4.0)
                .containsEntry("downloads|source:APP_STORE_SEARCH|2026-10-02", 3.0)
                .containsEntry("redownloads||2026-10-02", 2.0)
                .containsEntry("downloads||2026-10-01", 9.0);
        // Days outside the window (after: C's 10-03; before: 09-30) and unpublished days produce nothing.
        assertThat(points).extracting(ChannelMetricPoint::day)
                .allMatch(d -> !d.isBefore(FROM) && !d.isAfter(TO));
        assertThat(m).doesNotContainKey("impressions||2026-10-01").doesNotContainKey("page_views||2026-10-01");
        assertThat(points).extracting(ChannelMetricPoint::metric).doesNotContain("rating_count", "rating_avg");
    }

    @Test
    void createsTheOngoingRequestWhenNoneExistsAndReusesTheExistingOneOn409() {
        expectJson(REQUESTS, "{\"data\":[]}");
        server.expect(once(), requestTo(API + "/v1/analyticsReportRequests")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer jwt-token"))
                .andExpect(jsonPath("$.data.type").value("analyticsReportRequests"))
                .andExpect(jsonPath("$.data.attributes.accessType").value("ONGOING"))
                .andExpect(jsonPath("$.data.relationships.app.data.id").value("12345"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"errors\":[{\"status\":\"409\"}]}"));
        // the re-list after the 409 now finds it
        expectJson(REQUESTS, "{\"data\":[{\"id\":\"REQ1\"}]}");
        expectJson(DISCOVERY_LOOKUP, "{\"data\":[]}");
        expectJson(DOWNLOADS_LOOKUP, "{\"data\":[]}");

        assertThat(connector.pullDaily(ctx(null), FROM, TO)).isEmpty();

        server.verify();
    }

    @Test
    void aSegmentMissingARequiredColumnFailsTheWindowWithAClearMessage() {
        expectRequestAndReports();
        expectJson(DISCOVERY_INSTANCES, "{\"data\":[{\"id\":\"A\",\"attributes\":{\"processingDate\":\"2026-10-03\"}}]}");
        expectSegmentList("A", "https://segments.example.com/a1");
        expectSegment("https://segments.example.com/a1", gzip(
                "Date\tEvent\tSource Type\tCounts\n2026-10-02\tImpression\tApp Store Search\t5\n"));
        expectJson(DOWNLOADS_INSTANCES, "{\"data\":[]}");

        assertThatThrownBy(() -> connector.pullDaily(ctx(null), FROM, TO))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class, e -> {
                    assertThat(e.health()).isEqualTo(ConnectorHealth.DEGRADED);
                    assertThat(e.getMessage()).contains("App Store Discovery and Engagement Standard")
                            .contains("Unique Counts");
                });
    }

    @Test
    void anAuthRefusalNeedsSetupAndRateLimitingIsDegraded() {
        server.expect(once(), requestTo(REQUESTS)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> connector.pullDaily(ctx(null), FROM, TO))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class, e -> {
                    assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
                    assertThat(e.getMessage()).contains("role");
                });

        server.reset();
        server.expect(once(), requestTo(REQUESTS)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> connector.pullDaily(ctx(null), FROM, TO))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class,
                        e -> assertThat(e.health()).isEqualTo(ConnectorHealth.DEGRADED));
    }

    @Test
    void missingConfigurationNeedsSetup() {
        ConnectionContext none = new ConnectionContext("proj", "app-store-connect", "conn", null, null, null, Map.of(), null);
        assertThatThrownBy(() -> connector.pullDaily(none, FROM, TO))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class,
                        e -> assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED));
    }

    // -- ratings ----------------------------------------------------------------------------------------

    @Test
    void addsTodaysRatingSnapshotFromThePublicLookupWhenTheWindowReachesToday() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        expectRequestAndReports();
        expectJson(DISCOVERY_INSTANCES, "{\"data\":[]}");
        expectJson(DOWNLOADS_INSTANCES, "{\"data\":[]}");
        server.expect(once(), requestTo("https://itunes.apple.com/lookup?id=12345&country=gb"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"resultCount\":1,\"results\":[{\"userRatingCount\":321,\"averageUserRating\":4.65}]}",
                        MediaType.parseMediaType("text/javascript; charset=utf-8")));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx("GB"), today.minusDays(2), today);

        server.verify();
        assertThat(byKey(points)).containsEntry("rating_count||" + today, 321.0)
                .containsEntry("rating_avg||" + today, 4.65).hasSize(2);
    }

    @Test
    void aFailedRatingLookupNeverFailsTheStorePull() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        expectRequestAndReports();
        expectJson(DISCOVERY_INSTANCES, "{\"data\":[{\"id\":\"A\",\"attributes\":{\"processingDate\":\"" + today + "\"}}]}");
        expectSegmentList("A", "https://segments.example.com/a1");
        expectSegment("https://segments.example.com/a1", gzip(
                "Date\tEvent\tSource Type\tCounts\tUnique Counts\n" + today.minusDays(1) + "\tImpression\tApp Store Search\t5\t4\n"));
        expectJson(DOWNLOADS_INSTANCES, "{\"data\":[]}");
        server.expect(once(), requestTo("https://itunes.apple.com/lookup?id=12345&country=us"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(null), today.minusDays(2), today);

        assertThat(byKey(points)).containsEntry("impressions||" + today.minusDays(1), 5.0);
        assertThat(points).extracting(ChannelMetricPoint::metric).doesNotContain("rating_count", "rating_avg");
    }

    // -- health -----------------------------------------------------------------------------------------

    @Test
    void healthIsHealthyWhenTheAppIsReadableAndARequestExists() {
        expectJson(API + "/v1/apps/12345", "{\"data\":{\"id\":\"12345\"}}");
        expectJson(REQUESTS, "{\"data\":[{\"id\":\"REQ1\"}]}");

        assertThat(connector.checkHealth(ctx(null))).isEqualTo(ConnectorHealth.HEALTHY);
    }

    @Test
    void healthNeedsSetupForAnUnknownAppAndWhenTheRequestCannotBeCreated() {
        server.expect(once(), requestTo(API + "/v1/apps/12345")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(connector.checkHealth(ctx(null))).isEqualTo(ConnectorHealth.SETUP_REQUIRED);

        server.reset();
        expectJson(API + "/v1/apps/12345", "{\"data\":{\"id\":\"12345\"}}");
        expectJson(REQUESTS, "{\"data\":[]}");
        server.expect(once(), requestTo(API + "/v1/analyticsReportRequests")).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThat(connector.checkHealth(ctx(null))).isEqualTo(ConnectorHealth.SETUP_REQUIRED);

        assertThat(connector.checkHealth(new ConnectionContext("p", "app-store-connect", "c", null, null, null, Map.of(), null)))
                .isEqualTo(ConnectorHealth.SETUP_REQUIRED);
    }
}
