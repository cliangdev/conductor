package com.conductor.integration.ingest;

import com.conductor.integration.IngestItem;

/**
 * Where a {@link FeedPullService} pull hands off each {@link IngestItem} it emits. An interface (not a
 * direct {@code KnowledgeIngestionService} call) so the destination is a bean swap, not a scheduler
 * edit: {@link KnowledgeIngestionDigestSink} files straight to the Knowledge Center inbox for now; a
 * later SignalBus-based implementation can insert the digest-materiality gate (see the (later)
 * {@code com.conductor.integration.ingest.digest} package) in front of that without touching
 * {@link FeedPullService} at all.
 *
 * <p>Must be idempotent by {@link IngestItem#dedupKey()} — {@link FeedPullService} may call this again
 * for the same item after a crash between the sink call and the cursor advance (see
 * {@link FeedPullService}'s class javadoc for why that ordering is deliberate).
 */
public interface DigestSink {
    void accept(String projectId, IngestItem item);
}
