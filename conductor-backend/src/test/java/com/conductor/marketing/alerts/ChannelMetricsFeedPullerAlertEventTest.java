package com.conductor.marketing.alerts;

import com.conductor.entity.Connection;
import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsSource;
import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.Connector;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.IngestBatch;
import com.conductor.integration.IngestMode;
import com.conductor.integration.IngestRequest;
import com.conductor.integration.IngestSink;
import com.conductor.integration.IngestSpec;
import com.conductor.integration.MetricChannel;
import com.conductor.integration.OAuthReauthRequiredException;
import com.conductor.integration.ingest.ChannelDimensionStateStore;
import com.conductor.integration.ingest.ChannelMetricDailyStore;
import com.conductor.integration.ingest.ChannelMetricDefinitionStore;
import com.conductor.integration.ingest.ChannelMetricsFeedPuller;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.service.ConnectionHealthService;
import com.conductor.service.ConnectionService;
import com.conductor.service.OAuthFlowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * The puller tells the alert evaluator that a connection's data changed, and only when a pull really did finish:
 * not on a degraded or sign-in-expired pull, not for a connector with no channel metrics, and not part-way through
 * a chunked walk (an unfinished backfill or re-walk is half old, half new data, which would read as a mismatch).
 */
class ChannelMetricsFeedPullerAlertEventTest {

    private ConnectorRegistry registry;
    private OAuthFlowService oauth;
    private ApplicationEventPublisher publisher;
    private ChannelMetricsSource source;
    private ChannelMetricsFeedPuller puller;
    private Connection connection;
    private ConnectorFeed feed;
    private IngestSpec spec;

    @BeforeEach
    void setUp() {
        registry = mock(ConnectorRegistry.class);
        ConnectionService connectionService = mock(ConnectionService.class);
        oauth = mock(OAuthFlowService.class);
        publisher = mock(ApplicationEventPublisher.class);
        when(oauth.withFreshToken(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        Connector connector = mock(Connector.class, withSettings().extraInterfaces(ChannelMetricsSource.class));
        source = (ChannelMetricsSource) connector;
        when(source.channel()).thenReturn(MetricChannel.SEARCH);
        when(source.backfillDays()).thenReturn(90);
        when(source.trailingDays()).thenReturn(4);
        when(source.prunesMissingDimensions()).thenReturn(true);
        when(source.maxDaysPerPull()).thenReturn(Integer.MAX_VALUE);
        when(source.pull(any(), any(), any())).thenCallRealMethod();
        when(source.trailingDays(any())).thenCallRealMethod();
        when(registry.getById("gsc")).thenReturn(Optional.of(connector));

        connection = new Connection();
        connection.setId("conn-1");
        connection.setProjectId("proj-1");
        connection.setConnectorId("gsc");
        when(connectionService.toContext(connection)).thenReturn(
                new ConnectionContext("proj-1", "gsc", "conn-1", "tok", null, null, java.util.Map.of(), null));

        feed = new ConnectorFeed();
        feed.setId("feed-1");
        feed.setProjectId("proj-1");
        feed.setConnectionId("conn-1");
        feed.setConnectorId("gsc");
        feed.setIngestId("channel_metrics");
        feed.setMode(IngestMode.SNAPSHOT);
        feed.setIntervalMinutes(360);
        spec = new IngestSpec("channel_metrics", "l", "d", IngestMode.SNAPSHOT, null, null, 360, null, null, null,
                null, IngestSink.CHANNEL_METRICS, null);

        puller = new ChannelMetricsFeedPuller(registry, connectionService, mock(ChannelMetricDailyStore.class),
                mock(ChannelMetricDefinitionStore.class), mock(ChannelDimensionStateStore.class), new ObjectMapper(),
                oauth, mock(ConnectionHealthService.class), publisher);
    }

    private IngestBatch pull(String cursor) {
        return puller.pull(feed, spec, connection, new IngestRequest("channel_metrics", null, cursor, 500));
    }

    private static String yesterdayCursor() {
        return "{\"lastDay\":\"" + LocalDate.now(ZoneOffset.UTC).minusDays(1) + "\"}";
    }

    @Test
    void aFinishedPullPublishesTheProjectChannelAndConnection() {
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of(ChannelMetricPoint.total("clicks",
                LocalDate.now(ZoneOffset.UTC).minusDays(1), 4)));

        IngestBatch batch = pull(yesterdayCursor());

        assertThat(batch.health()).isEqualTo(ConnectorHealth.HEALTHY);
        verify(publisher).publishEvent(new ChannelMetricsPulledEvent("proj-1", "SEARCH", "conn-1"));
    }

    @Test
    void aFirstEverPullThatReachesTodayPublishes() {
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of());

        pull(null);

        verify(publisher).publishEvent(new ChannelMetricsPulledEvent("proj-1", "SEARCH", "conn-1"));
    }

