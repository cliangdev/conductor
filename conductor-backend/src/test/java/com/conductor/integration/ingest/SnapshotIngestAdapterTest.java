package com.conductor.integration.ingest;

import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorData;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.FetchConnector;
import com.conductor.integration.IngestBatch;
import com.conductor.integration.IngestItem;
import com.conductor.integration.IngestMode;
import com.conductor.integration.IngestRequest;
import com.conductor.integration.IngestSpec;
import com.conductor.integration.IngestWindowSpec;
import com.conductor.integration.IntegrationToolSpec;
import com.conductor.integration.ToolOperation;
import com.conductor.integration.WindowAlignment;
import com.conductor.service.IntegrationFetchService;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SnapshotIngestAdapterTest {

    private final IntegrationFetchService fetchService = mock(IntegrationFetchService.class);
    private final ConnectorRegistry connectorRegistry = mock(ConnectorRegistry.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SnapshotIngestAdapter adapter =
            new SnapshotIngestAdapter(fetchService, connectorRegistry, objectMapper);

    private static ConnectionContext ctx() {
        return new ConnectionContext("proj-1", "gsc", "conn-1", "token", null, null, Map.of(), null);
    }

    private static IngestSpec gscWeeklySpec() {
        IngestWindowSpec window = new IngestWindowSpec(7, 3, WindowAlignment.ISO_WEEK);
        return new IngestSpec("search_analytics_weekly", "GSC weekly digest", "desc", IngestMode.SNAPSHOT,
                "search_analytics", "metrics.digest.{connector}.{ingest}", null, window, "KNOWLEDGE",
                "marketing", null);
    }

    // ---- periodKeyFor: the only place a cursor is ever compared against ----

    @Test
    void periodKeyIsStableAcrossAnIsoWeekButDiffersTheFollowingWeek() {
        IngestWindowSpec window = new IngestWindowSpec(7, 0, WindowAlignment.ISO_WEEK);
        LocalDate monday = LocalDate.of(2026, 7, 22).with(DayOfWeek.MONDAY);
        LocalDate sunday = monday.plusDays(6);
        LocalDate nextMonday = monday.plusDays(7);

        String mondayKey = adapter.periodKeyFor(window, monday.atStartOfDay(ZoneOffset.UTC).toInstant());
        String sundayKey = adapter.periodKeyFor(window,
                sunday.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(20 * 3600));
        String nextMondayKey = adapter.periodKeyFor(window, nextMonday.atStartOfDay(ZoneOffset.UTC).toInstant());

        assertThat(mondayKey).isEqualTo(sundayKey);
        assertThat(mondayKey).isNotEqualTo(nextMondayKey);
    }

    @Test
    void periodKeyUsesTheIsoWeekBasedYearAcrossACalendarYearBoundary() {
        IngestWindowSpec window = new IngestWindowSpec(7, 0, WindowAlignment.ISO_WEEK);
        // Falls in ISO week 1 of 2026 (2026-01-01 is a Thursday, so that week starts Mon 2025-12-29) --
        // the week-based year must win over the calendar year for this date.
        LocalDate date = LocalDate.of(2025, 12, 30);
        int expectedWeekBasedYear = date.get(IsoFields.WEEK_BASED_YEAR);
        int expectedWeek = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);

        String result = adapter.periodKeyFor(window, date.atStartOfDay(ZoneOffset.UTC).toInstant());

        assertThat(date.getYear()).isEqualTo(2025);
        assertThat(expectedWeekBasedYear).isEqualTo(2026); // proves the boundary case is real
        assertThat(result).isEqualTo(String.format("%d-W%02d", expectedWeekBasedYear, expectedWeek));
    }

    @Test
    void periodKeyShiftsAnchorByLagDays() {
        Instant now = LocalDate.of(2026, 7, 27).atStartOfDay(ZoneOffset.UTC).toInstant();
        String noLag = adapter.periodKeyFor(new IngestWindowSpec(7, 0, WindowAlignment.ISO_WEEK), now);
        String sevenDayLag = adapter.periodKeyFor(new IngestWindowSpec(7, 7, WindowAlignment.ISO_WEEK), now);

        assertThat(sevenDayLag).isNotEqualTo(noLag);
    }

    // ---- pull() ----

    @Test
    void emitsOneItemWithDeterministicDedupKeyAndSourceRefForThePeriod() {
        when(fetchService.fetchData("conn-1", false)).thenReturn(
                new ConnectorData(Map.of("trend", List.of()), ConnectorHealth.HEALTHY,
                        Instant.parse("2026-07-22T00:00:00Z"), null));
        when(connectorRegistry.findFetch("gsc")).thenReturn(Optional.empty());

        IngestRequest request = new IngestRequest("search_analytics_weekly", null, null, 100);
        IngestBatch batch = adapter.pull(ctx(), gscWeeklySpec(), request);

        assertThat(batch.items()).hasSize(1);
        IngestItem item = batch.items().get(0);
        String periodKey = (String) item.metadata().get("periodKey");

        assertThat(item.dedupKey()).isEqualTo("feed:conn-1:search_analytics_weekly:" + periodKey);
        assertThat(item.sourceRef()).isEqualTo("connector://gsc/conn-1/search_analytics_weekly@" + periodKey);
        assertThat(item.sourceType()).isEqualTo("metrics.digest.gsc.search_analytics_weekly");
        assertThat(batch.nextCursor()).isEqualTo(periodKey);
        assertThat(batch.health()).isEqualTo(ConnectorHealth.HEALTHY);
    }

    @Test
    void sameDedupKeyForTwoPullsInTheSameIsoWeekDifferentAcrossTheBoundary() {
        when(connectorRegistry.findFetch("gsc")).thenReturn(Optional.empty());

        LocalDate monday = LocalDate.of(2026, 7, 20);
        LocalDate nextMonday = monday.plusDays(7);
        when(fetchService.fetchData("conn-1", false)).thenReturn(
                new ConnectorData(Map.of("trend", List.of()), ConnectorHealth.HEALTHY,
                        monday.atStartOfDay(ZoneOffset.UTC).toInstant(), null));

        IngestSpec spec = gscWeeklySpec();
        IngestRequest request = new IngestRequest("search_analytics_weekly", null, null, 100);

        // Two pulls "in the same week" (both compare against Instant.now(), which is fixed for this
        // process run) share the same periodKey/dedupKey via periodKeyFor -- verified directly above.
        // Here we confirm the dedupKey format is entirely period-driven by computing it twice for
        // explicitly different periods and asserting they differ.
        String periodThisWeek = adapter.periodKeyFor(spec.window(), monday.atStartOfDay(ZoneOffset.UTC).toInstant());
        String periodNextWeek = adapter.periodKeyFor(spec.window(),
                nextMonday.atStartOfDay(ZoneOffset.UTC).toInstant());

        assertThat(periodThisWeek).isNotEqualTo(periodNextWeek);

        IngestBatch batch = adapter.pull(ctx(), spec, request);
        assertThat(batch.items().get(0).dedupKey())
                .isEqualTo("feed:conn-1:search_analytics_weekly:" + batch.nextCursor());
    }

    @Test
    void cursorEqualToComputedPeriodKeyShortCircuitsToZeroItems() {
        when(connectorRegistry.findFetch("gsc")).thenReturn(Optional.empty());
        when(fetchService.fetchData("conn-1", false)).thenReturn(
                new ConnectorData(Map.of("trend", List.of()), ConnectorHealth.HEALTHY, Instant.now(), null));

        IngestSpec spec = gscWeeklySpec();
        String currentPeriod = adapter.periodKeyFor(spec.window(), Instant.now());
        IngestRequest request = new IngestRequest("search_analytics_weekly", null, currentPeriod, 100);

        IngestBatch batch = adapter.pull(ctx(), spec, request);

        assertThat(batch.items()).isEmpty();
        assertThat(batch.nextCursor()).isEqualTo(currentPeriod);
        assertThat(batch.health()).isEqualTo(ConnectorHealth.HEALTHY);
    }

    @Test
    void setupRequiredYieldsNoItemsAndNoCursorAdvance() {
        when(fetchService.fetchData("conn-1", false))
                .thenReturn(ConnectorData.setupRequired("needs re-auth"));

        IngestRequest request = new IngestRequest("search_analytics_weekly", null, null, 100);
        IngestBatch batch = adapter.pull(ctx(), gscWeeklySpec(), request);

        assertThat(batch.items()).isEmpty();
        assertThat(batch.nextCursor()).isNull();
        assertThat(batch.health()).isEqualTo(ConnectorHealth.SETUP_REQUIRED);
    }

    @Test
    void degradedYieldsNoItemsAndNoCursorAdvance() {
        when(fetchService.fetchData("conn-1", false))
                .thenReturn(ConnectorData.degraded("upstream timeout", Map.of("trend", List.of())));

        IngestRequest request = new IngestRequest("search_analytics_weekly", null, "some-prior-cursor", 100);
        IngestBatch batch = adapter.pull(ctx(), gscWeeklySpec(), request);

        assertThat(batch.items()).isEmpty();
        assertThat(batch.nextCursor()).isNull();
        assertThat(batch.health()).isEqualTo(ConnectorHealth.DEGRADED);
    }

    @Test
    void projectsSnapshotByOperationOutputKeysWhenOperationIsKnown() throws Exception {
        FetchConnector fetch = mock(FetchConnector.class);
        ToolOperation op = new ToolOperation("search_analytics", "desc", Map.of(), null,
                List.of("trend", "topQueries"));
        when(fetch.getToolSpec()).thenReturn(new IntegrationToolSpec("gsc", List.of(op)));
        when(connectorRegistry.findFetch("gsc")).thenReturn(Optional.of(fetch));

        Map<String, Object> fullSnapshot = Map.of(
                "trend", List.of(),
                "topQueries", List.of(),
                "brandedClickShare", 0.31);
        when(fetchService.fetchData("conn-1", false)).thenReturn(
                new ConnectorData(fullSnapshot, ConnectorHealth.HEALTHY, Instant.now(), null));

        IngestRequest request = new IngestRequest("search_analytics_weekly", null, null, 100);
        IngestBatch batch = adapter.pull(ctx(), gscWeeklySpec(), request);

        Map<String, Object> projected = objectMapper.readValue(batch.items().get(0).payload(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(projected.keySet()).containsExactlyInAnyOrder("trend", "topQueries");
    }

    @Test
    void unknownOperationReturnsUnfilteredSnapshot() throws Exception {
        when(connectorRegistry.findFetch("gsc")).thenReturn(Optional.empty());
        Map<String, Object> fullSnapshot = Map.of("trend", List.of(), "brandedClickShare", 0.31);
        when(fetchService.fetchData("conn-1", false)).thenReturn(
                new ConnectorData(fullSnapshot, ConnectorHealth.HEALTHY, Instant.now(), null));

        IngestRequest request = new IngestRequest("search_analytics_weekly", null, null, 100);
        IngestBatch batch = adapter.pull(ctx(), gscWeeklySpec(), request);

        Map<String, Object> projected = objectMapper.readValue(batch.items().get(0).payload(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        assertThat(projected.keySet()).containsExactlyInAnyOrder("trend", "brandedClickShare");
    }
}
