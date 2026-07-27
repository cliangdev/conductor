package com.conductor.pipeline;

import java.util.List;

/**
 * The pipeline's actual directed stage graph (issue #342 correction) — a branching DAG, not the
 * straight chain the frontend originally (and wrongly) assumed. Verified against the real code, edge
 * by edge:
 *
 * <ul>
 *   <li>{@code WEBHOOKS -> INBOX} — direct. {@code KnowledgeSignalSink} (a {@code SignalSubscriber})
 *       calls {@code KnowledgeIngestionService.submit(...)} off a {@code Signal} published straight
 *       from a webhook-derived event in {@code GitHubConnector}. No {@code ConnectorFeed}/{@code
 *       ConnectorFeedDigest} row is ever created or read on this path.
 *   <li>{@code FEEDS -> DIGESTS} — {@code ConnectorFeedScheduler} pulls a due feed and hands the
 *       result to {@code MetricsDigestService}, which always writes a {@code ConnectorFeedDigest} row
 *       (material or {@code SKIPPED}) for a metric feed.
 *   <li>{@code DIGESTS -> INBOX} — {@code DigestSubmissionService} submits a material digest's
 *       narrated prose into the same knowledge inbox any other producer uses.
 *   <li>{@code INBOX -> LIBRARIAN_RUNS} — {@code KnowledgeIngestScheduler} claims due {@code
 *       PENDING} sources and fires a librarian run via {@code LibrarianDispatchService}.
 *   <li>{@code LIBRARIAN_RUNS -> PAGES_WRITTEN} — the librarian run's {@code write_knowledge_pages}
 *       call is what produces {@code knowledge_page_revisions} in the first place.
 * </ul>
 *
 * <p><b>Deliberately NOT modeled: a {@code FEEDS -> INBOX} bypass edge.</b>
 * {@code FeedPullService.recordOutcome} has a real branch for it —
 * {@code if (spec.isMetricFeed()) { metricsDigestService.record(...); } else {
 * digestSink.accept(feed.getProjectId(), item); }} — a non-metric feed's items go straight to {@code
 * KnowledgeIngestionService} via the generic {@code DigestSink}, skipping {@code
 * ConnectorFeedDigest}/DIGESTS entirely. This is unreachable today: every shipped connector's {@code
 * ingest[]} entries declare a {@code digest} block (i.e. {@code isMetricFeed()} is always true) —
 * {@code PipelineTopologyToolSpecTest} asserts exactly that, over every tool-spec on the classpath,
 * and will fail the moment a non-metric feed ships. If it does: add {@code new Edge("FEEDS",
 * "INBOX")} here, correct {@link PipelineHealthService}'s class javadoc, and update
 * {@code docs/knowledge.md}'s "Live health" section — don't just relax the failing test.
 */
public final class PipelineTopology {

    /** One directed edge, `from` and `to` matching {@code PipelineStage} enum names verbatim. */
    public record Edge(String from, String to) {
    }

    public static final List<Edge> EDGES = List.of(
            new Edge("WEBHOOKS", "INBOX"),
            new Edge("FEEDS", "DIGESTS"),
            new Edge("DIGESTS", "INBOX"),
            new Edge("INBOX", "LIBRARIAN_RUNS"),
            new Edge("LIBRARIAN_RUNS", "PAGES_WRITTEN"));

    private PipelineTopology() {
    }
}
