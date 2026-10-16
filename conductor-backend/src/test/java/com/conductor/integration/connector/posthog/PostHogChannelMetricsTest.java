package com.conductor.integration.connector.posthog;

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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PostHogChannelMetricsTest {

    private static final LocalDate FROM = LocalDate.of(2026, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 10, 2);

    private MockRestServiceServer server;
    private PostHogConnector connector;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(false).build();
        connector = new PostHogConnector(restTemplate);
    }

    private static ConnectionContext ctx(Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>(extra);
        config.putIfAbsent("projectId", "42");
        return new ConnectionContext("proj", "posthog", "conn", "ph-key", null, null, config, null);
    }

    private static final String TOTALS_JSON = """
            {"columns":["day","visitors","sessions","pageviews"],
             "results":[["2026-10-01",100,130,310],["2026-10-02",90,120,280]]}""";
    private static final String TOTALS_WITH_CONVERSIONS_JSON = """
            {"results":[["2026-10-01",100,130,310,7]]}""";
    private static final String REFERRERS_JSON = """
            {"results":[["2026-10-01","google.com",60],["2026-10-01","(direct)",40],["2026-10-02","reddit.com",15]]}""";

    @Test
    void declaresItselfAWebSourceWithTheDocumentedWindows() {
        assertThat(connector.channel()).isEqualTo(MetricChannel.WEB);
        assertThat(connector.backfillDays()).isEqualTo(90);
        assertThat(connector.trailingDays()).isEqualTo(2);
    }

    @Test
    void mapsDailyTotalsAndReferrerDimensionRows() {
        server.expect(requestTo("https://app.posthog.com/api/projects/42/query/"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer ph-key"))
                .andExpect(jsonPath("$.query.kind").value("HogQLQuery"))
                .andExpect(jsonPath("$.query.query").value(not(containsString("conversion_clicks"))))
                .andExpect(jsonPath("$.query.values.fromDay").value("2026-10-01"))
                .andExpect(jsonPath("$.query.values.toDay").value("2026-10-03"))
                .andRespond(withSuccess(TOTALS_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://app.posthog.com/api/projects/42/query/"))
                .andExpect(jsonPath("$.query.query").value(containsString("$referring_domain")))
                .andRespond(withSuccess(REFERRERS_JSON, MediaType.APPLICATION_JSON));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(Map.of()), FROM, TO);

        assertThat(points).filteredOn(p -> p.metric().equals("visitors"))
                .extracting(ChannelMetricPoint::day, p -> p.value().intValue())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(FROM, 100), org.assertj.core.groups.Tuple.tuple(TO, 90));
        assertThat(points).extracting(ChannelMetricPoint::metric).doesNotContain("conversion_clicks");
        assertThat(points).filteredOn(p -> p.metric().equals("sessions_by_referrer"))
                .extracting(ChannelMetricPoint::dimensionKey)
                .containsExactlyInAnyOrder("google.com", "(direct)", "reddit.com");
        server.verify();
    }

    @Test
    void referrersAreCappedAtFiftyPerDayKeepingTheBiggest() {
        StringBuilder rows = new StringBuilder("{\"results\":[");
        for (int i = 0; i < 60; i++) {
            rows.append(i == 0 ? "" : ",").append("[\"2026-10-01\",\"site").append(i).append(".com\",").append(i + 1).append("]");
        }
        rows.append("]}");
        server.expect(requestTo("https://app.posthog.com/api/projects/42/query/"))
                .andRespond(withSuccess(TOTALS_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://app.posthog.com/api/projects/42/query/"))
                .andRespond(withSuccess(rows.toString(), MediaType.APPLICATION_JSON));

        List<ChannelMetricPoint> referrers = connector.pullDaily(ctx(Map.of()), FROM, TO).stream()
                .filter(p -> p.metric().equals("sessions_by_referrer")).toList();

        assertThat(referrers).hasSize(50);
        assertThat(referrers).extracting(ChannelMetricPoint::dimensionKey).doesNotContain("site0.com", "site9.com");
        assertThat(referrers).extracting(ChannelMetricPoint::dimensionKey).contains("site59.com");
    }

    @Test
    void nullReferrerBecomesDirect() {
        server.expect(requestTo("https://app.posthog.com/api/projects/42/query/"))
                .andRespond(withSuccess(TOTALS_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://app.posthog.com/api/projects/42/query/"))
                .andRespond(withSuccess("{\"results\":[[\"2026-10-01\",null,5]]}", MediaType.APPLICATION_JSON));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(Map.of()), FROM, TO);

        assertThat(points).filteredOn(p -> p.metric().equals("sessions_by_referrer"))
                .singleElement().extracting(ChannelMetricPoint::dimensionKey).isEqualTo("(direct)");
    }

    @Test
    void filterAndConversionEventTravelAsPlaceholdersNeverInTheSql() {
        server.expect(requestTo("https://app.posthog.com/api/projects/42/query/"))
                .andExpect(jsonPath("$.query.query").value(containsString("{filterProperty}")))
                .andExpect(jsonPath("$.query.query").value(containsString("{conversionEvent}")))
                .andExpect(jsonPath("$.query.query").value(not(containsString("web"))))
                .andExpect(jsonPath("$.query.query").value(not(containsString("signup_click"))))
                .andExpect(jsonPath("$.query.values.filterProperty").value("$lib"))
                .andExpect(jsonPath("$.query.values.filterValue").value("web"))
                .andExpect(jsonPath("$.query.values.conversionEvent").value("signup_click"))
                .andRespond(withSuccess(TOTALS_WITH_CONVERSIONS_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://app.posthog.com/api/projects/42/query/"))
                .andExpect(jsonPath("$.query.query").value(not(containsString("{conversionEvent}"))))
                .andExpect(jsonPath("$.query.values.conversionEvent").doesNotExist())
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        List<ChannelMetricPoint> points = connector.pullDaily(
                ctx(Map.of("eventFilter", "$lib = 'web'", "conversionEvent", "signup_click")), FROM, TO);

        assertThat(points).filteredOn(p -> p.metric().equals("conversion_clicks"))
                .singleElement().extracting(p -> p.value().intValue()).isEqualTo(7);
        server.verify();
    }

    @Test
    void injectionInTheFilterIsRejectedBeforeAnyRequest() {
        for (String bad : List.of("x' OR 1=1 --", "$lib = 'web' OR 1=1", "$lib = 'a'; DROP TABLE events",
                "$lib = 'it''s'", "$lib = 'back\\slash'", "a b = 'c'", "$lib != 'web'", "= 'x'")) {
            assertThatThrownBy(() -> connector.pullDaily(ctx(Map.of("eventFilter", bad)), FROM, TO))
                    .as(bad).isInstanceOf(ChannelMetricsSourceException.class)
                    .extracting(e -> ((ChannelMetricsSourceException) e).health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        }
        server.verify();
    }

    @Test
    void injectionInTheConversionEventIsRejectedBeforeAnyRequest() {
        for (String bad : List.of("x' OR 1=1 --", "click'); DROP", "with space", "a".repeat(101), "quote\"")) {
            assertThatThrownBy(() -> connector.pullDaily(ctx(Map.of("conversionEvent", bad)), FROM, TO))
                    .as(bad).isInstanceOf(ChannelMetricsSourceException.class);
        }
        server.verify();
    }

    @Test
    void hostSettingSelectsRegionOrSelfHostedAndRejectsAnythingElse() {
        assertThat(PostHogSettings.resolveHost(null)).isEqualTo("https://app.posthog.com");
        assertThat(PostHogSettings.resolveHost("eu")).isEqualTo("https://eu.posthog.com");
        assertThat(PostHogSettings.resolveHost("US")).isEqualTo("https://us.posthog.com");
        assertThat(PostHogSettings.resolveHost("https://ph.example.com/")).isEqualTo("https://ph.example.com");
        assertThat(PostHogSettings.resolveHost("https://ph.example.com:8443")).isEqualTo("https://ph.example.com:8443");
        for (String bad : List.of("http://ph.example.com", "ftp://x", "https://user:pw@ph.example.com",
                "https://ph.example.com/some/path", "https://ph.example.com?x=1", "not a url")) {
            assertThatThrownBy(() -> PostHogSettings.resolveHost(bad)).as(bad)
                    .isInstanceOf(ChannelMetricsSourceException.class);
        }
    }

    @Test
    void aSelfHostedHostIsUsedForTheQuery() {
        server.expect(requestTo("https://ph.example.com/api/projects/42/query/"))
                .andRespond(withSuccess(TOTALS_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://ph.example.com/api/projects/42/query/"))
                .andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

        connector.pullDaily(ctx(Map.of("host", "https://ph.example.com")), FROM, TO);

        server.verify();
    }

    @Test
    void missingProjectIdNeedsSetup() {
        ConnectionContext noProject = new ConnectionContext("proj", "posthog", "conn", "k", null, null, Map.of(), null);
        assertThatThrownBy(() -> connector.pullDaily(noProject, FROM, TO))
                .isInstanceOf(ChannelMetricsSourceException.class);
    }

    @Test
    void authFailureNeedsSetupAndRateLimitIsTransient() {
        server.expect(requestTo("https://app.posthog.com/api/projects/42/query/"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        assertThatThrownBy(() -> connector.pullDaily(ctx(Map.of()), FROM, TO))
                .extracting(e -> ((ChannelMetricsSourceException) e).health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);

        server.reset();
        server.expect(requestTo("https://app.posthog.com/api/projects/42/query/"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertThatThrownBy(() -> connector.pullDaily(ctx(Map.of()), FROM, TO))
                .extracting(e -> ((ChannelMetricsSourceException) e).health()).isEqualTo(ConnectorHealth.DEGRADED);
    }
}
