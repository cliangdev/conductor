package com.conductor.integration.ingest;

import com.conductor.entity.Connection;
import com.conductor.integration.ChannelMetricPoint;
import com.conductor.integration.ChannelMetricsSource;
import com.conductor.integration.ChannelMetricsSourceException;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.IngestBatch;
import com.conductor.integration.IngestRequest;
import com.conductor.integration.IngestSpec;
import com.conductor.service.ConnectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Service
public class ChannelMetricsFeedPuller {

    private static final Logger log = LoggerFactory.getLogger(ChannelMetricsFeedPuller.class);

    static final String CURSOR_LAST_DAY = "lastDay";
    /** Never reach back further than this, whatever a source asks for (GSC allows ~16 months, we keep it sane). */
    static final int MAX_BACKFILL_DAYS = 480;

    private final ConnectorRegistry connectorRegistry;
    private final ConnectionService connectionService;
    private final ChannelMetricDailyStore store;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ChannelMetricsFeedPuller(ConnectorRegistry connectorRegistry,
                                    ConnectionService connectionService,
                                    ChannelMetricDailyStore store,
                                    ObjectMapper objectMapper) {
        this(connectorRegistry, connectionService, store, objectMapper, Clock.systemUTC());
    }

    ChannelMetricsFeedPuller(ConnectorRegistry connectorRegistry,
                             ConnectionService connectionService,
                             ChannelMetricDailyStore store,
                             ObjectMapper objectMapper,
                             Clock clock) {
        this.connectorRegistry = connectorRegistry;
        this.connectionService = connectionService;
        this.store = store;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
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
        int trailing = Math.max(1, source.trailingDays());
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

        ConnectionContext ctx = connectionService.toContext(connection);
        java.util.List<ChannelMetricPoint> points;
        try {
            points = source.pullDaily(ctx, from, today);
        } catch (ChannelMetricsSourceException e) {
            log.warn("Channel metrics feed {} ({}): {}", feed.getId(), connection.getConnectorId(), e.getMessage());
            return e.health() == ConnectorHealth.SETUP_REQUIRED
                    ? IngestBatch.setupRequired(e.getMessage())
                    : IngestBatch.degraded(e.getMessage());
        }

        int written = store.replaceWindow(connection.getProjectId(), connection.getId(), connection.getConnectorId(),
                source.channel().name(), from, today, provisionalFrom, points);
        log.info("Channel metrics feed {} upserted {} point(s) for {}..{}", feed.getId(), written, from, today);
        return IngestBatch.empty("{\"" + CURSOR_LAST_DAY + "\":\"" + today + "\"}");
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
