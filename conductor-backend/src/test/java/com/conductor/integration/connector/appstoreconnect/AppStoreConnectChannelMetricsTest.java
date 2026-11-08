package com.conductor.integration.connector.appstoreconnect;

import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsPull;
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
import java.time.Clock;
import java.time.Instant;
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
    private static final String DETAILED_LOOKUP = API + "/v1/analyticsReportRequests/REQ1/reports?filter%5Bname%5D="
            + "App%20Downloads%20Detailed";
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
        return ctx(ratingsCountry, null);
    }

    private static ConnectionContext ctx(String ratingsCountry, String vendorNumber) {
        Map<String, Object> config = new HashMap<>();
        config.put("issuerId", "ISSUER");
        config.put("keyId", "KEY123");
        config.put("appId", "12345");
        if (ratingsCountry != null) config.put("ratingsCountry", ratingsCountry);
        if (vendorNumber != null) config.put("vendorNumber", vendorNumber);
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

    private void expectApp() {
        expectJson(API + "/v1/apps/12345", "{\"data\":{\"id\":\"12345\"}}");
    }

    private void expectRequestAndReports() {
        expectApp();
        expectJson(REQUESTS, "{\"data\":[{\"id\":\"REQ1\"}]}");
        expectJson(DISCOVERY_LOOKUP, "{\"data\":[{\"id\":\"R1\"}]}");
        expectJson(DOWNLOADS_LOOKUP, "{\"data\":[{\"id\":\"R2\"}]}");
        // the optional Detailed report (campaign downloads, see AppStoreCampaignDownloadsTest) is not listed here
        expectJson(DETAILED_LOOKUP, "{\"data\":[]}");
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
                .containsExactly("issuerId", "keyId", "appId", "vendorNumber", "providerToken", "ratingsCountry", "apiKey");
        assertThat(connector.getSpec().fields()).filteredOn(f -> f.key().equals("vendorNumber"))
                .singleElement().satisfies(f -> {
                    assertThat(f.required()).isFalse();
                    assertThat(f.secret()).isFalse();
                    assertThat(f.hint()).contains("Payments and Financial Reports");
                });
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
        expectApp();
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
        expectJson(DETAILED_LOOKUP, "{\"data\":[]}");

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
        expectApp();
        server.expect(once(), requestTo(REQUESTS)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> connector.pullDaily(ctx(null), FROM, TO))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class, e -> {
                    assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
                    assertThat(e.getMessage()).contains("role");
                });

        server.reset();
        expectApp();
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

    @Test
    void anAppTheKeyCannotSeeIsNamedAsSuchNotAsARoleProblem() {
        server.expect(once(), requestTo(API + "/v1/apps/12345")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> connector.pullDaily(ctx(null), FROM, TO))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class, e -> {
                    assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
                    assertThat(e.getMessage()).isEqualTo(
                            "No app with Apple ID 12345 is visible to this API key — check the App Apple ID");
                });
        server.verify();
    }

    @Test
    void aNonNumericAppIdNeedsSetupWithoutCallingApple() {
        ConnectionContext bad = ctxWithAppId("abc");
        assertThatThrownBy(() -> connector.pullDaily(bad, FROM, TO))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class, e -> {
                    assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
                    assertThat(e.getMessage()).contains("numbers only");
                });
        assertThat(connector.checkHealth(bad)).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        server.verify(); // no request was made
    }

    private static ConnectionContext ctxWithAppId(String appId) {
        return new ConnectionContext("proj", "app-store-connect", "conn",
                "-----BEGIN PRIVATE KEY-----\nfake\n-----END PRIVATE KEY-----",
                null, null, Map.of("issuerId", "ISSUER", "keyId", "KEY123", "appId", appId), null);
    }

    // -- zero days --------------------------------------------------------------------------------------

    @Test
    void aProcessedDayWithNoRowsForAMetricIsStoredAsZeroButAnUnpublishedDayIsLeftAlone() {
        expectRequestAndReports();
        // Oct 2 (processed Oct 3) has impressions only; Oct 1 (processed Oct 2) has no rows at all. Nothing is
        // processed after Oct 3, so Oct 3 and later are unpublished and must stay untouched.
        expectJson(DISCOVERY_INSTANCES, """
                {"data":[{"id":"A","attributes":{"processingDate":"2026-10-03"}},
                         {"id":"B","attributes":{"processingDate":"2026-10-02"}}]}""");
        expectSegmentList("A", "https://segments.example.com/a1");
        expectSegment("https://segments.example.com/a1", gzip(
                "Date\tEvent\tSource Type\tCounts\tUnique Counts\n2026-10-02\tImpression\tApp Store Search\t5\t4\n"));
        expectJson(API + "/v1/analyticsReportInstances/B/segments", "{\"data\":[]}");
        expectJson(DOWNLOADS_INSTANCES, "{\"data\":[{\"id\":\"D\",\"attributes\":{\"processingDate\":\"2026-10-03\"}}]}");
        expectSegmentList("D", "https://segments.example.com/d1");
        expectSegment("https://segments.example.com/d1", gzip(
                "Date\tDownload Type\tSource Type\tCounts\n2026-10-02\tFirst-time download\tApp Store Search\t3\n"));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(null), FROM, TO.plusDays(2));

        server.verify();
        Map<String, Double> m = byKey(points);
        // Oct 2: impressions present, the rest of Discovery is an explicit zero; downloads present, redownloads zero.
        assertThat(m).containsEntry("impressions||2026-10-02", 5.0)
                .containsEntry("page_views||2026-10-02", 0.0)
                .containsEntry("page_views_unique||2026-10-02", 0.0)
                .containsEntry("downloads||2026-10-02", 3.0)
                .containsEntry("redownloads||2026-10-02", 0.0);
        // Both day totals exist, so the total exists; the search source's missing redownloads row is a covered
        // zero too, so it has a total of its own.
        assertThat(m).containsEntry("total_downloads||2026-10-02", 3.0)
                .containsEntry("redownloads|source:APP_STORE_SEARCH|2026-10-02", 0.0)
                .containsEntry("total_downloads|source:APP_STORE_SEARCH|2026-10-02", 3.0);
        // Oct 1: a processed Discovery day with no rows -> zeros for all four Discovery metrics (and nothing else).
        assertThat(m).containsEntry("impressions||2026-10-01", 0.0)
                .containsEntry("impressions_unique||2026-10-01", 0.0)
                .containsEntry("page_views||2026-10-01", 0.0)
                .containsEntry("page_views_unique||2026-10-01", 0.0)
                .containsEntry("impressions|source:APP_STORE_SEARCH|2026-10-01", 0.0)
                .doesNotContainKey("downloads||2026-10-01")
                .doesNotContainKey("downloads|source:APP_STORE_SEARCH|2026-10-01");
        // Nothing for a day without a processed instance.
        assertThat(points).extracting(ChannelMetricPoint::day)
                .noneMatch(d -> d.isAfter(LocalDate.of(2026, 10, 2)));
    }

    @Test
    void aConnectionIsIdentifiedByItsAppId() {
        assertThat(connector.identityKey(ctxWithAppId(" 12345 "))).hasValue("12345");
        assertThat(connector.identityKey(ctxWithAppId("67890"))).hasValue("67890");
        assertThat(connector.identityKey(new ConnectionContext("p", "app-store-connect", "c", null, null, null,
                Map.of(), null))).isEmpty();
    }

    // -- credential check -------------------------------------------------------------------------------

    @Test
    void verifyAcceptsWorkingCredentialsAfterCheckingTheAppAndTheOngoingRequest() {
        expectApp();
        expectJson(REQUESTS, "{\"data\":[{\"id\":\"REQ1\"}]}");

        assertThat(connector.verifyCredentials(ctx(null))).isEmpty();
        server.verify();
    }

    @Test
    void verifyRefusesBadInputWithPlainMessages() {
        assertThat(connector.verifyCredentials(ctxWithAppId("abc")))
                .hasValueSatisfying(m -> assertThat(m).contains("numbers only"));
        assertThat(connector.verifyCredentials(new ConnectionContext("p", "app-store-connect", "c", null, null, null,
                Map.of(), null))).hasValueSatisfying(m -> assertThat(m).contains("Issuer ID"));

        AppStoreConnectTokenService garbage = new AppStoreConnectTokenService() {
            @Override public String token(String connectionId, Credentials creds) {
                throw new IllegalArgumentException("bad pem");
            }
        };
        AppStoreConnectConnector withGarbageKey = new AppStoreConnectConnector(new RestTemplate(), garbage);
        assertThat(withGarbageKey.verifyCredentials(ctx(null))).hasValueSatisfying(m -> assertThat(m).contains(".p8"));
    }

    @Test
    void verifyMapsAppleStatusesToThePlainMessagesAFeedPullUses() {
        // wrong Issuer/Key id -> 401
        server.expect(once(), requestTo(API + "/v1/apps/12345")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThat(connector.verifyCredentials(ctx(null)))
                .hasValueSatisfying(m -> assertThat(m).contains("rejected the API key"));

        // wrong app id -> 404 on the app
        server.reset();
        server.expect(once(), requestTo(API + "/v1/apps/12345")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThat(connector.verifyCredentials(ctx(null))).hasValue(
                "No app with Apple ID 12345 is visible to this API key — check the App Apple ID");

        // narrow role: app readable, analytics 403
        server.reset();
        expectApp();
        server.expect(once(), requestTo(REQUESTS)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThat(connector.verifyCredentials(ctx(null))).hasValueSatisfying(m -> assertThat(m).contains("role"));
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

    // -- total downloads ----------------------------------------------------------------------------------

    @Test
    void totalDownloadsIsFirstTimePlusRedownloadsPerSourceOnDaysTheDownloadsReportCovers() {
        expectRequestAndReports();
        expectJson(DISCOVERY_INSTANCES, "{\"data\":[]}");
        expectJson(DOWNLOADS_INSTANCES, "{\"data\":[{\"id\":\"D\",\"attributes\":{\"processingDate\":\"2026-10-03\"}}]}");
        expectSegmentList("D", "https://segments.example.com/d1");
        expectSegment("https://segments.example.com/d1", gzip(
                "Date\tDownload Type\tSource Type\tCounts\n"
                        + "2026-10-02\tFirst-time download\tApp Store Search\t29\n"
                        + "2026-10-02\tRedownload\tApp Store Search\t4\n"
                        + "2026-10-02\tFirst-time download\tApp Store Browse\t1\n"
                        + "2026-10-02\tRedownload\tApp Referrer\t2\n"
                        // 2026-10-01 has no instance of its own: it arrives inside D, as a request's history does
                        + "2026-10-01\tFirst-time download\tApp Store Search\t9\n"));

        Map<String, Double> m = byKey(connector.pullDaily(ctx(null), FROM, TO));

        server.verify();
        assertThat(m).containsEntry("downloads||2026-10-02", 30.0)
                .containsEntry("redownloads||2026-10-02", 6.0)
                .containsEntry("total_downloads||2026-10-02", 36.0)
                .containsEntry("total_downloads|source:APP_STORE_SEARCH|2026-10-02", 33.0);
        // on a covered day a source's missing row is a real 0 (Apple omits zero rows), so it still has a total
        assertThat(m).containsEntry("total_downloads|source:APP_STORE_BROWSE|2026-10-02", 1.0)
                .containsEntry("total_downloads|source:APP_REFERRER|2026-10-02", 2.0)
                .containsEntry("total_downloads|source:WEB_REFERRER|2026-10-02", 0.0);
        // a day carried inside another day's instance is covered too: its missing rows are zeros, not gaps
        assertThat(m).containsEntry("downloads||2026-10-01", 9.0)
                .containsEntry("redownloads||2026-10-01", 0.0)
                .containsEntry("total_downloads||2026-10-01", 9.0)
                .containsEntry("total_downloads|source:APP_STORE_SEARCH|2026-10-01", 9.0)
                .containsEntry("downloads|source:APP_STORE_BROWSE|2026-10-01", 0.0);
    }

    @Test
    void aRequestsHistoryArrivingInOneInstanceIsCoveredDayByDaySoPerSourceSumsKeepEveryDay() {
        expectRequestAndReports();
        // A new request's first instance ships the history before it; those days have no instance of their own.
        // Sep 30 had search impressions but no search downloads; Oct 1 a search download but no redownload.
        expectJson(DISCOVERY_INSTANCES, "{\"data\":[{\"id\":\"A\",\"attributes\":{\"processingDate\":\"2026-10-03\"}}]}");
        expectSegmentList("A", "https://segments.example.com/a1");
        expectSegment("https://segments.example.com/a1", gzip(
                "Date\tEvent\tSource Type\tCounts\tUnique Counts\n"
                        + "2026-09-30\tImpression\tApp Store Search\t300\t250\n"
                        + "2026-10-01\tImpression\tApp Store Search\t280\t240\n"
                        + "2026-10-02\tImpression\tApp Store Search\t310\t260\n"));
        expectJson(DOWNLOADS_INSTANCES, "{\"data\":[{\"id\":\"D\",\"attributes\":{\"processingDate\":\"2026-10-03\"}}]}");
        expectSegmentList("D", "https://segments.example.com/d1");
        expectSegment("https://segments.example.com/d1", gzip(
                "Date\tDownload Type\tSource Type\tCounts\n"
                        + "2026-09-30\tRedownload\tApp Referrer\t1\n"
                        + "2026-10-01\tFirst-time download\tApp Store Search\t2\n"
                        + "2026-10-02\tFirst-time download\tApp Store Search\t1\n"
                        + "2026-10-02\tRedownload\tApp Store Search\t1\n"));

        Map<String, Double> m = byKey(connector.pullDaily(ctx(null), LocalDate.of(2026, 9, 29), TO));

        server.verify();
        assertThat(m).containsEntry("downloads|source:APP_STORE_SEARCH|2026-09-30", 0.0)
                .containsEntry("total_downloads|source:APP_STORE_SEARCH|2026-09-30", 0.0)
                .containsEntry("total_downloads|source:APP_STORE_SEARCH|2026-10-01", 2.0)
                .containsEntry("total_downloads|source:APP_STORE_SEARCH|2026-10-02", 2.0)
                .containsEntry("page_views|source:APP_STORE_SEARCH|2026-09-30", 0.0)
                .containsEntry("impressions_unique|source:APP_STORE_SEARCH|2026-10-01", 240.0);
        // a day neither report carries is still left alone
        assertThat(m.keySet()).noneMatch(k -> k.endsWith("|2026-09-29"));
    }

    @Test
    void aSourceTheOverviewDoesNotReadOneByOneIsLeftAsAppleSentIt() {
        expectRequestAndReports();
        expectJson(DISCOVERY_INSTANCES, "{\"data\":[]}");
        expectJson(DOWNLOADS_INSTANCES, "{\"data\":[{\"id\":\"D\",\"attributes\":{\"processingDate\":\"2026-10-03\"}}]}");
        expectSegmentList("D", "https://segments.example.com/d1");
        expectSegment("https://segments.example.com/d1", gzip(
                "Date\tDownload Type\tSource Type\tCounts\n2026-10-02\tFirst-time download\tApp Clip\t2\n"));

        Map<String, Double> m = byKey(connector.pullDaily(ctx(null), FROM, TO));

        assertThat(m).containsEntry("downloads|source:APP_CLIP|2026-10-02", 2.0)
                .doesNotContainKey("redownloads|source:APP_CLIP|2026-10-02")
                .doesNotContainKey("downloads|source:INSTITUTIONAL_PURCHASE|2026-10-02")
                .containsEntry("downloads|source:APP_STORE_SEARCH|2026-10-02", 0.0);
    }

    @Test
    void aDayWithNoDownloadsInputsWritesNoTotalDownloads() {
        expectRequestAndReports();
        expectJson(DISCOVERY_INSTANCES, "{\"data\":[{\"id\":\"A\",\"attributes\":{\"processingDate\":\"2026-10-03\"}}]}");
        expectSegmentList("A", "https://segments.example.com/a1");
        expectSegment("https://segments.example.com/a1", gzip(
                "Date\tEvent\tSource Type\tCounts\tUnique Counts\n2026-10-02\tImpression\tApp Store Search\t5\t4\n"));
        expectJson(DOWNLOADS_INSTANCES, "{\"data\":[]}");

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(null), FROM, TO);

        assertThat(points).extracting(ChannelMetricPoint::metric).doesNotContain("total_downloads");
    }

    // -- contract version ---------------------------------------------------------------------------------

    @Test
    void thePullReportsContractVersion5SoHistoryIsReWalkedOnce() {
        expectRequestAndReports();
        expectJson(DISCOVERY_INSTANCES, "{\"data\":[]}");
        expectJson(DOWNLOADS_INSTANCES, "{\"data\":[{\"id\":\"D\",\"attributes\":{\"processingDate\":\"2026-10-03\"}}]}");
        expectSegmentList("D", "https://segments.example.com/d1");
        expectSegment("https://segments.example.com/d1", gzip(
                "Date\tDownload Type\tSource Type\tCounts\n2026-10-02\tFirst-time download\tApp Store Search\t3\n"));

        ChannelMetricsPull pull = connector.pull(ctx(null), FROM, TO);

        assertThat(pull.contractVersion()).isEqualTo(5);
        assertThat(byKey(pull.points())).containsEntry("downloads||2026-10-02", 3.0);
    }

    // -- sales reports: downloads of record ---------------------------------------------------------------

    private static final String SALES_BASE = API + "/v1/salesReports?filter%5Bfrequency%5D=DAILY"
            + "&filter%5BreportType%5D=SALES&filter%5BreportSubType%5D=SUMMARY&filter%5BvendorNumber%5D=94514742"
            + "&filter%5BreportDate%5D=";
    private static final String SALES_VERSION = "&filter%5Bversion%5D=1_1";
    private static final String SALES_HEADER = "Provider\tSKU\tVersion\tProduct Type Identifier\tUnits\tCountry Code\t"
            + "Apple Identifier\n";

    private static String salesUrl(LocalDate day) {
        return SALES_BASE + day + SALES_VERSION;
    }

    private static String salesRow(String appleId, String productType, int units) {
        return "Apple\tSKU1\t1.160\t" + productType + "\t" + units + "\tUS\t" + appleId + "\n";
    }

    private void expectSalesReport(LocalDate day, String body) {
        server.expect(once(), requestTo(salesUrl(day))).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer jwt-token"))
                .andRespond(withSuccess(gzip(body), MediaType.parseMediaType("application/a-gzip")));
    }

    private void expectEmptyAnalytics() {
        expectRequestAndReports();
        expectJson(DISCOVERY_INSTANCES, "{\"data\":[]}");
        expectJson(DOWNLOADS_INSTANCES, "{\"data\":[]}");
    }

    private static List<ChannelMetricPoint> salesPoints(List<ChannelMetricPoint> points) {
        return points.stream()
                .filter(p -> p.metric().equals("downloads_of_record") || p.metric().equals("sales_report_days"))
                .toList();
    }

    @Test
    void storesDownloadsOfRecordAndCoverageDaysFromSalesReportsForThisAppOnly() {
        expectEmptyAnalytics();
        LocalDate d1 = LocalDate.of(2026, 10, 1);
        LocalDate d2 = d1.plusDays(1);
        LocalDate d3 = d1.plusDays(2);
        expectSalesReport(d1, SALES_HEADER
                + salesRow("12345", "1F", 20) + salesRow("12345", "1", 4) + salesRow("12345", "1T", 6)
                + salesRow("12345", "7", 50) + salesRow("12345", "3", 2) + salesRow("12345", "IAY", 3)
                + salesRow("99999", "1F", 999) + salesRow("99999", "7", 999));
        server.expect(once(), requestTo(salesUrl(d2))).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"title\":\"The request expected results but none were found\"}]}"));
        // a report that exists but has no first-time downloads of ours is a read day with a reported zero
        expectSalesReport(d3, SALES_HEADER + salesRow("12345", "7", 5) + salesRow("99999", "1F", 7));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(null, " 94514742 "), d1, d3);

        server.verify();
        Map<String, Double> m = byKey(salesPoints(points));
        // 2026-10-02 is long settled, so Apple's 404 there means a day with no sales: a covered day reading 0
        assertThat(m).containsOnly(
                Map.entry("downloads_of_record||2026-10-01", 30.0),
                Map.entry("sales_report_days||2026-10-01", 1.0),
                Map.entry("downloads_of_record||2026-10-02", 0.0),
                Map.entry("sales_report_days||2026-10-02", 1.0),
                Map.entry("downloads_of_record||2026-10-03", 0.0),
                Map.entry("sales_report_days||2026-10-03", 1.0));
    }

    @Test
    void aNotFoundRecentDayWritesNothingBecauseAppleMayStillPublishIt() {
        // 2026-11-06 03:00 UTC is 2026-11-05 in Pacific: 11-04 is one day old, so its 404 is "not published yet"
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        AppStoreConnectTokenService tokens = new AppStoreConnectTokenService() {
            @Override public String token(String connectionId, Credentials creds) { return "jwt-token"; }
        };
        connector = new AppStoreConnectConnector(restTemplate, tokens,
                Clock.fixed(Instant.parse("2026-11-06T03:00:00Z"), ZoneOffset.UTC));
        expectEmptyAnalytics();
        LocalDate recent = LocalDate.of(2026, 11, 4);
        server.expect(once(), requestTo(salesUrl(recent))).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"errors\":[{\"title\":\"The request expected results but none were found\"}]}"));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(null, "94514742"), recent, recent);

        server.verify();
        assertThat(salesPoints(points)).isEmpty();
    }

    @Test
    void salesNumbersSitBesideTheAnalyticsDownloadsWithoutChangingThem() {
        expectRequestAndReports();
        expectJson(DISCOVERY_INSTANCES, "{\"data\":[]}");
        expectJson(DOWNLOADS_INSTANCES, "{\"data\":[{\"id\":\"D\",\"attributes\":{\"processingDate\":\"2026-10-03\"}}]}");
        expectSegmentList("D", "https://segments.example.com/d1");
        expectSegment("https://segments.example.com/d1", gzip(
                "Date\tDownload Type\tSource Type\tCounts\n"
                        + "2026-10-02\tFirst-time download\tApp Store Search\t29\n"
                        + "2026-10-02\tRedownload\tApp Store Search\t4\n"));
        expectSalesReport(LocalDate.of(2026, 10, 1), SALES_HEADER + salesRow("12345", "1F", 2));
        expectSalesReport(LocalDate.of(2026, 10, 2), SALES_HEADER + salesRow("12345", "1F", 30));

        Map<String, Double> m = byKey(connector.pullDaily(ctx(null, "94514742"), FROM, TO));

        server.verify();
        assertThat(m).containsEntry("downloads||2026-10-02", 29.0)
                .containsEntry("total_downloads|source:APP_STORE_SEARCH|2026-10-02", 33.0)
                .containsEntry("downloads_of_record||2026-10-02", 30.0)
                .containsEntry("sales_report_days||2026-10-02", 1.0)
                .containsEntry("downloads_of_record||2026-10-01", 2.0);
    }

    @Test
    void withoutAVendorNumberNoSalesRequestIsMadeAndNoSalesPointIsWritten() {
        // no expectation for any salesReports URL: a request would fail the test
        for (String vendor : new String[] {null, "", "   "}) {
            server.reset();
            expectEmptyAnalytics();

            List<ChannelMetricPoint> points = connector.pullDaily(ctx(null, vendor), FROM, TO);

            server.verify();
            assertThat(salesPoints(points)).as("vendorNumber=[" + vendor + "]").isEmpty();
        }
    }

    @Test
    void aVendorNumberThatIsNotNumericIsNeverSentToAppleAndNeverFailsThePull() {
        expectEmptyAnalytics();

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(null, "94514742&x=1"), FROM, TO);

        server.verify();
        assertThat(salesPoints(points)).isEmpty();
        assertThat(connector.verifyCredentials(ctx(null, "abc")))
                .hasValueSatisfying(m -> assertThat(m).contains("vendor number").contains("numbers only"));
    }

    @Test
    void aSalesFailureIsSkippedAndDoesNotFailTheAnalyticsPart() {
        expectRequestAndReports();
        expectJson(DISCOVERY_INSTANCES, "{\"data\":[{\"id\":\"A\",\"attributes\":{\"processingDate\":\"2026-10-03\"}}]}");
        expectSegmentList("A", "https://segments.example.com/a1");
        expectSegment("https://segments.example.com/a1", gzip(
                "Date\tEvent\tSource Type\tCounts\tUnique Counts\n2026-10-02\tImpression\tApp Store Search\t5\t4\n"));
        expectJson(DOWNLOADS_INSTANCES, "{\"data\":[]}");
        // the key's role cannot read Sales: the first request is refused and no further day is tried
        server.expect(once(), requestTo(salesUrl(FROM))).andRespond(withStatus(HttpStatus.FORBIDDEN));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(null, "94514742"), FROM, TO);

        server.verify();
        assertThat(byKey(points)).containsEntry("impressions||2026-10-02", 5.0);
        assertThat(salesPoints(points)).isEmpty();
    }

    @Test
    void aSalesFailureMidWindowKeepsTheDaysAlreadyReadAndStopsAskingForMore() {
        expectEmptyAnalytics();
        expectSalesReport(FROM, SALES_HEADER + salesRow("12345", "1F", 3));
        server.expect(once(), requestTo(salesUrl(TO))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(null, "94514742"), FROM, TO.plusDays(1));

        server.verify();
        assertThat(byKey(salesPoints(points))).containsOnly(
                Map.entry("downloads_of_record||2026-10-01", 3.0),
                Map.entry("sales_report_days||2026-10-01", 1.0));
    }

    @Test
    void anUnreadableSalesReportIsSkippedAndDoesNotFailTheAnalyticsPart() {
        expectEmptyAnalytics();
        expectSalesReport(FROM, "Provider\tSKU\nApple\tSKU1\n"); // no Units / Apple Identifier columns

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(null, "94514742"), FROM, FROM);

        server.verify();
        assertThat(salesPoints(points)).isEmpty();
    }

    @Test
    void salesAreNotRequestedForADayAppleCannotHavePublishedYet() {
        // 2026-11-06 03:00 UTC is 2026-11-05 19:00 in Pacific, Apple's report-date zone: the newest report day is 11-04
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        AppStoreConnectTokenService tokens = new AppStoreConnectTokenService() {
            @Override public String token(String connectionId, Credentials creds) { return "jwt-token"; }
        };
        connector = new AppStoreConnectConnector(restTemplate, tokens,
                Clock.fixed(Instant.parse("2026-11-06T03:00:00Z"), ZoneOffset.UTC));
        expectEmptyAnalytics();
        expectSalesReport(LocalDate.of(2026, 11, 3), SALES_HEADER + salesRow("12345", "1F", 1));
        expectSalesReport(LocalDate.of(2026, 11, 4), SALES_HEADER + salesRow("12345", "1F", 2));
        // 11-05 (Pacific today) has no expectation: asking for it would fail the test

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(null, "94514742"),
                LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 5));

        server.verify();
        assertThat(salesPoints(points)).extracting(ChannelMetricPoint::day)
                .containsOnly(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 11, 4));
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
