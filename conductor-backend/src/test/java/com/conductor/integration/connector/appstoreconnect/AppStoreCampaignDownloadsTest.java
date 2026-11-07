package com.conductor.integration.connector.appstoreconnect;

import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsPull;
import com.conductor.integration.ConnectionContext;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Campaign-token downloads: the App Downloads Detailed report's {@code Campaign} column, stored as STORE
 * {@code campaign_downloads} points with dimension {@code ct:{token}}.
 */
class AppStoreCampaignDownloadsTest {

    private static final String API = "https://api.appstoreconnect.apple.com";
    private static final String REQUESTS = API + "/v1/apps/12345/analyticsReportRequests?filter%5BaccessType%5D=ONGOING";
    private static final String LOOKUP = API + "/v1/analyticsReportRequests/REQ1/reports?filter%5Bname%5D=";
    private static final String DISCOVERY_LOOKUP = LOOKUP + "App%20Store%20Discovery%20and%20Engagement%20Standard";
    private static final String DOWNLOADS_LOOKUP = LOOKUP + "App%20Downloads%20Standard";
    private static final String DETAILED_LOOKUP = LOOKUP + "App%20Downloads%20Detailed";
    private static final String DISCOVERY_INSTANCES = API
            + "/v1/analyticsReports/R1/instances?filter%5Bgranularity%5D=DAILY&limit=200";
    private static final String DOWNLOADS_INSTANCES = API
            + "/v1/analyticsReports/R2/instances?filter%5Bgranularity%5D=DAILY&limit=200";
    private static final String DETAILED_INSTANCES = API
            + "/v1/analyticsReports/R3/instances?filter%5Bgranularity%5D=DAILY&limit=200";

