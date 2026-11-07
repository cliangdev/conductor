package com.conductor.integration.connector.applesearchads;

import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsPull;
import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorHealth;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Per-campaign search-term daily rows (Apple's org time zone, the only zone that report allows), against
 * recorded Apple Ads v5 response shapes.
 */
class AppleSearchAdsSearchTermFeedTest {

    private static final String BASE = "https://api.searchads.apple.com/api/v5";
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 2);
    private static final String EMPTY = "{\"data\":{\"reportingDataResponse\":{\"row\":[]}}}";

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

    private void expectCampaigns(String campaignsJson) {
        server.expect(once(), requestTo(BASE + "/reports/campaigns"))
                .andRespond(withSuccess(CAMPAIGN_REPORT, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/campaigns?limit=1000&offset=0"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(campaignsJson, MediaType.APPLICATION_JSON));
    }

    private void expectCampaign1001() {
        expectCampaigns("""
                {"data":[{"id":1001,"name":"Brand","adChannelType":"SEARCH","startTime":"2026-01-01T00:00:00.000"}],
                 "pagination":{"totalResults":1}}""");
    }

    private void expectEmptyKeywordsAndAds(String campaignId) {
        server.expect(once(), requestTo(BASE + "/reports/campaigns/" + campaignId + "/keywords"))
                .andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/reports/campaigns/" + campaignId + "/ads"))
                .andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));
    }

    private static final String SEARCH_TERMS = """
            {"data":{"reportingDataResponse":{"row":[
              {"metadata":{"searchTermText":"meal planner","searchTermSource":"TARGETED","keywordId":77,"adGroupId":55},
               "granularity":[
                 {"date":"2026-10-01","impressions":300,"taps":30,"tapInstalls":6,"tapNewDownloads":4,"tapRedownloads":2,
                  "localSpend":{"amount":"9.50","currency":"USD"}},
                 {"date":"2026-10-02","impressions":200,"taps":10,"tapInstalls":1,"tapNewDownloads":1,
                  "localSpend":{"amount":"3.00","currency":"USD"}}]},
              {"metadata":{"searchTermText":"recipes: easy","searchTermSource":"AUTO","adGroupId":55},
               "granularity":[{"date":"2026-10-01","impressions":40,"taps":2,"tapInstalls":1,
                  "localSpend":{"amount":"0.80","currency":"USD"}}]},
              {"metadata":{"searchTermText":null,"searchTermSource":"AUTO","adGroupId":55},
               "granularity":[{"date":"2026-10-01","impressions":5,"taps":1,"tapInstalls":1,"tapNewDownloads":1,
                  "localSpend":{"amount":"0.40","currency":"USD"}}]},
              {"metadata":{"searchTermSource":"TARGETED","adGroupId":56},
               "granularity":[{"date":"2026-10-01","impressions":7,"taps":2,"tapInstalls":0,"tapNewDownloads":0,
                  "localSpend":{"amount":"0.60","currency":"USD"}},
                 {"date":"2026-10-02","impressions":3,"taps":1,"localSpend":{"amount":"0.20","currency":"USD"}}]},
              {"metadata":{"searchTermText":"  ","adGroupId":57},
               "granularity":[{"date":"2026-10-01","impressions":1,"taps":0,"localSpend":{"amount":"0.00","currency":"USD"}}]},
              {"metadata":{"searchTermText":"meal planner","searchTermSource":"AUTO","adGroupId":56},
               "granularity":[{"date":"2026-10-01","impressions":100,"taps":10,"tapInstalls":2,"tapNewDownloads":2,
                  "localSpend":{"amount":"2.50","currency":"USD"}}]}
            ]}}}""";

    private void expectSearchTerms(String campaignId, String body) {
        server.expect(once(), requestTo(BASE + "/reports/campaigns/" + campaignId + "/searchterms"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-AP-Context", "orgId=999"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test
    void searchTermRowsAreStoredPerCampaignInTheOrgTimeZone() {
        expectCampaign1001();
        expectEmptyKeywordsAndAds("1001");
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1001/searchterms"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.granularity").value("DAILY"))
                .andExpect(jsonPath("$.timeZone").value("ORTZ"))
                .andExpect(jsonPath("$.startTime").value("2026-10-01"))
                .andExpect(jsonPath("$.endTime").value("2026-10-02"))
                .andExpect(jsonPath("$.returnRowTotals").value(false))
                .andExpect(jsonPath("$.returnGrandTotals").value(false))
                .andExpect(jsonPath("$.selector.pagination.offset").value(0))
                .andExpect(jsonPath("$.selector.pagination.limit").value(1000))
                .andRespond(withSuccess(SEARCH_TERMS, MediaType.APPLICATION_JSON));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        String planner = "searchterm:1001:meal planner";
        assertThat(value(points, "spend", planner, "2026-10-02")).isEqualByComparingTo("3.00");
        assertThat(value(points, "impressions", planner, "2026-10-02")).isEqualByComparingTo("200");
        assertThat(value(points, "taps", planner, "2026-10-02")).isEqualByComparingTo("10");
        assertThat(value(points, "installs", planner, "2026-10-02")).isEqualByComparingTo("1");
        assertThat(value(points, "new_downloads", planner, "2026-10-02")).isEqualByComparingTo("1");
        // the term may contain a colon: the key is split on the second one only
        assertThat(value(points, "impressions", "searchterm:1001:recipes: easy", "2026-10-01")).isEqualByComparingTo("40");
        assertThat(value(points, "installs", "searchterm:1001:recipes: easy", "2026-10-01")).isEqualByComparingTo("1");
        server.verify();
    }

    @Test
    void theSameTermFromTwoRowsAddsUpIntoOnePointPerDay() {
        expectCampaign1001();
        expectEmptyKeywordsAndAds("1001");
        expectSearchTerms("1001", SEARCH_TERMS);

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        String planner = "searchterm:1001:meal planner";
        // 300 + 100, 30 + 10, 6 + 2, 4 + 2, 9.50 + 2.50 from the targeted and the auto row
        assertThat(value(points, "impressions", planner, "2026-10-01")).isEqualByComparingTo("400");
        assertThat(value(points, "taps", planner, "2026-10-01")).isEqualByComparingTo("40");
        assertThat(value(points, "installs", planner, "2026-10-01")).isEqualByComparingTo("8");
        assertThat(value(points, "new_downloads", planner, "2026-10-01")).isEqualByComparingTo("6");
        assertThat(value(points, "spend", planner, "2026-10-01")).isEqualByComparingTo("12.00");
        assertThat(points.stream().filter(p -> p.metric().equals("impressions") && p.dimensionKey().equals(planner)
                && p.day().equals(FROM))).hasSize(1);
    }

    @Test
    void aRowWithNoTermTextIsTheLowVolumeRowAndItsValuesAddUp() {
        expectCampaign1001();
        expectEmptyKeywordsAndAds("1001");
        expectSearchTerms("1001", SEARCH_TERMS);

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        String low = "searchterm:1001:(low volume)";
        // null text, missing text and blank text all land in the one row: 5 + 7 + 1 impressions on the first day
        assertThat(value(points, "impressions", low, "2026-10-01")).isEqualByComparingTo("13");
        assertThat(value(points, "taps", low, "2026-10-01")).isEqualByComparingTo("3");
        assertThat(value(points, "spend", low, "2026-10-01")).isEqualByComparingTo("1.00");
        assertThat(value(points, "installs", low, "2026-10-01")).isEqualByComparingTo("1");
        assertThat(value(points, "new_downloads", low, "2026-10-01")).isEqualByComparingTo("1");
        assertThat(value(points, "impressions", low, "2026-10-02")).isEqualByComparingTo("3");
        assertThat(points.stream().filter(p -> p.metric().equals("impressions") && p.dimensionKey().equals(low)
                && p.day().equals(FROM))).hasSize(1);
        // no row is stored under a null or empty term
        assertThat(points).noneMatch(p -> p.dimensionKey().startsWith("searchterm:") && p.dimensionKey().endsWith(":"));
        assertThat(points).noneMatch(p -> p.dimensionKey().contains("null"));
    }

    @Test
    void aFieldAppleDoesNotSendWritesNoSearchTermPoint() {
        expectCampaign1001();
        expectEmptyKeywordsAndAds("1001");
        expectSearchTerms("1001", SEARCH_TERMS);

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        // "recipes: easy" sends no tapNewDownloads: no new_downloads, not a zero
        assertThat(points).noneMatch(p -> p.metric().equals("new_downloads")
                && p.dimensionKey().equals("searchterm:1001:recipes: easy"));
        // the low-volume row sent no installs on its second day (only spend, impressions, taps)
        assertThat(points).noneMatch(p -> p.metric().equals("installs")
                && p.dimensionKey().equals("searchterm:1001:(low volume)") && p.day().equals(TO));
        // search-term rows never carry the metrics the contract leaves out
        assertThat(points).noneMatch(p -> p.dimensionKey().startsWith("searchterm:")
                && (p.metric().equals("redownloads") || p.metric().equals("view_installs")));
    }

    @Test
    void searchTermRowsNeverFeedTheTotalsOrBecomeDimensionStates() {
        expectCampaign1001();
        expectEmptyKeywordsAndAds("1001");
        expectSearchTerms("1001", SEARCH_TERMS);
        server.expect(once(), requestTo(BASE + "/campaigns/1001/adgroups?limit=1000&offset=0"))
                .andRespond(withSuccess("{\"data\":[],\"pagination\":{\"totalResults\":0}}", MediaType.APPLICATION_JSON));

        ChannelMetricsPull pulled = connector.pull(ctx(), FROM, TO);

        assertThat(value(pulled.points(), "installs", "", "2026-10-01")).isEqualByComparingTo("20");
        assertThat(value(pulled.points(), "impressions", "", "2026-10-01")).isEqualByComparingTo("1000");
        assertThat(value(pulled.points(), "spend", "", "2026-10-01")).isEqualByComparingTo("50.50");
        assertThat(pulled.dimensionStates()).noneMatch(s -> s.dimensionKey().startsWith("searchterm:"));
    }

    @Test
    void everyPageOfSearchTermsIsRead() {
        expectCampaign1001();
        expectEmptyKeywordsAndAds("1001");
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1001/searchterms"))
                .andExpect(jsonPath("$.selector.pagination.offset").value(0))
                .andRespond(withSuccess("""
                        {"data":{"reportingDataResponse":{"row":[
                          {"metadata":{"searchTermText":"one"},
                           "granularity":[{"date":"2026-10-01","impressions":9,"taps":1,"localSpend":{"amount":"1.00","currency":"USD"}}]}
                        ]}},"pagination":{"totalResults":2,"startIndex":0,"itemsPerPage":1}}""", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1001/searchterms"))
                .andExpect(jsonPath("$.selector.pagination.offset").value(1))
                .andRespond(withSuccess("""
                        {"data":{"reportingDataResponse":{"row":[
                          {"metadata":{"searchTermText":"two"},
                           "granularity":[{"date":"2026-10-01","impressions":8,"taps":1,"localSpend":{"amount":"1.00","currency":"USD"}}]}
                        ]}},"pagination":{"totalResults":2,"startIndex":1,"itemsPerPage":1}}""", MediaType.APPLICATION_JSON));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        assertThat(value(points, "impressions", "searchterm:1001:one", "2026-10-01")).isEqualByComparingTo("9");
        assertThat(value(points, "impressions", "searchterm:1001:two", "2026-10-01")).isEqualByComparingTo("8");
        server.verify();
    }

    @Test
    void aSearchTermReportIsReadForEachKeywordCampaignAndNotForOtherCampaignTypes() {
        expectCampaigns("""
                {"data":[{"id":1001,"name":"Brand","adChannelType":"SEARCH"},
                         {"id":1003,"name":"Today","adChannelType":"DISPLAY"},
                         {"id":1004,"name":"Legacy"},
                         {"id":1002,"name":"Old","adChannelType":"SEARCH","endTime":"2025-06-01T00:00:00.000"}],
                 "pagination":{"totalResults":4}}""");
        expectEmptyKeywordsAndAds("1001");
        expectEmptyKeywordsAndAds("1004");
        expectSearchTerms("1001", EMPTY);
        expectSearchTerms("1004", EMPTY);

        connector.pullDaily(ctx(), FROM, TO);

        // no expectation for 1003 (DISPLAY) or 1002 (ended before the window): a request to them would fail the call
        server.verify();
    }

    @Test
    void aClientErrorOnOneCampaignsSearchTermsSkipsOnlyThatCampaign() {
        expectCampaigns("""
                {"data":[{"id":1001,"name":"Brand","adChannelType":"SEARCH"},
                         {"id":1005,"name":"Odd","adChannelType":"SEARCH"}],
                 "pagination":{"totalResults":2}}""");
        expectEmptyKeywordsAndAds("1001");
        expectSearchTerms("1001", SEARCH_TERMS);
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1005/keywords"))
                .andRespond(withSuccess("""
                        {"data":{"reportingDataResponse":{"row":[
                          {"metadata":{"keywordId":90,"keyword":"odd keyword"},
                           "granularity":[{"date":"2026-10-01","impressions":4,"taps":1,"tapInstalls":1,
                              "localSpend":{"amount":"0.50","currency":"USD"}}]}]}}}""", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1005/ads"))
                .andRespond(withSuccess(EMPTY, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1005/searchterms"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        ChannelMetricsPull pulled = connector.pull(ctx(), FROM, LocalDate.of(2026, 10, 1));

        // the other campaign's search terms survive, and so do the skipped campaign's keywords
        assertThat(value(pulled.points(), "impressions", "searchterm:1001:meal planner", "2026-10-01"))
                .isEqualByComparingTo("400");
        assertThat(value(pulled.points(), "impressions", "keyword:90:odd keyword", "2026-10-01")).isEqualByComparingTo("4");
        assertThat(pulled.points()).noneMatch(p -> p.dimensionKey().startsWith("searchterm:1005:"));
        assertThat(pulled.skippedCampaignIds()).containsExactly("1005");
    }

    @Test
    void aCampaignWhoseKeywordReportWasSkippedIsNotAskedForSearchTerms() {
        expectCampaigns("""
                {"data":[{"id":1005,"name":"Odd","adChannelType":"SEARCH"}],"pagination":{"totalResults":1}}""");
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1005/keywords"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        ChannelMetricsPull pulled = connector.pull(ctx(), FROM, LocalDate.of(2026, 10, 1));

        // no expectation for 1005's search terms: a request to them would fail the call
        assertThat(pulled.skippedCampaignIds()).containsExactly("1005");
    }

    @Test
    void aCompletePullSkipsNoCampaign() {
        expectCampaign1001();
        expectEmptyKeywordsAndAds("1001");
        expectSearchTerms("1001", SEARCH_TERMS);

        assertThat(connector.pull(ctx(), FROM, LocalDate.of(2026, 10, 1)).skippedCampaignIds()).isEmpty();
    }

    @Test
    void aRateLimitOrServerErrorOnSearchTermsStillFailsThePull() {
        expectCampaign1001();
        expectEmptyKeywordsAndAds("1001");
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1001/searchterms"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> connector.pullDaily(ctx(), FROM, TO))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class,
                        e -> assertThat(e.health()).isEqualTo(ConnectorHealth.DEGRADED));

        server.reset();
        expectCampaign1001();
        expectEmptyKeywordsAndAds("1001");
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1001/searchterms"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> connector.pullDaily(ctx(), FROM, TO))
                .isInstanceOf(ChannelMetricsSourceException.class);
    }

    @Test
    void refusedCredentialsOnSearchTermsNeedSetup() {
        expectCampaign1001();
        expectEmptyKeywordsAndAds("1001");
        server.expect(once(), requestTo(BASE + "/reports/campaigns/1001/searchterms"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> connector.pullDaily(ctx(), FROM, TO))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class,
                        e -> assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED));
    }

    @Test
    void thePullReportsContractVersionTwo() {
        expectCampaign1001();
        expectEmptyKeywordsAndAds("1001");
        expectSearchTerms("1001", EMPTY);

        assertThat(connector.pull(ctx(), FROM, LocalDate.of(2026, 10, 1)).contractVersion()).isEqualTo(2);
    }

    private static BigDecimal value(List<ChannelMetricPoint> points, String metric, String dimension, String day) {
        return points.stream()
                .filter(p -> p.metric().equals(metric) && p.dimensionKey().equals(dimension) && p.day().toString().equals(day))
                .map(ChannelMetricPoint::value).findFirst().orElseThrow();
    }

}
