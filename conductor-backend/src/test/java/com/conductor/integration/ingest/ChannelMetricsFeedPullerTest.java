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
import com.conductor.integration.OAuthReauthRequiredException;
import com.conductor.integration.ConnectorMetadata;
import com.conductor.service.OAuthFlowService;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
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
    private OAuthFlowService oAuthFlowService;
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
        oAuthFlowService = mock(OAuthFlowService.class);
        when(oAuthFlowService.withFreshToken(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        Connector connector = mock(Connector.class, withSettings().extraInterfaces(ChannelMetricsSource.class));
        source = (ChannelMetricsSource) connector;
        when(source.channel()).thenReturn(MetricChannel.SEARCH);
        when(source.backfillDays()).thenReturn(90);
        when(source.trailingDays()).thenReturn(4);
        when(source.prunesMissingDimensions()).thenReturn(true);
        when(source.maxDaysPerPull()).thenReturn(Integer.MAX_VALUE);
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

        when(store.replaceWindow(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    LocalDate provisionalFrom = inv.getArgument(6);
                    for (ChannelMetricPoint p : inv.<List<ChannelMetricPoint>>getArgument(7)) {
                        upserts.add(new Upsert(inv.getArgument(3), p.metric(), p.dimensionKey(), p.day(), p.value(),
                                !inv.<java.util.function.Predicate<String>>getArgument(9).test(p.metric()) && !p.day().isBefore(provisionalFrom)));
                    }
                    return ((List<?>) inv.getArgument(7)).size();
                });

        puller = new ChannelMetricsFeedPuller(registry, connectionService, store, new ObjectMapper(),
                oAuthFlowService, Clock.fixed(Instant.parse("2026-10-14T09:00:00Z"), ZoneOffset.UTC));
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
    void aDayTheSourceHasNothingYetForIsLeftAloneAndTheWindowStillAdvances() {
        // The store platform publishes late: the newest two days come back with no rows at all.
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of(
                point("downloads", TODAY.minusDays(3), 5), point("downloads", TODAY.minusDays(2), 6)));

        IngestBatch batch = pull("{\"lastDay\":\"2026-10-13\"}");

        assertThat(upserts).extracting(Upsert::day).containsExactly(TODAY.minusDays(3), TODAY.minusDays(2));
        assertThat(upserts).noneMatch(u -> u.day().isAfter(TODAY.minusDays(2)));
        assertThat(batch.nextCursor()).isEqualTo("{\"lastDay\":\"2026-10-14\"}");
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
    void aSnapshotSourceWithoutBackfillStillReadsOneDayAndStoresTodaysLevel() {
        when(source.channel()).thenReturn(MetricChannel.SOCIAL);
        when(source.backfillDays()).thenReturn(0);
        when(source.trailingDays()).thenReturn(1);
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of(
                ChannelMetricPoint.of("followers", "youtube", TODAY, 950)));

        IngestBatch first = pull(null);
        pull(first.nextCursor());

        verify(source, org.mockito.Mockito.times(2)).pullDaily(any(), eq(TODAY.minusDays(1)), eq(TODAY));
        assertThat(upserts).hasSize(2).allSatisfy(u -> {
            assertThat(u.channel()).isEqualTo("SOCIAL");
            assertThat(u.dimension()).isEqualTo("youtube");
            assertThat(u.day()).isEqualTo(TODAY);
            assertThat(u.value()).isEqualByComparingTo("950");
        });
    }

    @Test
    void aClosedDimensionSetSourceAsksTheStoreNotToPruneMissingDimensions() {
        when(source.prunesMissingDimensions()).thenReturn(false);
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of());

        pull(null);

        verify(store).replaceWindow(any(), any(), any(), any(), any(), any(), any(), any(), eq(false), any());
    }

    @Test
    void snapshotMetricsAreStoredFinalWhileFlowsInsideTheTrailingWindowStayProvisional() {
        when(source.snapshot(anyString())).thenAnswer(inv -> !"website_clicks".equals(inv.getArgument(0)));
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of(
                ChannelMetricPoint.of("followers", "instagram", TODAY, 2400),
                ChannelMetricPoint.of("website_clicks", "instagram", TODAY, 9),
                ChannelMetricPoint.of("website_clicks", "instagram", TODAY.minusDays(10), 7)));

        pull(null);

        assertThat(upserts).extracting(Upsert::metric, Upsert::day, Upsert::provisional).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("followers", TODAY, false),
                org.assertj.core.groups.Tuple.tuple("website_clicks", TODAY, true),
                org.assertj.core.groups.Tuple.tuple("website_clicks", TODAY.minusDays(10), false));
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
        verify(store, never()).replaceWindow(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
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
                eq(TODAY.minusDays(90)), eq(TODAY), eq(TODAY.minusDays(4)), any(), eq(true), any());
    }

    // ---- chunked backfill ----

    @Test
    void aBackfillIsDrainedInContiguousChunksUntilCaughtUp() {
        when(source.maxDaysPerPull()).thenReturn(30);
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of());
        org.mockito.ArgumentCaptor<LocalDate> from = org.mockito.ArgumentCaptor.forClass(LocalDate.class);
        org.mockito.ArgumentCaptor<LocalDate> to = org.mockito.ArgumentCaptor.forClass(LocalDate.class);

        String cursor = null;
        int pulls = 0;
        IngestBatch batch;
        do {
            batch = pull(cursor);
            cursor = batch.nextCursor();
            pulls++;
            assertThat(pulls).isLessThan(20);
        } while (batch.hasMore());

        verify(source, org.mockito.Mockito.times(pulls)).pullDaily(any(), from.capture(), to.capture());
        assertThat(from.getAllValues().get(0)).isEqualTo(TODAY.minusDays(90));
        assertThat(to.getAllValues().get(0)).isEqualTo(TODAY.minusDays(90).plusDays(29));
        for (int i = 1; i < pulls; i++) {
            // each chunk restarts exactly trailing-1 (3) days before the previous chunk's last day
            assertThat(from.getAllValues().get(i)).isEqualTo(to.getAllValues().get(i - 1).minusDays(3));
            assertThat(to.getAllValues().get(i)).isAfter(to.getAllValues().get(i - 1));
        }
        assertThat(to.getAllValues().get(pulls - 1)).isEqualTo(TODAY);
        assertThat(cursor).isEqualTo("{\"lastDay\":\"2026-10-14\"}");
        assertThat(batch.hasMore()).isFalse();
    }

    @Test
    void aMidBackfillChunkReportsHasMoreAndStoresOnlyItsOwnRange() {
        when(source.maxDaysPerPull()).thenReturn(14);
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of());

        IngestBatch batch = pull(null);

        LocalDate from = TODAY.minusDays(90);
        LocalDate to = from.plusDays(13);
        verify(source).pullDaily(any(), eq(from), eq(to));
        verify(store).replaceWindow(eq("proj-1"), eq("conn-1"), eq("gsc"), eq("SEARCH"),
                eq(from), eq(to), eq(TODAY.minusDays(4)), any(), anyBoolean(), any());
        assertThat(batch.hasMore()).isTrue();
        assertThat(batch.nextCursor()).isEqualTo("{\"lastDay\":\"" + to + "\"}");
    }

    @Test
    void aChunkNoWiderThanTheTrailingOverlapStillMakesProgress() {
        when(source.maxDaysPerPull()).thenReturn(3); // < trailing (4)
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of());

        String cursor = null;
        int pulls = 0;
        IngestBatch batch;
        do {
            batch = pull(cursor);
            cursor = batch.nextCursor();
            pulls++;
            assertThat(pulls).isLessThan(200);
        } while (batch.hasMore());

        assertThat(cursor).isEqualTo("{\"lastDay\":\"2026-10-14\"}");
    }

    @Test
    void anEmptyChunkStillAdvancesTheCursor() {
        when(source.maxDaysPerPull()).thenReturn(14);
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of());

        IngestBatch first = pull(null);
        IngestBatch second = pull(first.nextCursor());

        assertThat(second.nextCursor()).isNotEqualTo(first.nextCursor());
    }

    @Test
    void whenCaughtUpAChunkedSourceReadsJustTheTrailingWindow() {
        when(source.maxDaysPerPull()).thenReturn(14);
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of());

        IngestBatch batch = pull("{\"lastDay\":\"2026-10-14\"}");

        verify(source).pullDaily(any(), eq(TODAY.minusDays(4)), eq(TODAY));
        assertThat(batch.hasMore()).isFalse();
    }

    @Test
    void aSnapshotSourceIsAlwaysAskedForAWindowEndingToday() {
        // the default maxDaysPerPull never chunks; backfill 1, trailing 1 like a rolling-snapshot source
        when(source.backfillDays()).thenReturn(1);
        when(source.trailingDays()).thenReturn(1);
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of());

        IngestBatch first = pull(null);
        IngestBatch second = pull("{\"lastDay\":\"2026-10-01\"}");

        verify(source, org.mockito.Mockito.times(2)).pullDaily(any(), any(), eq(TODAY));
        assertThat(first.hasMore()).isFalse();
        assertThat(second.hasMore()).isFalse();
    }

    // ---- OAuth token freshness ----

    @Test
    void theSourceIsPulledWithTheRefreshedContext() {
        ConnectionContext fresh = new ConnectionContext("proj-1", "gsc", "conn-1", "fresh-token", null, null,
                java.util.Map.of(), null);
        when(oAuthFlowService.withFreshToken(eq(connection), any())).thenReturn(fresh);
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of());

        pull(null);

        verify(source).pullDaily(eq(fresh), any(), any());
    }

    @Test
    void aDeadRefreshTokenAsksForAReconnectInPlainWords() {
        when(oAuthFlowService.withFreshToken(any(), any()))
                .thenThrow(new OAuthReauthRequiredException("invalid_grant", null));
        when(((Connector) source).getMetadata()).thenReturn(new ConnectorMetadata("gsc", "Google Search Console",
                null, "d", "G"));

        IngestBatch batch = pull(null);

        assertThat(batch.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
        assertThat(batch.errorMessage()).isEqualTo("Reconnect Google Search Console — its sign-in has expired");
        verify(source, never()).pullDaily(any(), any(), any());
        verify(store, never()).replaceWindow(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any());
    }
}