    private static final String HEADER = "Date\tApp Name\tDownload Type\tSource Type\tSource Info\tCampaign\tTerritory\tCounts\n";
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 2);

    private final List<String> detailedInstances = new java.util.ArrayList<>();
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

    // -- helpers ----------------------------------------------------------------------------------------

    private static ConnectionContext ctx() {
        return new ConnectionContext("proj", "app-store-connect", "conn",
                "-----BEGIN PRIVATE KEY-----\nfake\n-----END PRIVATE KEY-----", null, null,
                Map.of("issuerId", "ISSUER", "keyId", "KEY123", "appId", "12345"), null);
    }

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

    /** The app, the ONGOING request and the Standard reports: one downloads day (Oct 2) and no Discovery rows. */
    private void expectStandard() {
        expectJson(API + "/v1/apps/12345", "{\"data\":{\"id\":\"12345\"}}");
        expectJson(REQUESTS, "{\"data\":[{\"id\":\"REQ1\"}]}");
        expectJson(DISCOVERY_LOOKUP, "{\"data\":[{\"id\":\"R1\"}]}");
        expectJson(DOWNLOADS_LOOKUP, "{\"data\":[{\"id\":\"R2\"}]}");
        expectJson(DISCOVERY_INSTANCES, "{\"data\":[]}");
        expectJson(DOWNLOADS_INSTANCES, "{\"data\":[{\"id\":\"D\",\"attributes\":{\"processingDate\":\"2026-10-03\"}}]}");
        expectSegmentList("D", "https://segments.example.com/standard");
        expectSegment("https://segments.example.com/standard", gzip(
                "Date\tDownload Type\tSource Type\tCounts\n"
                        + "2026-10-02\tFirst-time download\tApp Store Search\t29\n"
                        + "2026-10-02\tRedownload\tApp Store Search\t4\n"));
    }

    private void expectDetailedReport() {
        expectJson(DETAILED_LOOKUP, "{\"data\":[{\"id\":\"R3\"}]}");
    }

    /**
     * Expects the segment list and segments of one Detailed instance and queues it for the instance list, which
     * {@link #flushDetailedInstances()} then expects. An instance processed on day P carries report day P-1.
     */
    private void expectDetailedInstance(String instanceId, String processingDate, byte[]... segments) {
        String[] urls = new String[segments.length];
        for (int i = 0; i < segments.length; i++) {
            urls[i] = "https://segments.example.com/" + instanceId + "-" + i;
            expectSegment(urls[i], segments[i]);
        }
        expectSegmentList(instanceId, urls);
        detailedInstances.add("{\"id\":\"" + instanceId + "\",\"attributes\":{\"processingDate\":\""
                + processingDate + "\"}}");
    }

    private void flushDetailedInstances() {
        expectJson(DETAILED_INSTANCES, "{\"data\":[" + String.join(",", detailedInstances) + "]}");
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

    private static String row(String day, String type, String source, String campaign, int counts) {
        return day + "\tRexipe\t" + type + "\t" + source + "\t\t" + campaign + "\tUS\t" + counts + "\n";
    }

    private static Map<String, Double> byKey(List<ChannelMetricPoint> points) {
        Map<String, Double> m = new HashMap<>();
        for (ChannelMetricPoint p : points) {
            m.put(p.metric() + "|" + p.dimensionKey() + "|" + p.day(), p.value().doubleValue());
        }
        assertThat(m).hasSameSizeAs(points); // no duplicate keys
        return m;
    }

    private static List<ChannelMetricPoint> campaignPoints(List<ChannelMetricPoint> points) {
        return points.stream().filter(p -> p.metric().equals("campaign_downloads")).toList();
    }

    private static Map<String, Double> standardPoints(List<ChannelMetricPoint> points) {
        return byKey(points.stream().filter(p -> !p.metric().equals("campaign_downloads")).toList());
    }

    // -- the criterion ----------------------------------------------------------------------------------

    @Test
    void storesFirstTimeDownloadsPerDayAndCampaignTokenFromTheDetailedReport() {
        expectStandard();
        expectDetailedReport();
        expectDetailedInstance("DET", "2026-10-03", gzip(HEADER
                + row("2026-10-01", "First-time download", "App Referrer", "springpromo", 3)
                + row("2026-10-01", "First-time download", "Web Referrer", "springpromo", 2)
                + row("2026-10-01", "First-time download", "App Store Search", "bio", 6)
                + row("2026-10-01", "Redownload", "App Referrer", "springpromo", 4)
                + row("2026-10-02", "First-time download", "App Referrer", "springpromo", 7)
                + row("2026-10-02", "First-time download", "App Store Browse", "bio", 5)
                + row("2026-10-02", "Manual update", "App Store Browse", "bio", 40)
                + row("2026-10-02", "Auto-update", "App Referrer", "springpromo", 22)));
        flushDetailedInstances();

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        server.verify();
        assertThat(byKey(campaignPoints(points))).containsOnly(
                Map.entry("campaign_downloads|ct:springpromo|2026-10-01", 5.0),
                Map.entry("campaign_downloads|ct:springpromo|2026-10-02", 7.0),
                Map.entry("campaign_downloads|ct:bio|2026-10-01", 6.0),
                Map.entry("campaign_downloads|ct:bio|2026-10-02", 5.0));
    }

    @Test
    void aTokenWithOnlyRedownloadsOrUpdatesHasNoPointNotAZero() {
        expectStandard();
        expectDetailedReport();
        expectDetailedInstance("DET", "2026-10-03", gzip(HEADER
                + row("2026-10-02", "Redownload", "App Referrer", "redownloadsonly", 9)
                + row("2026-10-02", "Manual update", "App Referrer", "redownloadsonly", 9)
                + row("2026-10-02", "First-time download", "App Referrer", "real", 6)));
        flushDetailedInstances();

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        assertThat(byKey(campaignPoints(points))).containsOnly(
                Map.entry("campaign_downloads|ct:real|2026-10-02", 6.0));
    }

    @Test
    void rowsWithAnEmptyCampaignWriteNothing() {
        expectStandard();
        expectDetailedReport();
        expectDetailedInstance("DET", "2026-10-03", gzip(HEADER
                + row("2026-10-02", "First-time download", "App Store Search", "", 12)
                + row("2026-10-02", "First-time download", "App Store Browse", "   ", 4)
                + row("2026-10-02", "First-time download", "App Referrer", "kept", 2)));
        flushDetailedInstances();

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        assertThat(byKey(campaignPoints(points))).containsOnly(Map.entry("campaign_downloads|ct:kept|2026-10-02", 2.0));
        assertThat(points).extracting(ChannelMetricPoint::dimensionKey)
                .doesNotContain("ct:", "ct:   ", "ct:null");
    }

    @Test
    void aSegmentWithNoCampaignColumnWritesNoCampaignPointsAndNeverFailsThePull() {
        expectStandard();
        expectDetailedReport();
        expectDetailedInstance("DET", "2026-10-03", gzip(
                "Date\tDownload Type\tSource Type\tCounts\n2026-10-02\tFirst-time download\tApp Referrer\t8\n"));
        flushDetailedInstances();

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        assertThat(campaignPoints(points)).isEmpty();
        assertThat(standardPoints(points)).containsEntry("downloads||2026-10-02", 29.0);
    }

    // -- absent / unreadable Detailed report -------------------------------------------------------------

    @Test
    void aDetailedReportMissingFromTheRequestIsNotAnErrorAndLeavesTheStandardPointsAsTheyAre() {
        expectStandard();
        expectJson(DETAILED_LOOKUP, "{\"data\":[]}");

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        server.verify();
        assertThat(campaignPoints(points)).isEmpty();
        assertThat(standardPoints(points)).containsOnly(
                Map.entry("downloads||2026-10-02", 29.0),
                Map.entry("downloads|source:APP_STORE_SEARCH|2026-10-02", 29.0),
                Map.entry("redownloads||2026-10-02", 4.0),
                Map.entry("redownloads|source:APP_STORE_SEARCH|2026-10-02", 4.0),
                Map.entry("total_downloads||2026-10-02", 33.0),
                Map.entry("total_downloads|source:APP_STORE_SEARCH|2026-10-02", 33.0));
    }

    @Test
    void aDetailedSegmentThatFailsToParseSkipsCampaignPointsButKeepsTheStandardOnes() {
        expectStandard();
        expectDetailedReport();
        // no Download Type / Counts columns: Apple's report no longer means what we read
        expectDetailedInstance("DET", "2026-10-03", gzip("Date\tCampaign\n2026-10-02\tspringpromo\n"));
        flushDetailedInstances();

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        server.verify();
        assertThat(campaignPoints(points)).isEmpty();
        assertThat(standardPoints(points)).containsEntry("downloads||2026-10-02", 29.0)
                .containsEntry("total_downloads||2026-10-02", 33.0);
    }

    @Test
    void aCorruptDetailedSegmentSkipsCampaignPointsButKeepsTheStandardOnes() {
        expectStandard();
        expectDetailedReport();
        // gzip magic bytes followed by garbage
        expectDetailedInstance("DET", "2026-10-03", new byte[] {0x1f, (byte) 0x8b, 0x08, 0x00, 0x01, 0x02, 0x03});
        flushDetailedInstances();

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        assertThat(campaignPoints(points)).isEmpty();
        assertThat(standardPoints(points)).containsEntry("downloads||2026-10-02", 29.0);
    }

    @Test
    void anUnreadableCountOnACampaignRowSkipsTheWholeDetailedPullInsteadOfWritingAGuess() {
        expectStandard();
        expectDetailedReport();
        expectDetailedInstance("DET", "2026-10-03", gzip(HEADER
                + row("2026-10-02", "First-time download", "App Referrer", "good", 5)
                + "2026-10-02\tRexipe\tFirst-time download\tApp Referrer\t\tbad\tUS\tmany\n"));
        flushDetailedInstances();

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        assertThat(campaignPoints(points)).isEmpty();
        assertThat(standardPoints(points)).containsEntry("downloads||2026-10-02", 29.0);
    }

    @Test
    void aFailingDetailedRequestIsSkippedAndNeverFailsTheStandardPart() {
        expectStandard();
        expectDetailedReport();
        server.expect(once(), requestTo(DETAILED_INSTANCES)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        server.verify();
        assertThat(campaignPoints(points)).isEmpty();
        assertThat(standardPoints(points)).containsEntry("downloads||2026-10-02", 29.0);
    }

    @Test
    void aRateLimitedDetailedLookupIsSkippedAndNeverFailsTheStandardPart() {
        expectStandard();
        server.expect(once(), requestTo(DETAILED_LOOKUP)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        server.verify();
        assertThat(campaignPoints(points)).isEmpty();
        assertThat(standardPoints(points)).containsEntry("downloads||2026-10-02", 29.0);
    }

    // -- instances, segments, window ----------------------------------------------------------------------

    @Test
    void theSegmentsOfOneInstanceAreSummedAndALaterInstanceReplacesAnEarlierOneForTheSameDay() {
        expectStandard();
        expectDetailedReport();
        // E1 (processed Oct 3) carries Oct 2 in two segments, plus Oct 1; E2 (processed Oct 4) restates Oct 2 and
        // also carries Oct 3, which is after the window.
        expectDetailedInstance("E1", "2026-10-03",
                gzip(HEADER + row("2026-10-02", "First-time download", "App Referrer", "spring", 4)
                        + row("2026-10-01", "First-time download", "App Referrer", "older", 2)),
                gzip(HEADER + row("2026-10-02", "First-time download", "Web Referrer", "spring", 3)));
        expectDetailedInstance("E2", "2026-10-04",
                gzip(HEADER + row("2026-10-02", "First-time download", "App Referrer", "spring", 9)
                        + row("2026-10-03", "First-time download", "App Referrer", "spring", 99)));
        flushDetailedInstances();

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        assertThat(byKey(campaignPoints(points))).containsOnly(
                Map.entry("campaign_downloads|ct:spring|2026-10-02", 9.0),
                Map.entry("campaign_downloads|ct:older|2026-10-01", 2.0));
        assertThat(points).extracting(ChannelMetricPoint::day).allMatch(d -> !d.isAfter(TO));
    }

    @Test
    void aDayBeforeTheWindowIsDroppedAndAnInstanceProcessedBeforeTheWindowIsNotRead() {
        expectStandard();
        expectDetailedReport();
        // no segment expectation for "OLD": requesting it would fail the test
        detailedInstances.add("{\"id\":\"OLD\",\"attributes\":{\"processingDate\":\"2026-09-20\"}}");
        expectDetailedInstance("DET", "2026-10-03", gzip(HEADER
                + row("2026-09-30", "First-time download", "App Referrer", "early", 50)
                + row("2026-10-01", "First-time download", "App Referrer", "inside", 1)));
        flushDetailedInstances();

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        server.verify();
        assertThat(byKey(campaignPoints(points))).containsOnly(Map.entry("campaign_downloads|ct:inside|2026-10-01", 1.0));
    }

    @Test
    void theDimensionKeyIsCappedAt190CharactersAndCollidingTokensAreSummed() {
        String longA = "a".repeat(300);
        String longB = "a".repeat(250) + "b".repeat(50);
        expectStandard();
        expectDetailedReport();
        expectDetailedInstance("DET", "2026-10-03", gzip(HEADER
                + row("2026-10-02", "First-time download", "App Referrer", longA, 2)
                + row("2026-10-02", "First-time download", "App Referrer", longB, 3)));
        flushDetailedInstances();

        List<ChannelMetricPoint> campaign = campaignPoints(connector.pullDaily(ctx(), FROM, TO));

        assertThat(campaign).singleElement().satisfies(p -> {
            assertThat(p.dimensionKey()).hasSize(190).startsWith("ct:aaaa");
            assertThat(p.value().doubleValue()).isEqualTo(5.0);
            assertThat(p.day()).isEqualTo(TO);
        });
    }

    @Test
    void campaignPointsSitBesideTheStandardPointsWithoutChangingThem() {
        expectStandard();
        expectJson(DETAILED_LOOKUP, "{\"data\":[]}");
        Map<String, Double> without = standardPoints(connector.pullDaily(ctx(), FROM, TO));

        server.reset();
        expectStandard();
        expectDetailedReport();
        expectDetailedInstance("DET", "2026-10-03", gzip(HEADER
                + row("2026-10-02", "First-time download", "App Referrer", "spring", 700)));
        flushDetailedInstances();
        List<ChannelMetricPoint> with = connector.pullDaily(ctx(), FROM, TO);

        assertThat(standardPoints(with)).isEqualTo(without);
        assertThat(campaignPoints(with)).hasSize(1);
    }

    // -- contract version ---------------------------------------------------------------------------------

    @Test
    void thePullReportsContractVersion3SoHistoryIsReWalkedOnceMoreForCampaignDownloads() {
        expectStandard();
        expectDetailedReport();
        expectDetailedInstance("DET", "2026-10-03", gzip(HEADER
                + row("2026-10-02", "First-time download", "App Referrer", "spring", 3)));
        flushDetailedInstances();

        ChannelMetricsPull pull = connector.pull(ctx(), FROM, TO);

        assertThat(pull.contractVersion()).isEqualTo(3);
        assertThat(AppStoreConnectConnector.CONTRACT_VERSION).isEqualTo(3);
        assertThat(byKey(campaignPoints(pull.points()))).containsOnly(
                Map.entry("campaign_downloads|ct:spring|2026-10-02", 3.0));
    }
}
