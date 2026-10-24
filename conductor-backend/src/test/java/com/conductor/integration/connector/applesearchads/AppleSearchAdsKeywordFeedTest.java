package com.conductor.integration.connector.applesearchads;

import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsPull;
import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.DimensionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Keyword and ad daily rows and the current-state read, against recorded Apple Ads v5 response shapes. */
class AppleSearchAdsKeywordFeedTest {

    private static final String BASE = "https://api.searchads.apple.com/api/v5";
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 2);

    private MockRestServiceServer server;
    private AppleSearchAdsConnector connector;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
        AppleAdsTokenService tokens = mock(AppleAdsTokenService.class);
        when(tokens.accessToken(any(), any())).thenReturn("bearer-token");
        connector = new AppleSearchAdsConnector(restTemplate, tokens,
                Clock.fixed(Instant.parse("2026-10-02T10:00:00Z"), ZoneOffset.UTC));
    }

    private static ConnectionContext ctx() {
        Map<String, Object> config = new HashMap<>();
        config.put("clientId", "CLIENT.abc");
        config.put("teamId", "TEAM.xyz");
        config.put("keyId", "KEY123");
        config.put("orgId", "999");
        return new ConnectionContext("proj", "apple-search-ads", "conn",
                "-----BEGIN PRIVATE KEY-----\nfake\n-----END PRIVATE KEY-----", null, null, config, null);
    }

    private static final String CAMPAIGN_REPORT = """
            {"data":{"reportingDataResponse":{"row":[
              {"metadata":{"campaignId":1001,"campaignName":"Brand"},
               "granularity":[{"date":"2026-10-01","impressions":1000,"taps":70,"tapInstalls":20,
                               "localSpend":{"amount":"50.50","currency":"USD"}}]}]}}}""";

    private static final String CAMPAIGNS = """
            {"data":[
              {"id":1001,"name":"Brand","status":"ENABLED","servingStatus":"RUNNING","startTime":"2026-01-01T00:00:00.000","endTime":null},
              {"id":1002,"name":"Old","status":"PAUSED","servingStatus":"NOT_RUNNING","startTime":"2025-01-01T00:00:00.000","endTime":"2025-06-01T00:00:00.000"}
             ],"pagination":{"totalResults":2,"startIndex":0,"itemsPerPage":1000}}""";

    private static final String KEYWORD_PAGE_1 = """
            {"data":{"reportingDataResponse":{"row":[
              {"metadata":{"keywordId":77,"keyword":"meal planner","matchType":"EXACT","keywordStatus":"ACTIVE","adGroupId":55,"adGroupName":"Core","bidAmount":{"amount":"1.25","currency":"USD"}},
               "granularity":[
                 {"date":"2026-10-01","impressions":500,"taps":40,"tapInstalls":9,"tapNewDownloads":6,"tapRedownloads":3,
                  "localSpend":{"amount":"12.25","currency":"USD"}},
                 {"date":"2026-10-02","impressions":300,"taps":20,"tapInstalls":4,"tapNewDownloads":3,
                  "localSpend":{"amount":"6.00","currency":"USD"}}]},
              {"metadata":{"id":78,"text":"recipes: easy"},
               "granularity":[{"date":"2026-10-01","impressions":100,"taps":5,"tapInstalls":2,
                  "localSpend":{"amount":"1.00","currency":"USD"}}]}
             ]}},"pagination":{"totalResults":3,"startIndex":0,"itemsPerPage":2}}""";

    private static final String KEYWORD_PAGE_2 = """
            {"data":{"reportingDataResponse":{"row":[
              {"metadata":{"keywordId":79,"keyword":"diet"},
               "granularity":[{"date":"2026-10-01","impressions":10,"taps":1,"tapNewDownloads":0,
                  "localSpend":{"amount":"0.10","currency":"USD"}}]}
             ]}},"pagination":{"totalResults":3,"startIndex":2,"itemsPerPage":1}}""";

    private static final String AD_REPORT = """
            {"data":{"reportingDataResponse":{"row":[
              {"metadata":{"adId":9,"adName":"Variant A","adGroupId":55,
                           "creative":{"creativeType":"CUSTOM_PRODUCT_PAGE","destination":{"parameters":{"productPageId":"abc-123"}}}},
               "granularity":[{"date":"2026-10-01","impressions":700,"taps":50,"tapInstalls":12,"tapNewDownloads":10,
                  "localSpend":{"amount":"20.00","currency":"USD"}}]},
              {"metadata":{"id":-1,"adGroupId":55},
               "granularity":[{"date":"2026-10-01","impressions":300,"taps":20,"tapInstalls":5,
                  "localSpend":{"amount":"8.00","currency":"USD"}}]},
              {"metadata":{"adId":-1,"adGroupId":56},
               "granularity":[{"date":"2026-10-01","impressions":90,"taps":4,"tapInstalls":1,
                  "localSpend":{"amount":"2.00","currency":"USD"}}]},
              {"metadata":{"adId":12,"adName":"No group"},
               "granularity":[{"date":"2026-10-01","impressions":1,"taps":1,"tapInstalls":1,
                  "localSpend":{"amount":"1.00","currency":"USD"}}]}
             ]}},"pagination":{"totalResults":1,"startIndex":0,"itemsPerPage":1000}}""";

    private void expectCampaigns() {
        server.expect(once(), requestTo(BASE + "/reports/campaigns"))
                .andRespond(withSuccess(CAMPAIGN_REPORT, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/campaigns?limit=1000&offset=0"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(CAMPAIGNS, MediaType.APPLICATION_JSON));
    }

    private void expectBreakdownReports() {
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1001/keywords"))
                .andExpect(jsonPath("$.granularity").value("DAILY"))
                .andExpect(jsonPath("$.timeZone").value("UTC"))
                .andExpect(jsonPath("$.selector.pagination.offset").value(0))
                .andRespond(withSuccess(KEYWORD_PAGE_1, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1001/keywords"))
                .andExpect(jsonPath("$.selector.pagination.offset").value(2))
                .andRespond(withSuccess(KEYWORD_PAGE_2, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1001/ads"))
                .andExpect(jsonPath("$.timeZone").value("UTC"))
                .andRespond(withSuccess(AD_REPORT, MediaType.APPLICATION_JSON));
    }

    @Test
    void keywordAndAdRowsAreKeyedPagedAndUseFirstTimeInstalls() {
        expectCampaigns();
        expectBreakdownReports();

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        // page 1 and page 2 are both read; the key splits on the second colon so text may contain colons
        assertThat(value(points, "impressions", "keyword:77:meal planner", "2026-10-01")).isEqualByComparingTo("500");
        assertThat(value(points, "spend", "keyword:77:meal planner", "2026-10-01")).isEqualByComparingTo("12.25");
        assertThat(value(points, "impressions", "keyword:78:recipes: easy", "2026-10-01")).isEqualByComparingTo("100");
        assertThat(value(points, "impressions", "keyword:79:diet", "2026-10-01")).isEqualByComparingTo("10");
        // installs are the first-time downloads; tapInstalls is only the fallback
        assertThat(value(points, "installs", "keyword:77:meal planner", "2026-10-01")).isEqualByComparingTo("6");
        assertThat(value(points, "installs", "keyword:77:meal planner", "2026-10-02")).isEqualByComparingTo("3");
        assertThat(value(points, "installs", "keyword:78:recipes: easy", "2026-10-01")).isEqualByComparingTo("2");
        assertThat(value(points, "redownloads", "keyword:77:meal planner", "2026-10-01")).isEqualByComparingTo("3");
        assertThat(points).noneMatch(p -> p.metric().equals("redownloads") && p.dimensionKey().equals("keyword:77:meal planner")
                && p.day().equals(TO));
        assertThat(value(points, "taps", "ad:55:9:Variant A", "2026-10-01")).isEqualByComparingTo("50");
        assertThat(value(points, "installs", "ad:55:9:Variant A", "2026-10-01")).isEqualByComparingTo("10");
        // the default-page pseudo-ad (-1) is kept once per ad group, never merged, and named when Apple gives no name
        assertThat(value(points, "impressions", "ad:55:-1:Default product page", "2026-10-01")).isEqualByComparingTo("300");
        assertThat(value(points, "impressions", "ad:56:-1:Default product page", "2026-10-01")).isEqualByComparingTo("90");
        // a row without an ad group is skipped
        assertThat(points).noneMatch(p -> p.dimensionKey().contains("No group"));
        // campaign rows and the total are unchanged: tapInstalls, and keyword/ad rows are not added to the total
        assertThat(value(points, "installs", "campaign:1001:Brand", "2026-10-01")).isEqualByComparingTo("20");
        assertThat(value(points, "installs", "", "2026-10-01")).isEqualByComparingTo("20");
        assertThat(value(points, "spend", "", "2026-10-01")).isEqualByComparingTo("50.50");
        server.verify();
    }

    @Test
    void aFailedKeywordReportFailsThePullInsteadOfReturningAPartialWindow() {
        expectCampaigns();
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1001/keywords"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> connector.pull(ctx(), FROM, TO))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class,
                        e -> assertThat(e.health()).isEqualTo(ConnectorHealth.DEGRADED));
    }

    @Test
    void aFailedAdReportFailsThePullToo() {
        expectCampaigns();
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1001/keywords"))
                .andRespond(withSuccess("{\"data\":{\"reportingDataResponse\":{\"row\":[]}}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1001/ads"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> connector.pullDaily(ctx(), FROM, TO))
                .isInstanceOf(ChannelMetricsSourceException.class);
    }

    @Test
    void currentStateOfKeywordsAndAdsIsReadWhenTheWindowReachesToday() {
        expectCampaigns();
        expectBreakdownReports();
        server.expect(once(), requestTo(BASE + "/campaigns/1001/adgroups?limit=1000&offset=0"))
                .andRespond(withSuccess("""
                        {"data":[{"id":55,"name":"Core","status":"ENABLED"}],"pagination":{"totalResults":1}}""",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/campaigns/1001/adgroups/55/targetingkeywords?limit=1000&offset=0"))
                .andRespond(withSuccess("""
                        {"data":[{"id":77,"text":"meal planner","matchType":"EXACT","status":"ACTIVE",
                                  "bidAmount":{"amount":"1.50","currency":"USD"}}],"pagination":{"totalResults":1}}""",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/campaigns/1001/adgroups/55/ads?limit=1000&offset=0"))
                .andRespond(withSuccess("""
                        {"data":[{"id":9,"name":"Variant A","status":"ENABLED","servingStatus":"RUNNING",
                                  "creativeType":"CUSTOM_PRODUCT_PAGE","productPageId":"abc-123"}],
                         "pagination":{"totalResults":1}}""", MediaType.APPLICATION_JSON));

        ChannelMetricsPull pulled = connector.pull(ctx(), FROM, TO);

        Map<String, Map<String, Object>> byKey = new HashMap<>();
        for (DimensionState s : pulled.dimensionStates()) byKey.put(s.dimensionKey(), s.state());
        assertThat(byKey).containsOnlyKeys("keyword:77:meal planner", "keyword:78:recipes: easy", "keyword:79:diet",
                "ad:55:9:Variant A", "ad:55:-1:Default product page", "ad:56:-1:Default product page");
        // members the state read did not cover fall back to what the report row said; no matchType is Search Match
        assertThat(byKey.get("keyword:78:recipes: easy")).containsEntry("matchType", "SEARCH_MATCH");
        assertThat(byKey.get("ad:56:-1:Default product page")).containsEntry("adGroupId", "56");
        assertThat(byKey.get("keyword:77:meal planner"))
                .containsEntry("status", "ACTIVE").containsEntry("matchType", "EXACT")
                .containsEntry("bidAmount", 1.5).containsEntry("bidCurrency", "USD")
                .containsEntry("adGroupName", "Core").containsEntry("campaignName", "Brand")
                .containsEntry("campaignStatus", "ENABLED").containsEntry("campaignServingStatus", "RUNNING");
        assertThat(byKey.get("ad:55:9:Variant A"))
                .containsEntry("servingStatus", "RUNNING").containsEntry("creativeType", "CUSTOM_PRODUCT_PAGE")
                .containsEntry("productPageId", "abc-123").containsEntry("adGroupName", "Core");
        server.verify();
    }

    @Test
    void aStateReadFailureNeverFailsTheMetricsPull() {
        expectCampaigns();
        expectBreakdownReports();
        server.expect(once(), requestTo(BASE + "/campaigns/1001/adgroups?limit=1000&offset=0"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        ChannelMetricsPull pulled = connector.pull(ctx(), FROM, TO);

        assertThat(pulled.points()).isNotEmpty();
        // the report rows' own metadata stands in for the failed read
        Map<String, Map<String, Object>> byKey = new HashMap<>();
        for (DimensionState s : pulled.dimensionStates()) byKey.put(s.dimensionKey(), s.state());
        assertThat(byKey.get("keyword:77:meal planner")).containsEntry("matchType", "EXACT")
                .containsEntry("status", "ACTIVE").containsEntry("bidAmount", 1.25).containsEntry("adGroupName", "Core");
        assertThat(byKey.get("ad:55:9:Variant A")).containsEntry("creativeType", "CUSTOM_PRODUCT_PAGE")
                .containsEntry("productPageId", "abc-123");
    }

    @Test
    void stateIsSkippedForABackfillChunkThatDoesNotReachToday() {
        expectCampaigns();
        expectBreakdownReports();

        ChannelMetricsPull pulled = connector.pull(ctx(), FROM, LocalDate.of(2026, 10, 1));

        assertThat(pulled.dimensionStates()).isEmpty();
    }

    @Test
    void aCampaignThatEndedBeforeTheWindowIsNotQueried() {
        expectCampaigns();
        expectBreakdownReports();

        connector.pullDaily(ctx(), FROM, TO);

        // no expectation exists for campaign 1002 (ended 2025-06-01): a request to it would fail the call
        server.verify();
    }

    private static final String MIXED_CAMPAIGNS = """
            {"data":[
              {"id":1001,"name":"Brand","adChannelType":"SEARCH","startTime":"2026-01-01T00:00:00.000","endTime":null},
              {"id":1003,"name":"Today","adChannelType":"DISPLAY","startTime":"2026-01-01T00:00:00.000","endTime":null},
              {"id":1004,"name":"Legacy","startTime":"2026-01-01T00:00:00.000","endTime":null}
             ],"pagination":{"totalResults":3,"startIndex":0,"itemsPerPage":1000}}""";

    private void expectMixedCampaigns() {
        server.expect(once(), requestTo(BASE + "/reports/campaigns"))
                .andRespond(withSuccess(CAMPAIGN_REPORT, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/campaigns?limit=1000&offset=0"))
                .andRespond(withSuccess(MIXED_CAMPAIGNS, MediaType.APPLICATION_JSON));
    }

    @Test
    void nonSearchCampaignsAreNotAskedForKeywordOrAdReports() {
        expectMixedCampaigns();
        expectBreakdownReports();
        // campaign 1004 has no adChannelType, so it is still queried
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1004/keywords"))
                .andRespond(withSuccess("{\"data\":{\"reportingDataResponse\":{\"row\":[]}}}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1004/ads"))
                .andRespond(withSuccess("{\"data\":{\"reportingDataResponse\":{\"row\":[]}}}", MediaType.APPLICATION_JSON));

        connector.pullDaily(ctx(), FROM, TO);

        // no expectation for campaign 1003 (DISPLAY): a request to it would fail the call
        server.verify();
    }

    @Test
    void aClientErrorOnOneCampaignsBreakdownIsSkippedAndTheRestOfThePullSurvives() {
        server.expect(once(), requestTo(BASE + "/reports/campaigns"))
                .andRespond(withSuccess(CAMPAIGN_REPORT, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/campaigns?limit=1000&offset=0"))
                .andRespond(withSuccess("""
                        {"data":[{"id":1001,"name":"Brand","adChannelType":"SEARCH"},
                                 {"id":1005,"name":"Odd","adChannelType":"SEARCH"}],
                         "pagination":{"totalResults":2}}""", MediaType.APPLICATION_JSON));
        expectBreakdownReports();
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1005/keywords"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.BAD_REQUEST));

        com.conductor.integration.ChannelMetricsPull pull = connector.pull(ctx(), FROM, LocalDate.of(2026, 10, 1));

        assertThat(value(pull.points(), "impressions", "keyword:77:meal planner", "2026-10-01")).isEqualByComparingTo("500");
        // the store is told to keep that campaign's stored keywords and ads, not to stop pruning altogether
        assertThat(pull.skippedCampaignIds()).containsExactly("1005");
    }

    @Test
    void aCompletePullSkipsNoCampaign() {
        server.expect(once(), requestTo(BASE + "/reports/campaigns"))
                .andRespond(withSuccess(CAMPAIGN_REPORT, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/campaigns?limit=1000&offset=0"))
                .andRespond(withSuccess(CAMPAIGNS, MediaType.APPLICATION_JSON));
        expectBreakdownReports();

        assertThat(connector.pull(ctx(), FROM, LocalDate.of(2026, 10, 1)).skippedCampaignIds()).isEmpty();
    }

    @Test
    void aServerErrorOnABreakdownReportStillFailsThePull() {
        server.expect(once(), requestTo(BASE + "/reports/campaigns"))
                .andRespond(withSuccess(CAMPAIGN_REPORT, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/campaigns?limit=1000&offset=0"))
                .andRespond(withSuccess(CAMPAIGNS, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1001/keywords"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> connector.pullDaily(ctx(), FROM, TO))
                .isInstanceOf(RuntimeException.class);
    }

    private static BigDecimal value(List<ChannelMetricPoint> points, String metric, String dimension, String day) {
        return points.stream()
                .filter(p -> p.metric().equals(metric) && p.dimensionKey().equals(dimension) && p.day().toString().equals(day))
                .map(ChannelMetricPoint::value).findFirst().orElseThrow();
    }
}
