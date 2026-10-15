package com.conductor.integration.connector.tiktok;

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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TikTokChannelMetricsTest {

    private static final String URL = TikTokClient.API_BASE + "/user/info/?fields=follower_count,video_count";
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    private MockRestServiceServer server;
    private TikTokConnector connector;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        connector = new TikTokConnector(new TikTokClient(restTemplate));
    }

    private static ConnectionContext ctx() {
        return new ConnectionContext("proj", "tiktok", "conn", "tt-token", null, null, Map.of(), null);
    }

    @Test
    void declaresASocialSnapshotSource() {
        assertThat(connector.channel()).isEqualTo(MetricChannel.SOCIAL);
        assertThat(connector.backfillDays()).isZero();
        assertThat(connector.trailingDays()).isEqualTo(1);
        assertThat(connector.prunesMissingDimensions()).isFalse();
    }

    @Test
    void readsFollowersAndVideoCountForToday() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer tt-token"))
                .andRespond(withSuccess("{\"data\":{\"user\":{\"follower_count\":321,\"video_count\":12}},"
                        + "\"error\":{\"code\":\"ok\",\"message\":\"\"}}", MediaType.APPLICATION_JSON));

        List<ChannelMetricPoint> points = connector.pullDaily(ctx(), TODAY.minusDays(1), TODAY);

        assertThat(points).extracting(ChannelMetricPoint::metric, ChannelMetricPoint::dimensionKey, ChannelMetricPoint::day)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("followers", "tiktok", TODAY),
                        org.assertj.core.groups.Tuple.tuple("media_count", "tiktok", TODAY));
        assertThat(points).filteredOn(p -> p.metric().equals("followers"))
                .extracting(p -> p.value().intValue()).containsExactly(321);
        server.verify();
    }

    @Test
    void aMissingStatsScopeIsSetupRequiredWithAClearMessage() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":{\"code\":\"scope_not_authorized\",\"message\":\"no scope\"}}"));

        assertThatThrownBy(() -> connector.pullDaily(ctx(), TODAY, TODAY))
                .isInstanceOfSatisfying(ChannelMetricsSourceException.class, e -> {
                    assertThat(e.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
                    assertThat(e.getMessage()).contains("user.info.stats").contains("scope_not_authorized");
                });
    }

    @Test
    void anOkStatusWithAnErrorEnvelopeIsSetupRequired() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"data\":{},\"error\":{\"code\":\"scope_not_authorized\",\"message\":\"x\"}}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> connector.pullDaily(ctx(), TODAY, TODAY))
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
    void skipsAFieldTikTokOmits() {
        server.expect(requestTo(URL)).andRespond(withSuccess(
                "{\"data\":{\"user\":{\"follower_count\":5}},\"error\":{\"code\":\"ok\"}}", MediaType.APPLICATION_JSON));

        assertThat(connector.pullDaily(ctx(), TODAY, TODAY)).extracting(ChannelMetricPoint::metric)
                .containsExactly("followers");
    }
}
