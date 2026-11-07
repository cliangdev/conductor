package com.conductor.integration.connector.tiktok;

import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.IngestSink;
import com.conductor.integration.IngestSpec;
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
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TikTokChannelMetricsTest {

    private static final String URL =
            TikTokClient.API_BASE + "/user/info/?fields=follower_count,video_count,likes_count";
    private static final String RECONNECT_MESSAGE = "Reconnect TikTok to share follower counts";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    private MockRestServiceServer server;
    private TikTokConnector connector;
    private TikTokConnector flagOffConnector;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        connector = new TikTokConnector(new TikTokClient(restTemplate), null, true);
        flagOffConnector = new TikTokConnector(new TikTokClient(restTemplate), null, false);
    }

    private static ConnectionContext ctx() {
        return new ConnectionContext("proj", "tiktok", "conn", "tt-token", null, null, Map.of(), null);
    }

    private static String userInfo(String userJson) {
        return "{\"data\":{\"user\":" + userJson + "},\"error\":{\"code\":\"ok\",\"message\":\"\"}}";
    }

    @Test
    void declaresASocialSnapshotSource() {
        assertThat(connector.channel()).isEqualTo(MetricChannel.SOCIAL);
        assertThat(connector.backfillDays()).isZero();
        assertThat(connector.trailingDays()).isEqualTo(1);
        assertThat(connector.prunesMissingDimensions()).isFalse();
        assertThat(connector.snapshot("followers")).isTrue();
        assertThat(connector.snapshot("likes")).isTrue();
    }

    @Test
    void toolSpecDeclaresAChannelMetricsFeedOnTheChannelMetricsSink() {
        IngestSpec feed = connector.getToolSpec().ingest().stream()
                .filter(i -> i.id().equals("channel_metrics")).findFirst().orElseThrow();

        assertThat(feed.sink()).isEqualTo(IngestSink.CHANNEL_METRICS);
        assertThat(feed.defaultIntervalMinutes()).isEqualTo(360);
        assertThat(connector.getToolSpec().ingest()).extracting(IngestSpec::id)
                .containsExactly("post_metrics", "channel_metrics");
    }

    @Test
    void readsFollowersVideosAndLikesForTodayWhenTheScopeIsEnabledAndGranted() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tt-token"))
                .andRespond(withSuccess(userInfo("{\"follower_count\":321,\"video_count\":12,\"likes_count\":4567}"),
                        MediaType.APPLICATION_JSON));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), TODAY.minusDays(1), TODAY);

        assertThat(points).extracting(ChannelMetricPoint::metric, ChannelMetricPoint::dimensionKey,
                        ChannelMetricPoint::day, p -> p.value().longValue())
                .containsExactlyInAnyOrder(
                        tuple("followers", "tiktok", TODAY, 321L),
                        tuple("media_count", "tiktok", TODAY, 12L),
                        tuple("likes", "tiktok", TODAY, 4567L));
        server.verify();
    }

    @Test
    void withTheFlagOffNothingIsReadAndNothingIsWritten() {
        List<ChannelMetricPoint> points = flagOffConnector.pullDaily(ctx(), TODAY, TODAY);

        assertThat(points).isEmpty();
        server.verify();
    }

    @Test
    void withTheFlagOffAMissingTokenIsStillNotAFailure() {
        ConnectionContext noToken = new ConnectionContext("proj", "tiktok", "conn", null, null, null, Map.of(), null);

        assertThat(flagOffConnector.pullDaily(noToken, TODAY, TODAY)).isEmpty();
    }

    @Test
    void aMissingStatsScopeWritesNoPointsAndAsksTheCreatorToReconnect() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"scope_not_authorized\",\"message\":\"no scope\"}}"));

        assertThatThrownBy(() -> connector.pullDaily(ctx(), TODAY, TODAY))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class, e -> {
                    assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
                    assertThat(e.getMessage()).isEqualTo(RECONNECT_MESSAGE);
                });
        server.verify();
    }

    @Test
    void anOkStatusWithAScopeErrorEnvelopeAsksTheCreatorToReconnect() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"data\":{},\"error\":{\"code\":\"scope_not_authorized\",\"message\":\"x\"}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.pullDaily(ctx(), TODAY, TODAY))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class, e -> {
                    assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
                    assertThat(e.getMessage()).isEqualTo(RECONNECT_MESSAGE);
                });
    }

    @Test
    void aResponseWithNoStatFieldsAtAllIsTreatedAsNotGrantedNeverAsZero() {
        server.expect(requestTo(URL)).andRespond(withSuccess(userInfo("{}"), MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.pullDaily(ctx(), TODAY, TODAY))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class, e -> {
                    assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
                    assertThat(e.getMessage()).isEqualTo(RECONNECT_MESSAGE);
                });
    }

    @Test
    void aPermanentErrorThatIsNotAScopeProblemDoesNotClaimTheScopeIsMissing() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"data\":{},\"error\":{\"code\":\"invalid_params\",\"message\":\"bad fields\"}}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.pullDaily(ctx(), TODAY, TODAY))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class, e -> {
                    assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
                    assertThat(e.getMessage()).contains("invalid_params").isNotEqualTo(RECONNECT_MESSAGE);
                });
    }

    @Test
    void aMissingTokenAsksForAReconnectWhenTheFlagIsOn() {
        ConnectionContext noToken = new ConnectionContext("proj", "tiktok", "conn", " ", null, null, Map.of(), null);

        assertThatThrownBy(() -> connector.pullDaily(noToken, TODAY, TODAY))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class,
                        e -> assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED));
    }

    @Test
    void aServerErrorIsDegraded() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> connector.pullDaily(ctx(), TODAY, TODAY))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class,
                        e -> assertThat(e.health()).isEqualTo(ConnectorHealth.DEGRADED));
    }

    @Test
    void anAbsentFieldWritesNoPointWhileAReportedZeroIsKept() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                userInfo("{\"follower_count\":5,\"likes_count\":0}"), MediaType.APPLICATION_JSON));

        assertThat(connector.pullDaily(ctx(), TODAY, TODAY))
                .extracting(ChannelMetricPoint::metric, p -> p.value().longValue())
                .containsExactlyInAnyOrder(tuple("followers", 5L), tuple("likes", 0L));
    }

    @Test
    void aNonNumericFieldWritesNoPoint() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                userInfo("{\"follower_count\":\"many\",\"video_count\":3}"), MediaType.APPLICATION_JSON));

        assertThat(connector.pullDaily(ctx(), TODAY, TODAY)).extracting(ChannelMetricPoint::metric)
                .containsExactly("media_count");
    }
}
