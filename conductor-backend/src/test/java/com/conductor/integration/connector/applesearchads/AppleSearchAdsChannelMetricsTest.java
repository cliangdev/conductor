package com.conductor.integration.connector.applesearchads;

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

import java.time.LocalDate;
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

class AppleSearchAdsChannelMetricsTest {

    private static final String URL = "https://api.searchads.apple.com/api/v5/reports/campaigns";

    private MockRestServiceServer server;
    private AppleSearchAdsConnector connector;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        AppleAdsTokenService tokens = mock(AppleAdsTokenService.class);
        when(tokens.accessToken(any(), any())).thenReturn("bearer-token");
        connector = new AppleSearchAdsConnector(restTemplate, tokens);
    }

    private static ConnectionContext ctx() {
        Map<String, Object> config = new HashMap<>();
        config.put("clientId", "CLIENT.abc");
        config.put("teamId", "TEAM.xyz");
        config.put("keyId", "KEY123");
        config.put("orgId", "999");
        return new ConnectionContext("proj", "apple-search-ads", "conn", "-----BEGIN PRIVATE KEY-----\nfake\n-----END PRIVATE KEY-----",
                null, null, config, null);
    }

    private static final String REPORT = """
            {"data":{"reportingDataResponse":{"row":[
              {"metadata":{"campaignId":1001,"campaignName":"Brand"},
               "granularity":[
                 {"date":"2026-10-01","impressions":1000,"taps":70,"installs":20,"localSpend":{"amount":"50.50","currency":"USD"}},
                 {"date":"2026-10-02","impressions":800,"taps":50,"installs":10,"localSpend":{"amount":"40.00","currency":"USD"}}]},
              {"metadata":{"campaignId":1002,"campaignName":"Discovery: US"},
               "granularity":[
                 {"date":"2026-10-01","impressions":500,"taps":30,"installs":5,"localSpend":{"amount":"20.00","currency":"USD"}}]}
            ]}}}""";

    @Test
    void declaresItselfAnAdsSourceWithTheDocumentedWindows() {
        assertThat(connector.channel()).isEqualTo(MetricChannel.ADS);
        assertThat(connector.trailingDays()).isEqualTo(3);
        assertThat(connector.backfillDays()).isEqualTo(90);
    }

    @Test
    void emitsTotalsAndPerCampaignRowsWithParseableKeys() {
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer bearer-token"))
                .andExpect(header("X-AP-Context", "orgId=999"))
                .andExpect(jsonPath("$.granularity").value("DAILY"))
                .andExpect(jsonPath("$.startTime").value("2026-10-01"))
                .andExpect(jsonPath("$.endTime").value("2026-10-02"))
                .andRespond(withSuccess(REPORT, MediaType.APPLICATION_JSON));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2));

        assertThat(value(points, "spend", "", "2026-10-01")).isEqualByComparingTo("70.50");
        assertThat(value(points, "impressions", "", "2026-10-01")).isEqualByComparingTo("1500");
        assertThat(value(points, "taps", "", "2026-10-01")).isEqualByComparingTo("100");
        assertThat(value(points, "installs", "", "2026-10-02")).isEqualByComparingTo("10");
        assertThat(value(points, "installs", "campaign:1001:Brand", "2026-10-01")).isEqualByComparingTo("20");
        assertThat(value(points, "spend", "campaign:1002:Discovery: US", "2026-10-01")).isEqualByComparingTo("20.00");
        server.verify();
    }

    @Test
    void windowsLongerThanNinetyDaysAreSplit() {
        server.expect(once(), requestTo(URL))
                .andExpect(jsonPath("$.startTime").value("2026-01-01"))
                .andExpect(jsonPath("$.endTime").value("2026-03-31"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(URL))
                .andExpect(jsonPath("$.startTime").value("2026-04-01"))
                .andExpect(jsonPath("$.endTime").value("2026-04-10"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(connector.pullDaily(ctx(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 4, 10))).isEmpty();
        server.verify();
    }

    @Test
    void refusedCredentialsNeedSetupAndRateLimitIsTransient() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> connector.pullDaily(ctx(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)))
                .extracting(e -> ((ChannelMetricsSourceException) e).health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);

        server.reset();
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> connector.pullDaily(ctx(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)))
                .extracting(e -> ((ChannelMetricsSourceException) e).health()).isEqualTo(ConnectorHealth.DEGRADED);
    }

    @Test
    void incompleteConfigNeedsSetup() {
        ConnectionContext none = new ConnectionContext("proj", "apple-search-ads", "conn", null, null, null, Map.of(), null);
        assertThatThrownBy(() -> connector.pullDaily(none, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)))
                .isInstanceOf(ChannelMetricsSourceException.class);
    }

    private static java.math.BigDecimal value(List<ChannelMetricPoint> points, String metric, String dimension, String day) {
        return points.stream()
                .filter(p -> p.metric().equals(metric) && p.dimensionKey().equals(dimension) && p.day().toString().equals(day))
                .map(ChannelMetricPoint::value).findFirst().orElseThrow();
    }
}
