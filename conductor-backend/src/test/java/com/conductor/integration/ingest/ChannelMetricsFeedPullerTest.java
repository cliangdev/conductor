package com.conductor.integration.ingest;

import com.conductor.entity.Connection;
import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsPull;
import com.conductor.integration.MetricDefinition;
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
    private ChannelMetricDefinitionStore definitionStore;
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
        definitionStore = mock(ChannelMetricDefinitionStore.class);
        oAuthFlowService = mock(OAuthFlowService.class);
        when(oAuthFlowService.withFreshToken(any(), any())).thenAnswer(inv -> inv.getArgument(1));
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

        when(store.replaceWindow(anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenAnswer(inv -> {
                    LocalDate provisionalFrom = inv.getArgument(6);
                    for (ChannelMetricPoint p : inv.<List<ChannelMetricPoint>>getArgument(7)) {
                        upserts.add(new Upsert(inv.getArgument(3), p.metric(), p.dimensionKey(), p.day(), p.value(),
                                !inv.<java.util.function.Predicate<String>>getArgument(9).test(p.metric()) && !p.day().isBefore(provisionalFrom)));
                    }
                    return ((List<?>) inv.getArgument(7)).size();
                });

        puller = new ChannelMetricsFeedPuller(registry, connectionService, store, definitionStore, mock(ChannelDimensionStateStore.class),
                new ObjectMapper(), oAuthFlowService, Clock.fixed(Instant.parse("2026-10-14T09:00:00Z"), ZoneOffset.UTC));
    }

    private void stubPull(ChannelMetricsPull pulled) {
        org.mockito.Mockito.doReturn(pulled).when(source).pull(any(), any(), any());
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
                point("clicks", TODAY.minusDays(3), 7),
                point("clicks", TODAY, 8)));

        pull("{\"lastDay\":\"2026-10-13\"}");

        assertThat(upserts).extracting(Upsert::day, Upsert::provisional).containsExactly(
                org.assertj.core.groups.Tuple.tuple(TODAY.minusDays(10), false),
                org.assertj.core.groups.Tuple.tuple(TODAY.minusDays(5), false),
                org.assertj.core.groups.Tuple.tuple(TODAY.minusDays(4), false),
                org.assertj.core.groups.Tuple.tuple(TODAY.minusDays(3), true),
                org.assertj.core.groups.Tuple.tuple(TODAY, true));
        assertThat(upserts).allMatch(u -> u.channel().equals("SEARCH") && u.dimension().isEmpty());
    }

    @Test
    void theOldestReReadDayIsWrittenSettledBecauseTomorrowsWindowNoLongerReachesIt() {
        // trailing 4, today Oct 14: window Oct 10..14 is re-read, but tomorrow's starts Oct 11
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of(
                point("clicks", LocalDate.of(2026, 10, 10), 1), point("clicks", LocalDate.of(2026, 10, 11), 1)));

        pull("{\"lastDay\":\"2026-10-13\"}");

        assertThat(upserts).extracting(Upsert::day, Upsert::provisional).containsExactly(
                org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 10, 10), false),
                org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 10, 11), true));
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

        verify(store).replaceWindow(any(), any(), any(), any(), any(), any(), any(), any(), eq(false), any(), any());
    }

    @Test
    void skippedCampaignsAreHandedToTheStoreAndPruningStaysOn() {
        stubPull(new ChannelMetricsPull(List.of(), null, null, null, java.util.Set.of(), List.of(), null,
                java.util.Set.of("1005")));

        pull(null);

        verify(store).replaceWindow(any(), any(), any(), any(), any(), any(), any(), any(), eq(true), any(),
                eq(java.util.Set.of("1005")));
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
        verify(store, never()).replaceWindow(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any());
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
                eq(TODAY.minusDays(90)), eq(TODAY), eq(TODAY.minusDays(3)), any(), eq(true), any(), any());
    }

    // ---- source-declared provisional boundary, trailing hint and definitions ----

    @Test
    void aSourceProvisionalFromOverridesTheGenericTrailingWindow() {
        // generic rule: trailing 4 => provisional from Oct 10; the source says only Oct 13 onwards is
        stubPull(new ChannelMetricsPull(List.of(
                point("opens", TODAY.minusDays(3), 1), point("opens", TODAY.minusDays(2), 2),
                point("opens", TODAY.minusDays(1), 3), point("opens", TODAY, 4)),
                TODAY.minusDays(1), null, null, java.util.Set.of()));

        pull("{\"lastDay\":\"2026-10-13\"}");

        assertThat(upserts).extracting(Upsert::day, Upsert::provisional).containsExactly(
                org.assertj.core.groups.Tuple.tuple(TODAY.minusDays(3), false),
                org.assertj.core.groups.Tuple.tuple(TODAY.minusDays(2), false),
                org.assertj.core.groups.Tuple.tuple(TODAY.minusDays(1), true),
                org.assertj.core.groups.Tuple.tuple(TODAY, true));
        verify(store).replaceWindow(any(), any(), any(), any(), any(), any(), eq(TODAY.minusDays(1)), any(),
                anyBoolean(), any(), any());
    }

    @Test
    void aSourceCanDeclareSnapshotMetricsForOnePull() {
        stubPull(new ChannelMetricsPull(List.of(
                point("subscribers", TODAY, 50), point("opens", TODAY, 4)),
                null, null, null, java.util.Set.of("subscribers")));

        pull(null);

        assertThat(upserts).extracting(Upsert::metric, Upsert::provisional).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("subscribers", false),
                org.assertj.core.groups.Tuple.tuple("opens", true));
    }

    @Test
    void theTrailingWindowFollowsTheHintStoredInTheCursorAndTheCursorKeepsCarryingIt() {
        org.mockito.Mockito.doAnswer(inv -> inv.getArgument(0) == null ? 4 : inv.getArgument(0)).when(source).trailingDays(any(Integer.class));
        stubPull(ChannelMetricsPull.of(List.of()));

        IngestBatch batch = pull("{\"lastDay\":\"2026-10-13\",\"trailingDays\":7}");

        verify(source).pull(any(), eq(TODAY.minusDays(7)), eq(TODAY));
        assertThat(batch.nextCursor()).isEqualTo("{\"lastDay\":\"2026-10-14\",\"trailingDays\":7}");
    }

    @Test
    void aNewHintFromThePullReplacesTheOneInTheCursor() {
        org.mockito.Mockito.doAnswer(inv -> inv.getArgument(0) == null ? 4 : inv.getArgument(0)).when(source).trailingDays(any(Integer.class));
        stubPull(new ChannelMetricsPull(List.of(), null, null, 3,
                java.util.Set.of()));

        IngestBatch first = pull(null);
        IngestBatch second = pull(first.nextCursor());

        assertThat(first.nextCursor()).isEqualTo("{\"lastDay\":\"2026-10-14\",\"trailingDays\":3}");
        verify(source).pull(any(), eq(TODAY.minusDays(3)), eq(TODAY));
        assertThat(second.nextCursor()).contains("\"trailingDays\":3");
    }

    @Test
    void definitionsAFeedDeclaresAreHandedToTheDefinitionStoreAfterTheWindowIsWritten() {
        List<MetricDefinition> definitions = List.of(new MetricDefinition("opens", "Opens", "COUNT", "SUM", null, null,
                "UP", 1, null, null, null));
        stubPull(new ChannelMetricsPull(List.of(point("opens", TODAY, 1)),
                null, definitions, null, java.util.Set.of()));

        pull(null);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(store, definitionStore);
        order.verify(store).replaceWindow(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any());
        order.verify(definitionStore).replace("proj-1", "conn-1", definitions);
    }

    @Test
    void aSourceWithoutDefinitionsNeverTouchesTheDefinitionStore() {
        when(source.pullDaily(any(), any(), any())).thenReturn(List.of());

        pull(null);

        verify(definitionStore, never()).replace(any(), any(), any());
    }

    // ---- restatement walks never exceed the per-request cap ----

    private List<LocalDate[]> drain(String cursor, int hint, java.util.function.Function<Integer, ChannelMetricsPull> respond) {
        List<LocalDate[]> calls = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            calls.add(new LocalDate[]{inv.getArgument(1), inv.getArgument(2)});
            return respond.apply(hint);
        }).when(source).pull(any(), any(), any());
        IngestBatch batch;
        int guard = 0;
        do {
            batch = pull(cursor);
            cursor = batch.nextCursor();
            assertThat(++guard).isLessThan(30);
        } while (batch.hasMore());
        lastCursor = cursor;
        return calls;
    }

    private String lastCursor;

    private void assertContiguousWithinCap(List<LocalDate[]> calls, LocalDate start) {
        LocalDate expected = start;
        for (LocalDate[] c : calls) {
            assertThat(c[0]).isEqualTo(expected);
            assertThat(java.time.temporal.ChronoUnit.DAYS.between(c[0], c[1]) + 1).isLessThanOrEqualTo(14);
            expected = c[1].plusDays(1);
        }
        assertThat(expected).isEqualTo(TODAY.plusDays(1));
    }

    @Test
    void aFortyFiveDayRestatementIsWalkedInChunksOfAtMostFourteenDays() {
        when(source.maxDaysPerPull()).thenReturn(14);
        org.mockito.Mockito.doAnswer(inv -> inv.getArgument(0) == null ? 3 : inv.getArgument(0)).when(source).trailingDays(any(Integer.class));

        List<LocalDate[]> calls = drain("{\"lastDay\":\"2026-10-13\",\"trailingDays\":45}", 45,
                h -> new ChannelMetricsPull(List.of(), null, null, 45, java.util.Set.of()));

        assertThat(calls).hasSize(4);
        assertContiguousWithinCap(calls, TODAY.minusDays(45));
        assertThat(lastCursor).isEqualTo("{\"lastDay\":\"2026-10-14\",\"trailingDays\":45}");
    }

    @Test
    void aNinetyDayRestatementIsWalkedInChunksOfAtMostFourteenDays() {
        when(source.maxDaysPerPull()).thenReturn(14);
        org.mockito.Mockito.doAnswer(inv -> inv.getArgument(0) == null ? 3 : inv.getArgument(0)).when(source).trailingDays(any(Integer.class));

        List<LocalDate[]> calls = drain("{\"lastDay\":\"2026-10-14\",\"trailingDays\":90}", 90,
                h -> new ChannelMetricsPull(List.of(), null, null, 90, java.util.Set.of()));

        assertThat(calls).hasSize(7);
        assertContiguousWithinCap(calls, TODAY.minusDays(90));
    }

    @Test
    void movingFromAThreeDayHintToAFortyFiveDayHintNeverAsksForMoreThanFourteenDays() {
        when(source.maxDaysPerPull()).thenReturn(14);
        org.mockito.Mockito.doAnswer(inv -> inv.getArgument(0) == null ? 3 : inv.getArgument(0)).when(source).trailingDays(any(Integer.class));
        List<LocalDate[]> calls = new ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
            calls.add(new LocalDate[]{inv.getArgument(1), inv.getArgument(2)});
            return new ChannelMetricsPull(List.of(), null, null, 45, java.util.Set.of());
        }).when(source).pull(any(), any(), any());

        // yesterday's pull stored hint 3; this pull learns 45; the next walks 45 days
        IngestBatch first = pull("{\"lastDay\":\"2026-10-13\",\"trailingDays\":3}");
        assertThat(first.hasMore()).isFalse();
        calls.clear();
        String cursor = first.nextCursor();
        IngestBatch b;
        do {
            b = pull(cursor);
            cursor = b.nextCursor();
        } while (b.hasMore());

        assertThat(calls).hasSize(4);
        assertContiguousWithinCap(calls, TODAY.minusDays(45));
    }

    // ---- backfill when a response gains metrics or changes contract version ----

    private static MetricDefinition def(String key) {
        return new MetricDefinition(key, key, "COUNT", "SUM", null, null, "UP", 1, null, null, null);
    }

    @Test
    void newMetricKeysRestartTheBackfillWalkFromTheBackfillFloor() {
        when(source.maxDaysPerPull()).thenReturn(14);
        when(definitionStore.keys("conn-1")).thenReturn(java.util.Set.of("sessions"));
        stubPull(new ChannelMetricsPull(List.of(), null, List.of(def("sessions"), def("signups")), 3,
                java.util.Set.of(), List.of(), 2));

        IngestBatch batch = pull("{\"lastDay\":\"2026-10-13\",\"trailingDays\":3,\"contractVersion\":2}");

        assertThat(batch.hasMore()).isTrue();
        assertThat(batch.nextCursor()).contains("\"resumeFrom\":\"" + TODAY.minusDays(90) + "\"");
    }

    @Test
    void aContractVersionChangeRestartsTheBackfillWalk() {
        when(source.maxDaysPerPull()).thenReturn(14);
        when(definitionStore.keys("conn-1")).thenReturn(java.util.Set.of("sessions"));
        stubPull(new ChannelMetricsPull(List.of(), null, List.of(def("sessions")), 3, java.util.Set.of(), List.of(), 2));

        IngestBatch batch = pull("{\"lastDay\":\"2026-10-13\",\"trailingDays\":3}");

        assertThat(batch.hasMore()).isTrue();
        assertThat(batch.nextCursor()).contains("\"resumeFrom\":\"" + TODAY.minusDays(90) + "\"")
                .contains("\"contractVersion\":2");
    }

    @Test
    void unchangedKeysAndVersionDoNotBackfill() {
        when(source.maxDaysPerPull()).thenReturn(14);
        when(definitionStore.keys("conn-1")).thenReturn(java.util.Set.of("sessions"));
        stubPull(new ChannelMetricsPull(List.of(), null, List.of(def("sessions")), 3, java.util.Set.of(), List.of(), 2));

        IngestBatch batch = pull("{\"lastDay\":\"2026-10-13\",\"trailingDays\":3,\"contractVersion\":2}");

        assertThat(batch.hasMore()).isFalse();
        assertThat(batch.nextCursor()).doesNotContain("resumeFrom");
    }

    @Test
    void aFirstPullAlreadyBackfillsSoItNeverSchedulesAnotherWalk() {
        when(source.maxDaysPerPull()).thenReturn(200);
        when(definitionStore.keys("conn-1")).thenReturn(java.util.Set.of());
        stubPull(new ChannelMetricsPull(List.of(), null, List.of(def("sessions")), 3, java.util.Set.of(), List.of(), 2));

        IngestBatch batch = pull(null);

        assertThat(batch.hasMore()).isFalse();
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
            // each chunk starts the day after the previous chunk's last day: no overlap, no gap
            assertThat(from.getAllValues().get(i)).isEqualTo(to.getAllValues().get(i - 1).plusDays(1));
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
                eq(from), eq(to), eq(TODAY.minusDays(3)), any(), anyBoolean(), any(), any());
        assertThat(batch.hasMore()).isTrue();
        assertThat(batch.nextCursor())
                .isEqualTo("{\"lastDay\":\"" + to + "\",\"resumeFrom\":\"" + to.plusDays(1) + "\"}");
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
        verify(store, never()).replaceWindow(any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any());
    }
}
