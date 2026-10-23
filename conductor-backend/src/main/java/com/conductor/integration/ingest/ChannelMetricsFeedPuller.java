package com.conductor.integration.ingest;

import com.conductor.entity.Connection;
import com.conductor.integration.ChannelMetricsPull;
import com.conductor.integration.ChannelMetricsSource;
import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.IngestBatch;
import com.conductor.integration.IngestRequest;
import com.conductor.integration.IngestSpec;
import com.conductor.integration.OAuthReauthRequiredException;
import com.conductor.service.OAuthFlowService;
import com.conductor.service.ConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * The {@code CHANNEL_METRICS} sink: one pull asks the connection's {@link ChannelMetricsSource} for a window
 * of daily numbers and upserts them into {@code channel_metric_daily}, returning an item-less batch (like
 * {@code PostMetricsFeedPuller}) so {@code FeedPullService} applies the usual cursor and schedule bookkeeping.
 *
 * <h2>Windows</h2>
 * With no {@code lastDay} in the feed's cursor the first pull backfills {@link ChannelMetricsSource#backfillDays()}.
 * Afterwards each pull re-reads the source's {@link ChannelMetricsSource#trailingDays()} ending today, because
 * the platforms restate recent days; if the feed was down for longer than that, the window reaches back to
 * the last day already stored. Days within the trailing window are stored {@code provisional}.
 *
 * <p>A pull covers at most {@link ChannelMetricsSource#maxDaysPerPull()} days. When that stops short of today
 * the cursor records the chunk's last day and the batch says {@code hasMore}, so the scheduler re-dues the
 * feed at once; the next pull restarts {@code trailing - 1} days earlier and moves on, until it reaches today.
 * The OAuth access token is refreshed first (Google tokens last an hour).
 */
@Service
public class ChannelMetricsFeedPuller {

    private static final Logger log = LoggerFactory.getLogger(ChannelMetricsFeedPuller.class);

    static final String CURSOR_LAST_DAY = "lastDay";
    /** Optional: how many trailing days the source asked to re-read next time (see {@link ChannelMetricsPull}). */
    static final String CURSOR_TRAILING_DAYS = "trailingDays";
    /** Never reach back further than this, whatever a source asks for (GSC allows ~16 months, we keep it sane). */
    static final int MAX_BACKFILL_DAYS = 480;

    private final ConnectorRegistry connectorRegistry;
    private final ConnectionService connectionService;
    private final ChannelMetricDailyStore store;
    private final ChannelMetricDefinitionStore definitionStore;
    private final ChannelDimensionStateStore stateStore;
    private final ObjectMapper objectMapper;
    private final OAuthFlowService oAuthFlowService;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ChannelMetricsFeedPuller(ConnectorRegistry connectorRegistry,
                                    ConnectionService connectionService,
                                    ChannelMetricDailyStore store,
                                    ChannelMetricDefinitionStore definitionStore,
                                    ChannelDimensionStateStore stateStore,
                                    ObjectMapper objectMapper,
                                    OAuthFlowService oAuthFlowService) {
        this(connectorRegistry, connectionService, store, definitionStore, stateStore, objectMapper, oAuthFlowService,
                Clock.systemUTC());
    }

    ChannelMetricsFeedPuller(ConnectorRegistry connectorRegistry,
                             ConnectionService connectionService,
                             ChannelMetricDailyStore store,
                             ChannelMetricDefinitionStore definitionStore,
                             ChannelDimensionStateStore stateStore,
                             ObjectMapper objectMapper,
                             OAuthFlowService oAuthFlowService,
                             Clock clock) {
        this.connectorRegistry = connectorRegistry;
        this.connectionService = connectionService;
        this.store = store;
        this.definitionStore = definitionStore;
        this.stateStore = stateStore;
        this.objectMapper = objectMapper;
        this.oAuthFlowService = oAuthFlowService;
        this.clock = clock;
    }

    /**
     * Deliberately not {@code @Transactional}: the remote fetch can take minutes and must not hold a database
     * connection (or an idle-in-transaction session) open. The token refresh and the window write each run
     * in their own short transaction.
     */
    public IngestBatch pull(ConnectorFeed feed, IngestSpec spec, Connection connection, IngestRequest request) {
        ChannelMetricsSource source = connectorRegistry.getById(connection.getConnectorId())
                .filter(ChannelMetricsSource.class::isInstance)
                .map(ChannelMetricsSource.class::cast)
                .orElse(null);
        if (source == null) {
            return IngestBatch.setupRequired("Connector '" + connection.getConnectorId()
                    + "' does not provide channel metrics");
        }

        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        Integer previousHint = parseTrailingHint(request.cursor());
        int trailing = Math.max(1, source.trailingDays(previousHint));
        int backfill = Math.min(Math.max(trailing, source.backfillDays()), MAX_BACKFILL_DAYS);
        LocalDate lastDay = parseLastDay(request.cursor());

        LocalDate from;
        if (lastDay == null) {
            from = today.minusDays(backfill);
        } else {
            LocalDate trailingFrom = today.minusDays(trailing);
            LocalDate gapFrom = lastDay.minusDays(trailing - 1L);
            from = trailingFrom.isBefore(gapFrom) ? trailingFrom : gapFrom;
            LocalDate floor = today.minusDays(backfill);
            if (from.isBefore(floor)) {
                from = floor;
            }
        }
        LocalDate provisionalFrom = today.minusDays(trailing);

        // One chunk per pull so a backfill never holds a connection for minutes. The window always advances
        // past the stored lastDay, even when the trailing overlap is as wide as a chunk.
        long chunkEnd = (long) from.toEpochDay() + Math.max(1, source.maxDaysPerPull()) - 1;
        if (lastDay != null) {
            chunkEnd = Math.max(chunkEnd, lastDay.toEpochDay() + 1);
        }
        LocalDate to = chunkEnd >= today.toEpochDay() ? today : LocalDate.ofEpochDay(chunkEnd);

        ConnectionContext ctx;
        try {
            ctx = oAuthFlowService.withFreshToken(connection, connectionService.toContext(connection));
        } catch (OAuthReauthRequiredException e) {
            log.warn("Channel metrics feed {} ({}): sign-in expired: {}", feed.getId(), connection.getConnectorId(),
                    e.getMessage());
            return IngestBatch.setupRequired("Reconnect " + displayName(source, connection)
                    + " \u2014 its sign-in has expired");
        }
        ChannelMetricsPull pulled;
        try {
            pulled = source.pull(ctx, from, to);
        } catch (ChannelMetricsSourceException e) {
            log.warn("Channel metrics feed {} ({}): {}", feed.getId(), connection.getConnectorId(), e.getMessage());
            return e.health() == ConnectorHealth.SETUP_REQUIRED
                    ? IngestBatch.setupRequired(e.getMessage())
                    : IngestBatch.degraded(e.getMessage());
        }

        // A source that knows when its own numbers settle overrides the generic trailing window.
        if (pulled.provisionalFrom() != null) {
            provisionalFrom = pulled.provisionalFrom();
        }
        java.util.Set<String> pulledSnapshots = pulled.snapshotMetrics();
        int written = store.replaceWindow(connection.getProjectId(), connection.getId(), connection.getConnectorId(),
                source.channel().name(), from, to, provisionalFrom, pulled.points(), source.prunesMissingDimensions(),
                metric -> source.snapshot(metric) || pulledSnapshots.contains(metric));
        if (pulled.definitions() != null) {
            definitionStore.replace(connection.getProjectId(), connection.getId(), pulled.definitions());
        }
        if (!pulled.dimensionStates().isEmpty()) {
            stateStore.upsert(connection.getId(), pulled.dimensionStates());
        }
        log.info("Channel metrics feed {} upserted {} point(s) for {}..{}", feed.getId(), written, from, to);
        Integer hint = pulled.trailingDays() != null ? pulled.trailingDays() : previousHint;
        String cursor = "{\"" + CURSOR_LAST_DAY + "\":\"" + to + "\""
                + (hint == null ? "" : ",\"" + CURSOR_TRAILING_DAYS + "\":" + hint) + "}";
        return new IngestBatch(java.util.List.of(), cursor, to.isBefore(today), false, ConnectorHealth.HEALTHY, null);
    }

    private static String displayName(ChannelMetricsSource source, Connection connection) {
        if (source instanceof com.conductor.integration.Connector c && c.getMetadata() != null
                && c.getMetadata().name() != null && !c.getMetadata().name().isBlank()) {
            return c.getMetadata().name();
        }
        return connection.getConnectorId();
    }

    private Integer parseTrailingHint(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(cursor).get(CURSOR_TRAILING_DAYS);
            return node == null || !node.canConvertToInt() ? null : node.asInt();
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDate parseLastDay(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(cursor).get(CURSOR_LAST_DAY);
            return node == null || node.isNull() ? null : LocalDate.parse(node.asText());
        } catch (Exception e) {
            return null;
        }
    }
}
