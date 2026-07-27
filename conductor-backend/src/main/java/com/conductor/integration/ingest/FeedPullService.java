package com.conductor.integration.ingest;

import com.conductor.entity.Connection;
import com.conductor.integration.ConnectionContext;
import com.conductor.integration.ConnectorHealth;
import com.conductor.integration.ConnectorRegistry;
import com.conductor.integration.IngestBatch;
import com.conductor.integration.IngestItem;
import com.conductor.integration.IngestMode;
import com.conductor.integration.IngestRequest;
import com.conductor.integration.IngestSpec;
import com.conductor.integration.IngestWindow;
import com.conductor.integration.IngestWindowSpec;
import com.conductor.service.ConnectionService;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Executes one {@link ConnectorFeed} pull: resolves its connection + declared {@link IngestSpec},
 * dispatches to the connector's own {@link com.conductor.integration.IngestConnector} if it has one or
 * to {@link SnapshotIngestAdapter} otherwise, hands every returned item to the configured
 * {@link DigestSink}, then advances the feed's cursor/scheduling state.
 *
 * <p><b>The single most important invariant in this class:</b> every {@link IngestItem} is sunk
 * BEFORE the cursor advances, never the reverse. A crash between the two re-pulls the same
 * window/cursor next time, and the sink is idempotent by {@link IngestItem#dedupKey()} — so a repeat
 * pull simply collapses back into the same rows. Advancing the cursor first would risk the opposite:
 * a crash right after would silently and unrecoverably skip that period forever, since the next pull
 * would never re-request it. This is why {@link #recordOutcome} sinks every item in a plain loop
 * before touching any cursor/scheduling field on {@code feed}, and why a thrown sink exception must
 * propagate out of this method rather than being swallowed — swallowing it here would let the caller
 * (a future scheduler) believe the pull succeeded and move the cursor anyway.
 */
@Service
public class FeedPullService {

    private static final int DEFAULT_MAX_ITEMS = 500;

    private final ConnectorFeedRepository feedRepository;
    private final ConnectionService connectionService;
    private final ConnectorRegistry connectorRegistry;
    private final SnapshotIngestAdapter snapshotIngestAdapter;
    private final DigestSink digestSink;

    public FeedPullService(ConnectorFeedRepository feedRepository,
                           ConnectionService connectionService,
                           ConnectorRegistry connectorRegistry,
                           SnapshotIngestAdapter snapshotIngestAdapter,
                           DigestSink digestSink) {
        this.feedRepository = feedRepository;
        this.connectionService = connectionService;
        this.connectorRegistry = connectorRegistry;
        this.snapshotIngestAdapter = snapshotIngestAdapter;
        this.digestSink = digestSink;
    }

    @Transactional
    public void pull(String feedId) {
        ConnectorFeed feed = feedRepository.findById(feedId)
                .orElseThrow(() -> new EntityNotFoundException("connector_feed not found: " + feedId));
        pull(feed);
    }

    /** Overload taking an already-loaded feed — the scheduler's claim query already has the row. */
    @Transactional
    public void pull(ConnectorFeed feed) {
        Connection conn = connectionService.getById(feed.getConnectionId())
                .orElseThrow(() -> new EntityNotFoundException("Connection not found: " + feed.getConnectionId()));
        ConnectionContext ctx = connectionService.toContext(conn);
        IngestSpec spec = resolveSpec(feed);

        IngestWindow window = spec.mode() == IngestMode.WINDOW
                ? computeWindow(spec.window(), Instant.now())
                : null;
        IngestRequest request = new IngestRequest(feed.getIngestId(), window, feed.getCursorState(), DEFAULT_MAX_ITEMS);

        IngestBatch batch = connectorRegistry.findIngest(feed.getConnectorId())
                .map(ingestConnector -> ingestConnector.pull(ctx, request))
                .orElseGet(() -> snapshotIngestAdapter.pull(ctx, spec, request));

        recordOutcome(feed, batch);
    }

    private void recordOutcome(ConnectorFeed feed, IngestBatch batch) {
        OffsetDateTime now = OffsetDateTime.now();

        if (batch.health() == ConnectorHealth.SETUP_REQUIRED) {
            feed.setStatus(ConnectorFeedStatus.SETUP_REQUIRED);
            feed.setLastError(batch.errorMessage());
            feed.setLastRunAt(now);
            feedRepository.save(feed);
            return;
        }
        if (batch.health() == ConnectorHealth.DEGRADED) {
            // Never advance the cursor on a degraded (possibly stale-cache) pull.
            feed.setConsecutiveFailures(feed.getConsecutiveFailures() + 1);
            feed.setLastError(batch.errorMessage());
            feed.setLastRunAt(now);
            feedRepository.save(feed);
            return;
        }

        // Sink first -- see class javadoc. Any exception here propagates before any field on `feed`
        // that affects the cursor or schedule is touched below.
        for (IngestItem item : batch.items()) {
            digestSink.accept(feed.getProjectId(), item);
        }

        advanceCursor(feed, batch, now);
        feed.setLastRunAt(now);
        feedRepository.save(feed);
    }

    private void advanceCursor(ConnectorFeed feed, IngestBatch batch, OffsetDateTime now) {
        if (batch.resyncRequired()) {
            // The connector's cursor is no longer valid -- restart the feed from scratch next pull.
            feed.setCursorState(null);
        } else if (batch.nextCursor() != null) {
            feed.setCursorState(batch.nextCursor());
        }
        feed.setCursorUpdatedAt(now);
        feed.setConsecutiveFailures(0);
        feed.setLastError(null);
        feed.setStatus(ConnectorFeedStatus.ACTIVE);
        feed.setLastSuccessAt(now);
        feed.setNextRunAt(now.plusMinutes(feed.getIntervalMinutes()));
    }

    private IngestSpec resolveSpec(ConnectorFeed feed) {
        return connectorRegistry.getById(feed.getConnectorId())
                .map(c -> c.getToolSpec().ingest())
                .flatMap(specs -> specs.stream().filter(s -> s.id().equals(feed.getIngestId())).findFirst())
                .orElseThrow(() -> new IllegalStateException(
                        "No ingest '" + feed.getIngestId() + "' declared for connector '" + feed.getConnectorId() + "'"));
    }

    /**
     * Builds the {@link IngestWindow} an {@code IngestConnector#pull} call needs for a {@code WINDOW}-
     * mode feed: {@code sizeDays} wide, ending at the {@code alignTo} boundary at-or-after
     * {@code now - lagDays} (e.g. the Monday starting the ISO week containing that date, plus 7 days).
     * Not used for {@code SNAPSHOT}-mode feeds -- see {@link SnapshotIngestAdapter}'s own period-key
     * alignment, which serves the equivalent purpose for those.
     */
    private IngestWindow computeWindow(IngestWindowSpec spec, Instant now) {
        LocalDate anchor = now.atZone(ZoneOffset.UTC).toLocalDate().minusDays(spec.lagDays());
        LocalDate end = switch (spec.alignTo()) {
            case ISO_WEEK -> anchor.with(DayOfWeek.MONDAY).plusDays(7);
            case MONTH -> anchor.withDayOfMonth(1).plusMonths(1);
            case DAY -> anchor.plusDays(1);
        };
        LocalDate start = end.minusDays(spec.sizeDays());
        return new IngestWindow(
                start.atStartOfDay(ZoneOffset.UTC).toInstant(),
                end.atStartOfDay(ZoneOffset.UTC).toInstant());
    }
}
