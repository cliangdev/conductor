package com.conductor.integration;

/**
 * INGEST capability: pulls a batch of timestamped {@link IngestItem}s for the Knowledge Center inbox,
 * optionally scoped to an {@link IngestWindow}. Required for any feed declaring {@code mode: WINDOW} in
 * its {@link IngestSpec} (a {@link FetchConnector}'s single dashboard-shaped snapshot has no window
 * concept); a {@code SNAPSHOT}-mode feed can be served by bridging {@link FetchConnector#fetchData} —
 * implementing this interface is only required to add window semantics.
 *
 * <p>{@link #pull} must be side-effect-free with respect to the platform (it must not itself write to
 * the Knowledge Center or any other Conductor state — the caller does that) and safe to call twice with
 * the same {@link IngestRequest}: re-pulling the same window/cursor after a crash or retry must yield
 * the same {@link IngestItem#dedupKey()}s each time, so the idempotent inbox collapses the duplicates
 * rather than double-ingesting. Expected remote failures (rate limits, timeouts, transient API errors)
 * must be returned as {@link IngestBatch#degraded(String)} rather than thrown, mirroring
 * {@link FetchConnector#fetchData}'s convention.
 */
public interface IngestConnector extends Connector {
    IngestBatch pull(ConnectionContext ctx, IngestRequest request);
}
