package com.conductor.integration;

import java.util.List;

/**
 * The result of one {@link IngestConnector#pull}.
 *
 * <p>{@code nextCursor} is an OPAQUE, connector-owned blob. The platform persists it verbatim in
 * {@code connector_feed.cursor_state} and hands it back unchanged as {@link IngestRequest#cursor()} on
 * the next pull — it NEVER parses, validates, compares, orders, or migrates it. A cursor over
 * {@link #MAX_CURSOR_BYTES} is REJECTED outright (the batch is treated as failed and the feed's cursor
 * is NOT advanced), rather than truncated: a silently truncated opaque blob is unrecoverable, whereas a
 * rejected batch simply retries the same window/cursor next time.
 *
 * <p>{@code hasMore} tells the (later) puller to keep paging within the same tick; {@code
 * resyncRequired} tells it the connector's cursor is no longer valid and the feed should restart from
 * scratch (e.g. the remote API returned "cursor expired").
 */
public record IngestBatch(
        List<IngestItem> items,
        String nextCursor,
        boolean hasMore,
        boolean resyncRequired,
        ConnectorHealth health,
        String errorMessage) {

    /** Cap on {@link #nextCursor} size the platform will persist; see the class javadoc. */
    public static final int MAX_CURSOR_BYTES = 64 * 1024;

    public IngestBatch {
        if (items == null) items = List.of();
    }

    public static IngestBatch of(List<IngestItem> items, String nextCursor, boolean hasMore) {
        return new IngestBatch(items, nextCursor, hasMore, false, ConnectorHealth.HEALTHY, null);
    }

    public static IngestBatch empty() {
        return new IngestBatch(List.of(), null, false, false, ConnectorHealth.HEALTHY, null);
    }

    /** No items to hand off, but the cursor still advances (or stays put) at {@code cursor} — e.g. a
     *  SNAPSHOT feed re-pulled within the same period it already filed. */
    public static IngestBatch empty(String cursor) {
        return new IngestBatch(List.of(), cursor, false, false, ConnectorHealth.HEALTHY, null);
    }

    /** Expected remote failure (rate limit, timeout, transient API error) — never throw for these. */
    public static IngestBatch degraded(String errorMessage) {
        return new IngestBatch(List.of(), null, false, false, ConnectorHealth.DEGRADED, errorMessage);
    }

    public static IngestBatch setupRequired(String errorMessage) {
        return new IngestBatch(List.of(), null, false, false, ConnectorHealth.SETUP_REQUIRED, errorMessage);
    }
}
