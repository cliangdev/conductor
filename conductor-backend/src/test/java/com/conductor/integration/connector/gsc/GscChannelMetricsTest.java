package com.conductor.integration.connector.gsc;

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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GscChannelMetricsTest {

    private static final String URL = "https://www.googleapis.com/webmasters/v3/sites/sc-domain%3Aexample.com/searchAnalytics/query";
    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 3);

    private MockRestServiceServer server;
    private GscConnector connector;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        connector = new GscConnector(restTemplate);
    }

    private static ConnectionContext ctx() {
        return new ConnectionContext("proj", "gsc", "conn", "gsc-token", null, null,
                Map.of("siteUrl", "sc-domain:example.com"), null);
    }

    @Test
    void declaresItselfASearchSourceWithTheDocumentedWindows() {
        assertThat(connector.channel()).isEqualTo(MetricChannel.SEARCH);
        assertThat(connector.backfillDays()).isEqualTo(90);
        assertThat(connector.trailingDays()).isEqualTo(4);
    }

    @Test
    void mapsDailyTotalsAndPerDayTopQueries() {
        server.expect(once(), requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer gsc-token"))
                .andExpect(jsonPath("$.startDate").value("2026-10-01"))
                .andExpect(jsonPath("$.endDate").value("2026-10-03"))
                .andExpect(jsonPath("$.dimensions[0]").value("date"))
                .andRespond(withSuccess("""
                        {"rows":[{"keys":["2026-10-01"],"clicks":10,"impressions":200,"ctr":0.05,"position":4.5},
                                 {"keys":["2026-10-02"],"clicks":12,"impressions":240,"ctr":0.05,"position":5.0}]}""",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(URL))
                .andExpect(jsonPath("$.startDate").value("2026-10-01"))
                .andExpect(jsonPath("$.endDate").value("2026-10-01"))
                .andExpect(jsonPath("$.dimensions[1]").value("query"))
                .andExpect(jsonPath("$.rowLimit").value(100))
                .andRespond(withSuccess("""
                        {"rows":[{"keys":["2026-10-01","acme pricing"],"clicks":6,"impressions":90,"ctr":0.06,"position":2.0}]}""",
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(URL))
                .andExpect(jsonPath("$.startDate").value("2026-10-02"))
                .andRespond(withSuccess("{\"rows\":[]}", MediaType.APPLICATION_JSON));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), FROM, TO);

        assertThat(points).filteredOn(p -> p.metric().equals("clicks"))
                .extracting(ChannelMetricPoint::day, p -> p.value().intValue())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(FROM, 10),
                        org.assertj.core.groups.Tuple.tuple(FROM.plusDays(1), 12));
        assertThat(points).filteredOn(p -> p.metric().equals("position_x_impressions") && p.day().equals(FROM))
                .singleElement().satisfies(p -> assertThat(p.value()).isEqualByComparingTo("900"));
        assertThat(points).filteredOn(p -> p.metric().equals("query_clicks"))
                .singleElement().satisfies(p -> {
                    assertThat(p.dimensionKey()).isEqualTo("acme pricing");
                    assertThat(p.value()).isEqualByComparingTo("6");
                });
        assertThat(points).filteredOn(p -> p.metric().equals("query_impressions"))
                .singleElement().satisfies(p -> assertThat(p.value()).isEqualByComparingTo("90"));
        server.verify();
    }

    @Test
    void anUnknownPropertyNeedsSetup() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> connector.pullDaily(ctx(), FROM, TO))
                .isInstanceOf(ChannelMetricsSourceException.class)
                .extracting(e -> ((ChannelMetricsSourceException) e).health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
    }

    @Test
    void rateLimitIsTransient() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> connector.pullDaily(ctx(), FROM, TO))
                .extracting(e -> ((ChannelMetricsSourceException) e).health()).isEqualTo(ConnectorHealth.DEGRADED);
    }

    @Test
    void missingPropertyNeedsSetup() {
        ConnectionContext none = new ConnectionContext("proj", "gsc", "conn", "t", null, null, Map.of(), null);
        assertThatThrownBy(() -> connector.pullDaily(none, FROM, TO)).isInstanceOf(ChannelMetricsSourceException.class);
    }
}
