package com.conductor.integration.connector.meta;

import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.MetricChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MetaChannelMetricsTest {

    private static final String GRAPH = "https://graph.facebook.com/v21.0";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);
    private static final LocalDate FROM = TODAY.minusDays(28);

    private MockRestServiceServer server;
    private MetaConnector connector;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        connector = new MetaConnector(new MetaGraphClient(restTemplate));
    }

    private static ConnectionContext ctx(Map<String, Object> config) {
        return new ConnectionContext("proj", "meta", "conn", "page-token", null, null, config, null);
    }

    private static ConnectionContext both() {
        return ctx(Map.of("pageId", "111", "instagramBusinessAccountId", "222"));
    }

    private static String fbFields() { return GRAPH + "/111?fields=fan_count,followers_count"; }
    private static String fbPosts() { return GRAPH + "/111/published_posts?summary=total_count&limit=0"; }
    private static String igFields() { return GRAPH + "/222?fields=followers_count,media_count"; }

    private static String igInsights() {
        return GRAPH + "/222/insights?metric=website_clicks&period=day&since=" + FROM + "&until=" + TODAY.plusDays(1);
    }

    private static List<String> keys(List<ChannelMetricPoint> points) {
        return points.stream().map(p -> p.metric() + "/" + p.dimensionKey() + "/" + p.day() + "=" + p.value().stripTrailingZeros().toPlainString())
                .toList();
    }

    @Test
    void declaresASocialSnapshotSource() {
        assertThat(connector.channel()).isEqualTo(MetricChannel.SOCIAL);
        assertThat(connector.backfillDays()).isEqualTo(28);
        assertThat(connector.trailingDays()).isEqualTo(1);
        assertThat(connector.prunesMissingDimensions()).isFalse();
        assertThat(connector.snapshot("followers")).isTrue();
        assertThat(connector.snapshot("media_count")).isTrue();
        assertThat(connector.snapshot("website_clicks")).isFalse();
    }

    @Test
    void readsFollowersMediaCountsAndDailyWebsiteClicks() {
        server.expect(requestTo(fbFields())).andExpect(header("Authorization", "Bearer page-token"))
                .andRespond(withSuccess("{\"fan_count\":900,\"followers_count\":950,\"id\":\"111\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(fbPosts()))
                .andRespond(withSuccess("{\"data\":[],\"summary\":{\"total_count\":64}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(igFields()))
                .andRespond(withSuccess("{\"followers_count\":2400,\"media_count\":112,\"id\":\"222\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(igInsights()))
                .andRespond(withSuccess("""
                        {"data":[{"name":"website_clicks","period":"day","values":[
                          {"value":7,"end_time":"2026-10-13T07:00:00+0000"},
                          {"value":9,"end_time":"2026-10-14T07:00:00+0000"}]}]}""", MediaType.APPLICATION_JSON));

        List<ChannelMetricPoint> points = connector.pullDaily(both(), FROM, TODAY);

        assertThat(keys(points)).containsExactlyInAnyOrder(
                "followers/facebook/2026-10-14=950",
                "media_count/facebook/2026-10-14=64",
                "followers/instagram/2026-10-14=2400",
                "media_count/instagram/2026-10-14=112",
                "website_clicks/instagram/2026-10-12=7",
                "website_clicks/instagram/2026-10-13=9");
        assertThat(points).noneMatch(p -> p.dimensionKey().isEmpty());
        server.verify();
    }

    @Test
    void fallsBackToFanCountWhenFollowersCountIsAbsent() {
        server.expect(requestTo(fbFields()))
                .andRespond(withSuccess("{\"fan_count\":900,\"id\":\"111\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(fbPosts())).andRespond(withStatus(HttpStatus.FORBIDDEN));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(Map.of("pageId", "111")), FROM, TODAY);

        assertThat(keys(points)).containsExactly("followers/facebook/2026-10-14=900");
    }

    @Test
    void aMissingInstagramPermissionSkipsInstagramButKeepsFacebook() {
        server.expect(requestTo(fbFields()))
                .andRespond(withSuccess("{\"fan_count\":900,\"followers_count\":950}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(fbPosts()))
                .andRespond(withSuccess("{\"summary\":{\"total_count\":64}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(igFields())).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"(#10) Requires instagram_basic permission\",\"code\":10}}"));

        List<ChannelMetricPoint> points = connector.pullDaily(both(), FROM, TODAY);

        assertThat(points).extracting(ChannelMetricPoint::dimensionKey).containsOnly("facebook");
        assertThat(points).hasSize(2);
    }

    @Test
    void aDeprecatedWebsiteClicksMetricIsSkippedWithoutFailingTheFeed() {
        server.expect(requestTo(igFields()))
                .andRespond(withSuccess("{\"followers_count\":2400,\"media_count\":112}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(igInsights())).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"message\":\"(#100) The metric website_clicks is deprecated for v21.0\",\"code\":100}}"));

        List<ChannelMetricPoint> points = connector.pullDaily(
                ctx(Map.of("instagramBusinessAccountId", "222")), FROM, TODAY);

        assertThat(keys(points)).containsExactlyInAnyOrder("followers/instagram/2026-10-14=2400",
                "media_count/instagram/2026-10-14=112");
        server.verify();
    }

    @Test
    void totalRefusalIsSetupRequired() {
        server.expect(requestTo(fbFields())).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo(igFields())).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> connector.pullDaily(both(), FROM, TODAY))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class,
                        e -> assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED));
    }

    @Test
    void aRateLimitIsDegradedNotSetupRequired() {
        server.expect(requestTo(fbFields())).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> connector.pullDaily(ctx(Map.of("pageId", "111")), FROM, TODAY))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class,
                        e -> assertThat(e.health()).isEqualTo(ConnectorHealth.DEGRADED));
    }

    @Test
    void aServerErrorIsDegraded() {
        server.expect(requestTo(fbFields())).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> connector.pullDaily(ctx(Map.of("pageId", "111")), FROM, TODAY))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class,
                        e -> assertThat(e.health()).isEqualTo(ConnectorHealth.DEGRADED));
    }

    @Test
    void aConnectionWithoutAPageNeedsSetup() {
        assertThatThrownBy(() -> connector.pullDaily(ctx(Map.of()), FROM, TODAY))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class,
                        e -> assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED));
    }
}
