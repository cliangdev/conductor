package com.conductor.integration.ingest.digest;

import com.conductor.integration.IngestItem;
import com.conductor.integration.IngestSpec;
import com.conductor.integration.IngestWindow;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.integration.ingest.ConnectorFeedDigest;
import com.conductor.integration.ingest.ConnectorFeedDigestRepository;
import com.conductor.integration.ingest.DigestStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * The wiring the rest of the {@code digest} package's pure math classes were built for but never had:
 * turns one metric-feed {@link IngestItem} (the whole projected snapshot, as JSON) into an
 * up-to-date {@code connector_feed.last_stats} baseline and a {@link ConnectorFeedDigest} row, via
 * {@link MetricsAggregator} → {@link MetricsChangeDetector} → {@link DigestPayloadBuilder}. Called
 * from {@code FeedPullService} INSTEAD OF the generic {@code DigestSink} for any {@link
 * IngestSpec#isMetricFeed()} item -- a metric feed's raw projected snapshot must never reach the
 * Knowledge Center inbox directly (that would defeat the entire point of the digest pipeline: only a
 * narrated, model-written summary of the already-computed changes is meant to end up in the wiki, via
 * the (later) narrator + {@code DigestSubmissionService}).
 *
 * <p><b>Idempotency:</b> {@link ConnectorFeedDigest} has a {@code UNIQUE (feed_id, period_key)}
 * constraint, and this class treats an existing row for the item's period as proof the period was
 * already digested -- it returns without touching the change-detector or the feed's baseline again.
 * This is what makes a repeat pull for the same period (e.g. a crash between this call and
 * {@code FeedPullService}'s cursor advance, which re-pulls the same period next time) safe: without it,
 * the EWMA baseline would silently update twice for one real period, corrupting the statistical gate.
 */
@Service
public class MetricsDigestService {

    private static final Logger log = LoggerFactory.getLogger(MetricsDigestService.class);

    private final ConnectorFeedDigestRepository digestRepository;
    // MetricsAggregator/MetricsChangeDetector/DigestPayloadBuilder are deliberately plain, Spring-free
    // classes (see their own javadoc: "no Spring, safe to new directly in a unit test") -- `new`ing them
    // here rather than injecting keeps that true instead of forcing @Component onto pure math classes
    // just to satisfy this one Spring-managed caller.
    private final MetricsAggregator aggregator = new MetricsAggregator();
    private final MetricsChangeDetector changeDetector = new MetricsChangeDetector();
    private final DigestPayloadBuilder payloadBuilder = new DigestPayloadBuilder();
    private final ObjectMapper objectMapper;

    public MetricsDigestService(ConnectorFeedDigestRepository digestRepository, ObjectMapper objectMapper) {
        this.digestRepository = digestRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Processes one metric-feed item against {@code feed}. Mutates {@code feed}'s {@code lastStats}/
     * {@code quietPeriods}/{@code lastWindowStart}/{@code lastWindowEnd} in place -- the caller
     * ({@code FeedPullService}) is responsible for persisting {@code feed} itself, same as it already
     * does for the cursor fields {@link #record} doesn't touch. Persists the resulting {@link
     * ConnectorFeedDigest} row directly, since that's a brand-new row this class alone owns the shape of.
     */
    public void record(ConnectorFeed feed, IngestSpec spec, IngestItem item, IngestWindow window) {
        String periodKey = periodKeyOf(item);
        if (digestRepository.findByFeedIdAndPeriodKey(feed.getId(), periodKey).isPresent()) {
            log.debug("Digest already recorded for feed={} period={} -- skipping (idempotent re-pull)",
                    feed.getId(), periodKey);
            return;
        }

        Map<String, Object> rawPayload = readPayload(item);
        MetricsSnapshot snapshot = aggregator.aggregate(rawPayload, spec, window);
        MetricsBaseline previous = readBaseline(feed);
        ChangeDetectionResult result = changeDetector.detect(snapshot, previous, spec.digest(), periodKey, feed.getQuietPeriods());

        feed.setLastStats(objectMapper.convertValue(result.updatedBaseline(), new TypeReference<Map<String, Object>>() { }));
        feed.setQuietPeriods(result.quietPeriods());
        if (window != null) {
            feed.setLastWindowStart(OffsetDateTime.ofInstant(window.start(), ZoneOffset.UTC));
            feed.setLastWindowEnd(OffsetDateTime.ofInstant(window.end(), ZoneOffset.UTC));
        }

        ConnectorFeedDigest digest = new ConnectorFeedDigest();
        digest.setProjectId(feed.getProjectId());
        digest.setFeedId(feed.getId());
        digest.setPeriodKey(periodKey);
        if (window != null) {
            digest.setWindowStart(OffsetDateTime.ofInstant(window.start(), ZoneOffset.UTC));
            digest.setWindowEnd(OffsetDateTime.ofInstant(window.end(), ZoneOffset.UTC));
        }
        digest.setChangeReport(payloadBuilder.build(spec, periodKey, result));
        digest.setMaterial(result.material());
        // Computed once, here, at row-creation time -- never recomputed downstream (see
        // DigestSubmissionService), and deliberately excludes the numbers: the PERIOD is the unit of
        // knowledge, so re-narrating the same period after a fix collapses instead of duplicating.
        digest.setDedupKey("knowledge-digest:" + feed.getId() + ":" + periodKey);
        digest.setStatus(result.material() ? DigestStatus.PENDING : DigestStatus.SKIPPED);
        digestRepository.save(digest);

        log.info("Recorded {} digest for feed={} period={} material={}",
                digest.getStatus(), feed.getId(), periodKey, result.material());
    }

    /** {@code SnapshotIngestAdapter} always stamps this; a metric feed pulled through a future custom
     *  {@code IngestConnector} without it is a contract violation worth failing loudly on, the same way
     *  {@code LibrarianDispatchService#buildPayload} prefers throwing over silently stranding data. */
    private String periodKeyOf(IngestItem item) {
        Object periodKey = item.metadata() != null ? item.metadata().get("periodKey") : null;
        if (!(periodKey instanceof String s) || s.isBlank()) {
            throw new IllegalStateException(
                    "Metric-feed item for '" + item.sourceType() + "' is missing metadata.periodKey");
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readPayload(IngestItem item) {
        try {
            return objectMapper.readValue(item.payload(), Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse metric-feed item payload for '" + item.sourceType() + "'", e);
        }
    }

    private MetricsBaseline readBaseline(ConnectorFeed feed) {
        if (feed.getLastStats() == null) {
            return null;
        }
        return objectMapper.convertValue(feed.getLastStats(), MetricsBaseline.class);
    }
}
