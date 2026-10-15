package com.conductor.integration.connector.youtube;

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

class YouTubeChannelMetricsTest {

    private static final String BY_ID = YouTubeDataClient.API_BASE + "/channels?part=statistics&id=UC123";
    private static final String MINE = YouTubeDataClient.API_BASE + "/channels?part=statistics&mine=true";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    private MockRestServiceServer server;
    private YouTubeConnector connector;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        connector = new YouTubeConnector(new YouTubeDataClient(restTemplate));
    }

    private static ConnectionContext ctx(Map<String, Object> config) {
        return new ConnectionContext("proj", "youtube", "conn", "yt-token", null, null, config, null);
    }

    @Test
    void declaresASocialSnapshotSource() {
        assertThat(connector.channel()).isEqualTo(MetricChannel.SOCIAL);
        assertThat(connector.backfillDays()).isZero();
        assertThat(connector.trailingDays()).isEqualTo(1);
        assertThat(connector.prunesMissingDimensions()).isFalse();
        assertThat(connector.snapshot("followers")).isTrue();
        assertThat(connector.snapshot("channel_views")).isTrue();
    }

    @Test
    void readsSubscribersVideosAndViewsOfTheStoredChannel() {
        server.expect(requestTo(BY_ID)).andExpect(header("Authorization", "Bearer yt-token"))
                .andRespond(withSuccess("""
                        {"items":[{"id":"UC123","statistics":{"viewCount":"42000","subscriberCount":"950",
                          "hiddenSubscriberCount":false,"videoCount":"31"}}]}""", MediaType.APPLICATION_JSON));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(Map.of("channelId", "UC123")), TODAY, TODAY);

        assertThat(points).extracting(ChannelMetricPoint::metric).containsExactlyInAnyOrder(
                "followers", "media_count", "channel_views");
        assertThat(points).allMatch(p -> p.dimensionKey().equals("youtube") && p.day().equals(TODAY));
        assertThat(points).filteredOn(p -> p.metric().equals("channel_views"))
                .extracting(p -> p.value().intValue()).containsExactly(42000);
        server.verify();
    }

    @Test
    void fallsBackToMineWithoutAStoredChannelId() {
        server.expect(requestTo(MINE)).andRespond(withSuccess(
                "{\"items\":[{\"id\":\"UC9\",\"statistics\":{\"viewCount\":\"5\",\"subscriberCount\":\"2\",\"videoCount\":\"1\"}}]}",
                MediaType.APPLICATION_JSON));

        assertThat(connector.pullDaily(ctx(Map.of()), TODAY, TODAY)).hasSize(3);
    }

    @Test
    void hiddenSubscriberCountSkipsFollowers() {
        server.expect(requestTo(BY_ID)).andRespond(withSuccess(
                "{\"items\":[{\"statistics\":{\"viewCount\":\"5\",\"subscriberCount\":\"0\",\"hiddenSubscriberCount\":true,\"videoCount\":\"1\"}}]}",
                MediaType.APPLICATION_JSON));

        assertThat(connector.pullDaily(ctx(Map.of("channelId", "UC123")), TODAY, TODAY))
                .extracting(ChannelMetricPoint::metric).containsExactlyInAnyOrder("media_count", "channel_views");
    }

    @Test
    void aRefusedReadIsSetupRequired() {
        server.expect(requestTo(BY_ID)).andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> connector.pullDaily(ctx(Map.of("channelId", "UC123")), TODAY, TODAY))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class,
                        e -> assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED));
    }

    @Test
    void anAccountWithoutAChannelIsSetupRequired() {
        server.expect(requestTo(MINE)).andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.pullDaily(ctx(Map.of()), TODAY, TODAY))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class,
                        e -> assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED));
    }

    @Test
    void aServerErrorIsDegraded() {
        server.expect(requestTo(BY_ID)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> connector.pullDaily(ctx(Map.of("channelId", "UC123")), TODAY, TODAY))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class,
                        e -> assertThat(e.health()).isEqualTo(ConnectorHealth.DEGRADED));
    }
}
