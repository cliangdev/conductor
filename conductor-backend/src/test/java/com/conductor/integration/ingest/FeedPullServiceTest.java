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

    private final FeedPullService service = new FeedPullService(
            feedRepository, connectionService, connectorRegistry, snapshotIngestAdapter, digestSink);

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
}
