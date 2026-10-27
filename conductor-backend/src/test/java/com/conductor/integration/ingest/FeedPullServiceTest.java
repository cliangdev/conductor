package com.conductor.integration.ingest;

import com.conductor.entity.Connection;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.Connector;
import com.conductor.integration.ConnectorCategory;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.ConnectorSpec;
import com.conductor.integration.IngestBatch;
import com.conductor.integration.IngestConnector;
import com.conductor.integration.IngestItem;
import com.conductor.integration.IngestMode;
import com.conductor.integration.IngestSpec;
import com.conductor.integration.IntegrationToolSpec;
import com.conductor.integration.ingest.digest.MetricsDigestService;
import com.conductor.service.ConnectionService;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.RETURNS_DEFAULTS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeedPullServiceTest {

    private final ConnectorFeedRepository feedRepository = mock(ConnectorFeedRepository.class);
    private final ConnectionService connectionService = mock(ConnectionService.class);
    private final ConnectorRegistry connectorRegistry = mock(ConnectorRegistry.class);
    private final SnapshotIngestAdapter snapshotIngestAdapter = mock(SnapshotIngestAdapter.class);
    private final DigestSink digestSink = mock(DigestSink.class);
    private final MetricsDigestService metricsDigestService = mock(MetricsDigestService.class);

    private final com.conductor.service.publish.PostMetricsFeedPuller postMetricsFeedPuller =
            mock(com.conductor.service.publish.PostMetricsFeedPuller.class);

    private final FeedPullService service = new FeedPullService(
            feedRepository, connectionService, connectorRegistry, snapshotIngestAdapter, digestSink,
            metricsDigestService, postMetricsFeedPuller);

    @org.junit.jupiter.api.Test
    void aPostMetricsFeedIsPulledByThePullerAndNeverReachesASink() {
        ConnectorFeed feed = feed();
        feed.setConnectorId("meta");
        feed.setIngestId("post_metrics");
        com.conductor.entity.Connection conn = new com.conductor.entity.Connection();
        conn.setId("conn-1");
        conn.setProjectId("proj-1");
        conn.setConnectorId("meta");
        org.mockito.Mockito.when(connectionService.getById("conn-1")).thenReturn(java.util.Optional.of(conn));
        org.mockito.Mockito.when(connectionService.toContext(conn)).thenReturn(
                new com.conductor.integration.ConnectionContext("proj-1", "meta", "conn-1", "tok", null, null,
                        java.util.Map.of(), null));
        com.conductor.integration.IngestSpec spec = new com.conductor.integration.IngestSpec("post_metrics", "l", "d",
                IngestMode.SNAPSHOT, null, null, 360, null, null, null, null,
                com.conductor.integration.IngestSink.POST_METRICS, null);
        com.conductor.integration.Connector connector = mock(com.conductor.integration.Connector.class);
        com.conductor.integration.IntegrationToolSpec toolSpec = mock(com.conductor.integration.IntegrationToolSpec.class);
        org.mockito.Mockito.when(toolSpec.ingest()).thenReturn(java.util.List.of(spec));
        org.mockito.Mockito.when(connector.getToolSpec()).thenReturn(toolSpec);
        org.mockito.Mockito.when(connectorRegistry.getById("meta")).thenReturn(java.util.Optional.of(connector));
        org.mockito.Mockito.when(postMetricsFeedPuller.pull(org.mockito.ArgumentMatchers.eq(feed),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(conn), org.mockito.ArgumentMatchers.any()))
                .thenReturn(com.conductor.integration.IngestBatch.empty("2026-09-04T14"));

        boolean more = service.pull(feed);

        org.assertj.core.api.Assertions.assertThat(more).isFalse();
        org.mockito.Mockito.verify(postMetricsFeedPuller).pull(org.mockito.ArgumentMatchers.eq(feed),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(conn), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(digestSink, metricsDigestService, snapshotIngestAdapter);
        org.assertj.core.api.Assertions.assertThat(feed.getCursorState()).isEqualTo("2026-09-04T14");
    }

    @org.junit.jupiter.api.Test
    void aChannelMetricsFeedIsPulledByItsPullerAndNeverReachesASink() {
        ChannelMetricsFeedPuller channelPuller = mock(ChannelMetricsFeedPuller.class);
        FeedPullService withChannel = new FeedPullService(feedRepository, connectionService, connectorRegistry,
                snapshotIngestAdapter, digestSink, metricsDigestService, postMetricsFeedPuller, channelPuller);
        ConnectorFeed feed = feed();
        feed.setIngestId("channel_metrics");
        feed.setCursorState(null);
        feed.setIntervalMinutes(360);
        Connection conn = connection();
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(conn));
        when(connectionService.toContext(conn)).thenReturn(ctx());
        IngestSpec spec = new IngestSpec("channel_metrics", "l", "d", IngestMode.SNAPSHOT, null, null, 360, null,
                null, null, null, com.conductor.integration.IngestSink.CHANNEL_METRICS, null);
        Connector connector = mock(Connector.class);
        IntegrationToolSpec toolSpec = mock(IntegrationToolSpec.class);
        when(toolSpec.ingest()).thenReturn(List.of(spec));
        when(connector.getToolSpec()).thenReturn(toolSpec);
        when(connectorRegistry.getById("gsc")).thenReturn(Optional.of(connector));
        when(channelPuller.pull(org.mockito.ArgumentMatchers.eq(feed), any(), org.mockito.ArgumentMatchers.eq(conn), any()))
                .thenReturn(IngestBatch.empty("{\"lastDay\":\"2026-10-14\"}"));

        boolean more = withChannel.pull(feed);

        assertThat(more).isFalse();
        verify(channelPuller).pull(org.mockito.ArgumentMatchers.eq(feed), any(), org.mockito.ArgumentMatchers.eq(conn), any());
        org.mockito.Mockito.verifyNoInteractions(digestSink, metricsDigestService, snapshotIngestAdapter, postMetricsFeedPuller);
        assertThat(feed.getCursorState()).isEqualTo("{\"lastDay\":\"2026-10-14\"}");
        assertThat(feed.getNextRunAt()).isNotNull();
    }

    @org.junit.jupiter.api.Test
    void aStalledChannelPullIsStillASuccessWithZeroFailuresAndTheNormalSchedule() {
        ChannelMetricsFeedPuller channelPuller = mock(ChannelMetricsFeedPuller.class);
        FeedPullService withChannel = new FeedPullService(feedRepository, connectionService, connectorRegistry,
                snapshotIngestAdapter, digestSink, metricsDigestService, postMetricsFeedPuller, channelPuller);
        ConnectorFeed feed = feed();
        feed.setIngestId("channel_metrics");
        feed.setIntervalMinutes(360);
        feed.setConsecutiveFailures(0);
        Connection conn = connection();
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(conn));
        IngestSpec spec = new IngestSpec("channel_metrics", "l", "d", IngestMode.SNAPSHOT, null, null, 360, null,
                null, null, null, com.conductor.integration.IngestSink.CHANNEL_METRICS, null);
        Connector connector = mock(Connector.class);
        IntegrationToolSpec toolSpec = mock(IntegrationToolSpec.class);
        when(toolSpec.ingest()).thenReturn(List.of(spec));
        when(connector.getToolSpec()).thenReturn(toolSpec);
        when(connectorRegistry.getById("gsc")).thenReturn(Optional.of(connector));
        // what the puller returns for a feed flagged "no new data": a plain healthy batch
        when(channelPuller.pull(any(), any(), any(), any()))
                .thenReturn(new IngestBatch(List.of(), "{\"lastDay\":\"2026-10-14\"}", false, false,
                        com.conductor.integration.ConnectorHealth.HEALTHY, null));
        java.time.OffsetDateTime before = java.time.OffsetDateTime.now();

        withChannel.pull(feed);

        assertThat(feed.getConsecutiveFailures()).isZero();
        assertThat(feed.getStatus()).isEqualTo(com.conductor.integration.ingest.ConnectorFeedStatus.ACTIVE);
        assertThat(feed.getNextRunAt()).isAfterOrEqualTo(before.plusMinutes(360));
        assertThat(feed.getCursorState()).isEqualTo("{\"lastDay\":\"2026-10-14\"}");
    }

    private static ConnectorFeed feed() {
        ConnectorFeed feed = new ConnectorFeed();
        feed.setId("feed-1");
        feed.setProjectId("proj-1");
        feed.setConnectionId("conn-1");
        feed.setConnectorId("gsc");
        feed.setIngestId("search_analytics_weekly");
        feed.setMode(IngestMode.SNAPSHOT);
        feed.setIntervalMinutes(10080);
        feed.setCursorState("old-cursor");
        feed.setConsecutiveFailures(0);
        return feed;
    }

    private static Connection connection() {
        Connection c = new Connection();
        c.setId("conn-1");
        c.setProjectId("proj-1");
        c.setConnectorId("gsc");
        return c;
    }

    private static ConnectionContext ctx() {
        return new ConnectionContext("proj-1", "gsc", "conn-1", "token", null, null, Map.of(), null);
    }

    private static IngestItem item(String dedupKey) {
        return new IngestItem("type", "ref-" + dedupKey, null, "application/json", "{}", null, dedupKey, Map.of());
    }

    /** A real (non-mock) {@link Connector} -- {@code getToolSpec()} is a default interface method that
     *  Mockito can't stub cleanly, so this test double overrides it directly instead. */
    private static final class FakeConnector implements Connector {
        private final IntegrationToolSpec toolSpec;

        FakeConnector(IntegrationToolSpec toolSpec) {
            this.toolSpec = toolSpec;
        }

        @Override public String getId() { return "gsc"; }

        @Override
        public ConnectorMetadata getMetadata() {
            return new ConnectorMetadata("gsc", "Google Search Console", ConnectorCategory.ANALYTICS, "desc", "GSC");
        }

        @Override
        public ConnectorSpec getSpec() {
            return ConnectorSpec.oauth2(true, List.of());
        }

        @Override
        public IntegrationToolSpec getToolSpec() { return toolSpec; }
    }

    private static Connector connectorWithIngestSpec() {
        IngestSpec spec = new IngestSpec("search_analytics_weekly", "label", "desc", IngestMode.SNAPSHOT,
                "search_analytics", "metrics.digest.{connector}.{ingest}", null, null, null, null, null);
        return new FakeConnector(new IntegrationToolSpec("gsc", List.of(), List.of(), List.of(spec)));
    }

    private void stubHappyPathExceptBatch() {
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(connection()));
        when(connectionService.toContext(any())).thenReturn(ctx());
        when(connectorRegistry.getById("gsc")).thenReturn(Optional.of(connectorWithIngestSpec()));
        when(connectorRegistry.findIngest("gsc")).thenReturn(Optional.empty());
    }

    @Test
    void sinksEveryItemBeforeAdvancingTheCursor() {
        stubHappyPathExceptBatch();
        IngestItem item1 = item("dedup-1");
        IngestItem item2 = item("dedup-2");
        when(snapshotIngestAdapter.pull(any(), any(), any()))
                .thenReturn(IngestBatch.of(List.of(item1, item2), "new-cursor", false));

        ConnectorFeed feed = feed();
        service.pull(feed);

        InOrder inOrder = inOrder(digestSink, feedRepository);
        inOrder.verify(digestSink).accept("proj-1", item1);
        inOrder.verify(digestSink).accept("proj-1", item2);
        inOrder.verify(feedRepository).save(argThat(f -> "new-cursor".equals(f.getCursorState())));

        assertThat(feed.getCursorState()).isEqualTo("new-cursor");
        assertThat(feed.getStatus()).isEqualTo(ConnectorFeedStatus.ACTIVE);
        assertThat(feed.getNextRunAt()).isNotNull();
    }

    @Test
    void throwingSinkLeavesCursorStateUntouchedAndNeverSavesTheFeed() {
        stubHappyPathExceptBatch();
        IngestItem item1 = item("dedup-1");
        when(snapshotIngestAdapter.pull(any(), any(), any()))
                .thenReturn(IngestBatch.of(List.of(item1), "new-cursor", false));
        doThrow(new RuntimeException("boom")).when(digestSink).accept(any(), any());

        ConnectorFeed feed = feed();
        String originalCursor = feed.getCursorState();

        assertThatThrownBy(() -> service.pull(feed)).hasMessage("boom");

        assertThat(feed.getCursorState()).isEqualTo(originalCursor);
        verify(feedRepository, never()).save(any());
    }

    @Test
    void setupRequiredMarksFeedAndNeverTouchesCursor() {
        stubHappyPathExceptBatch();
        when(snapshotIngestAdapter.pull(any(), any(), any())).thenReturn(IngestBatch.setupRequired("needs auth"));

        ConnectorFeed feed = feed();
        String originalCursor = feed.getCursorState();
        service.pull(feed);

        assertThat(feed.getCursorState()).isEqualTo(originalCursor);
        assertThat(feed.getStatus()).isEqualTo(ConnectorFeedStatus.SETUP_REQUIRED);
        assertThat(feed.getLastError()).isEqualTo("needs auth");
        verify(digestSink, never()).accept(any(), any());
        verify(feedRepository).save(feed);
    }

    @Test
    void degradedIncrementsFailuresAndNeverTouchesCursor() {
        stubHappyPathExceptBatch();
        when(snapshotIngestAdapter.pull(any(), any(), any())).thenReturn(IngestBatch.degraded("timeout"));

        ConnectorFeed feed = feed();
        String originalCursor = feed.getCursorState();
        service.pull(feed);

        assertThat(feed.getCursorState()).isEqualTo(originalCursor);
        assertThat(feed.getConsecutiveFailures()).isEqualTo(1);
        assertThat(feed.getLastError()).isEqualTo("timeout");
        verify(digestSink, never()).accept(any(), any());
        verify(feedRepository).save(feed);
    }

    @Test
    void usesFeedsOwnIngestConnectorInsteadOfSnapshotAdapterWhenAvailable() {
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(connection()));
        when(connectionService.toContext(any())).thenReturn(ctx());
        when(connectorRegistry.getById("gsc")).thenReturn(Optional.of(connectorWithIngestSpec()));
        IngestConnector ingestConnector = mock(IngestConnector.class, RETURNS_DEFAULTS);
        when(connectorRegistry.findIngest("gsc")).thenReturn(Optional.of(ingestConnector));
        when(ingestConnector.pull(any(), any()))
                .thenReturn(IngestBatch.of(List.of(item("d1")), "c1", false));

        service.pull(feed());

        verify(ingestConnector).pull(any(), any());
        verify(snapshotIngestAdapter, never()).pull(any(), any(), any());
    }

    /** Minimal transaction manager: begins nothing real, but flips Spring's "actual transaction active" flag. */
    static final class FakeTxManager extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        int begun;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition d) { begun++; }
        @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus s) { }
        @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus s) { }
    }

    @org.junit.jupiter.api.Test
    void aPostMetricsPullRunsNoTransactionAcrossThePullerAndSkipsKms() {
        FakeTxManager txManager = new FakeTxManager();
        FeedPullService txService = new FeedPullService(feedRepository, connectionService, connectorRegistry,
                snapshotIngestAdapter, digestSink, metricsDigestService, postMetricsFeedPuller, null,
                new org.springframework.transaction.support.TransactionTemplate(txManager));
        ConnectorFeed feed = feed();
        feed.setConnectorId("meta");
        feed.setIngestId("post_metrics");
        Connection conn = connection();
        when(connectionService.getById("conn-1")).thenReturn(Optional.of(conn));
        IngestSpec spec = new IngestSpec("post_metrics", "l", "d", IngestMode.SNAPSHOT, null, null, 360, null, null,
                null, null, com.conductor.integration.IngestSink.POST_METRICS, null);
        com.conductor.integration.Connector connector = mock(com.conductor.integration.Connector.class);
        com.conductor.integration.IntegrationToolSpec toolSpec = mock(com.conductor.integration.IntegrationToolSpec.class);
        when(toolSpec.ingest()).thenReturn(java.util.List.of(spec));
        when(connector.getToolSpec()).thenReturn(toolSpec);
        when(connectorRegistry.getById("meta")).thenReturn(Optional.of(connector));
        boolean[] activeDuringPull = {true};
        when(postMetricsFeedPuller.pull(any(), any(), any(), any())).thenAnswer(inv -> {
            activeDuringPull[0] = org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive();
            return com.conductor.integration.IngestBatch.empty("2026-09-04T14");
        });

        txService.pull(feed);

        assertThat(activeDuringPull[0]).as("no transaction while the puller calls the platform").isFalse();
        verify(connectionService, never()).toContext(any());
        assertThat(txManager.begun).as("outcome bookkeeping still runs in a short transaction").isEqualTo(1);
        verify(feedRepository).save(feed);
        assertThat(feed.getCursorState()).isEqualTo("2026-09-04T14");
    }
}
