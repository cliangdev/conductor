package com.conductor.integration.ingest;

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
import com.conductor.service.ConnectionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class ChannelMetricsFeedPullerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    private ConnectorRegistry registry;
    private ConnectionService connectionService;
    private ChannelMetricDailyStore store;
    private ChannelMetricsSource source;
    private ChannelMetricsFeedPuller puller;
    private ConnectorFeed feed;
    private Connection connection;
    private IngestSpec spec;

    private record Upsert(String channel, String metric, String dimension, LocalDate day, BigDecimal value, boolean provisional) {}

    private final List<Upsert> upserts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        registry = mock(ConnectorRegistry.class);
        connectionService = mock(ConnectionService.class);
        store = mock(ChannelMetricDailyStore.class);
        Connector connector = mock(Connector.class, withSettings().extraInterfaces(ChannelMetricsSource.class));
        source = (ChannelMetricsSource) connector;
        when(source.channel()).thenReturn(MetricChannel.SEARCH);
        when(source.backfillDays()).thenReturn(90);
        when(source.trailingDays()).thenReturn(4);
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

        when(store.replaceWindow(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    LocalDate provisionalFrom = inv.getArgument(6);
                    for (ChannelMetricPoint p : inv.<List<ChannelMetricPoint>>getArgument(7)) {
                        upserts.add(new Upsert(inv.getArgument(3), p.metric(), p.dimensionKey(), p.day(), p.value(),
                                !p.day().isBefore(provisionalFrom)));
                    }
                    return ((List<?>) inv.getArgument(7)).size();
                });

        puller = new ChannelMetricsFeedPuller(registry, connectionService, store, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-10-14T09:00:00Z"), ZoneOffset.UTC));
    }

    private IngestBatch pull(String cursor) {
        return puller.pull(feed, spec, connection, new IngestRequest("channel_metrics", null, cursor, 500));
    }

    private static ChannelMetricPoint point(String metric, LocalDate day, double value) {
        return ChannelMetricPoint.total(metric, day, value);
    }

    @Test
    void firstRunBackfillsTheSourcesBackfillDays() {
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of());

        IngestBatch batch = pull(null);

        verify(source).pullDaily(any(), eq(TODAY.minusDays(90)), eq(TODAY));
        assertThat(batch.health()).isEqualTo(ConnectorHealth.HEALTHY);
        assertThat(batch.items()).isEmpty();
        assertThat(batch.nextCursor()).isEqualTo("{\"lastDay\":\"2026-10-14\"}");
    }

    @Test
    void laterRunsReadOnlyTheTrailingWindow() {
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of());

        pull("{\"lastDay\":\"2026-10-13\"}");

        verify(source).pullDaily(any(), eq(TODAY.minusDays(4)), eq(TODAY));
    }

    @Test
    void aLongOutageReachesBackToTheLastStoredDay() {
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of());

        pull("{\"lastDay\":\"2026-10-01\"}");

        verify(source).pullDaily(any(), eq(LocalDate.of(2026, 9, 28)), eq(TODAY));
    }

    @Test
    void daysInsideTheTrailingWindowAreProvisional() {
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of(
                point("clicks", TODAY.minusDays(10), 5),
                point("clicks", TODAY.minusDays(5), 6),
                point("clicks", TODAY.minusDays(4), 7),
                point("clicks", TODAY, 8)));

        pull("{\"lastDay\":\"2026-10-13\"}");

        assertThat(upserts).extracting(Upsert::day, Upsert::provisional).containsExactly(
                org.assertj.core.groups.Tuple.tuple(TODAY.minusDays(10), false),
                org.assertj.core.groups.Tuple.tuple(TODAY.minusDays(5), false),
                org.assertj.core.groups.Tuple.tuple(TODAY.minusDays(4), true),
                org.assertj.core.groups.Tuple.tuple(TODAY, true));
        assertThat(upserts).allMatch(u -> u.channel().equals("SEARCH") && u.dimension().isEmpty());
    }

    @Test
    void dimensionRowsKeepTheirKey() {
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of(
                ChannelMetricPoint.of("query_clicks", "acme pricing", TODAY, 3)));

        pull(null);

        assertThat(upserts).singleElement().satisfies(u -> {
            assertThat(u.metric()).isEqualTo("query_clicks");
            assertThat(u.dimension()).isEqualTo("acme pricing");
        });
    }

    @Test
    void aBrokenCursorIsTreatedAsAFirstRun() {
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of());

        pull("not json");

        verify(source).pullDaily(any(), eq(TODAY.minusDays(90)), eq(TODAY));
    }

    @Test
    void aSetupProblemIsReportedAndWritesNothing() {
        when(source.pullDaily(any(), any(), any())).thenThrow(ChannelMetricsSourceException.setupRequired("reconnect"));

        IngestBatch batch = pull(null);

        assertThat(batch.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        assertThat(batch.errorMessage()).isEqualTo("reconnect");
        verify(store, never()).replaceWindow(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aTransientFailureIsDegradedAndLeavesTheCursorAlone() {
        when(source.pullDaily(any(), any(), any())).thenThrow(ChannelMetricsSourceException.degraded("429"));

        IngestBatch batch = pull("{\"lastDay\":\"2026-10-13\"}");

        assertThat(batch.health()).isEqualTo(ConnectorHealth.DEGRADED);
        assertThat(batch.nextCursor()).isNull();
    }

    @Test
    void aConnectorWithoutASourceNeedsSetup() {
        Connector plain = mock(Connector.class);
        when(registry.getById("gsc")).thenReturn(Optional.of(plain));

        assertThat(pull(null).health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
    }

    @Test
    void theWindowIsWrittenInOneCallWithTheConnectionIdentityAndBounds() {
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of(point("clicks", TODAY, 1)));

        pull(null);

        verify(store).replaceWindow(eq("proj-1"), eq("conn-1"), eq("gsc"), eq("SEARCH"),
                eq(TODAY.minusDays(90)), eq(TODAY), eq(TODAY.minusDays(4)), any());
    }
}