    @Test
    void aDegradedSourcePublishesNothing() {
        when(source.pullDaily(any(), any(), any())).thenThrow(ChannelMetricsSourceException.degraded("timed out"));

        IngestBatch batch = pull(yesterdayCursor());

        assertThat(batch.health()).isEqualTo(ConnectorHealth.DEGRADED);
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void aSourceThatNeedsSetupPublishesNothing() {
        when(source.pullDaily(any(), any(), any())).thenThrow(ChannelMetricsSourceException.setupRequired("no key"));

        IngestBatch batch = pull(yesterdayCursor());

        assertThat(batch.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void anExpiredSignInPublishesNothing() {
        when(oauth.withFreshToken(any(), any())).thenThrow(new OAuthReauthRequiredException("expired", null));

        IngestBatch batch = pull(yesterdayCursor());

        assertThat(batch.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void aConnectorWithNoChannelMetricsPublishesNothing() {
        connection.setConnectorId("plain-connector");
        Connector plain = mock(Connector.class);
        when(registry.getById("plain-connector")).thenReturn(Optional.of(plain));

        IngestBatch batch = pull(yesterdayCursor());

        assertThat(batch.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        verify(publisher, never()).publishEvent(any(Object.class));
    }

    @Test
    void aChunkThatStopsShortOfTodayPublishesNothingUntilTheWalkFinishes() {
        when(source.maxDaysPerPull()).thenReturn(30);
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of());

        IngestBatch firstChunk = pull(null);

        assertThat(firstChunk.hasMore()).isTrue();
        verify(publisher, never()).publishEvent(any(Object.class));

        IngestBatch last = pull("{\"lastDay\":\"" + LocalDate.now(ZoneOffset.UTC).minusDays(1) + "\",\"resumeFrom\":\""
                + LocalDate.now(ZoneOffset.UTC).minusDays(1) + "\"}");

        assertThat(last.hasMore()).isFalse();
        verify(publisher).publishEvent(new ChannelMetricsPulledEvent("proj-1", "SEARCH", "conn-1"));
    }

    @Test
    void aPublisherThatThrowsNeverFailsThePull() {
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of());
        doThrow(new IllegalStateException("listener blew up")).when(publisher).publishEvent(any(Object.class));

        assertThatNoException().isThrownBy(() -> {
            IngestBatch batch = pull(yesterdayCursor());
            assertThat(batch.health()).isEqualTo(ConnectorHealth.HEALTHY);
        });
    }

    @Test
    void thePullerStillWorksWhereNoPublisherIsWired() {
        ChannelMetricsFeedPuller plain = new ChannelMetricsFeedPuller(registry, mock(ConnectionService.class),
                mock(ChannelMetricDailyStore.class), mock(ChannelMetricDefinitionStore.class),
                mock(ChannelDimensionStateStore.class), new ObjectMapper(), oauth, mock(ConnectionHealthService.class));
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of());

        IngestBatch batch = plain.pull(feed, spec, connection, new IngestRequest("channel_metrics", null,
                yesterdayCursor(), 500));

        assertThat(batch.health()).isEqualTo(ConnectorHealth.HEALTHY);
    }
}
