package com.conductor.integration.ingest.digest;

import com.conductor.integration.Aggregation;
import com.conductor.integration.Direction;
import com.conductor.integration.DigestSpec;
import com.conductor.integration.IngestItem;
import com.conductor.integration.IngestMode;
import com.conductor.integration.IngestSpec;
import com.conductor.integration.MetricSpec;
import com.conductor.integration.ingest.ConnectorFeed;
import com.conductor.integration.ingest.ConnectorFeedDigest;
import com.conductor.integration.ingest.ConnectorFeedDigestRepository;
import com.conductor.integration.ingest.DigestStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link MetricsDigestService} -- the wiring that connects {@link MetricsAggregator} /
 * {@link MetricsChangeDetector} / {@link DigestPayloadBuilder} (all exercised for real here, not
 * mocked) to a persisted {@link ConnectorFeedDigest} row and the feed's baseline. Uses the real math
 * classes deliberately: this class's whole job is orchestration, and mocking the math would hide a
 * wiring bug (wrong argument order, wrong field read) behind a passing test.
 */
class MetricsDigestServiceTest {

    private final ConnectorFeedDigestRepository digestRepository = mock(ConnectorFeedDigestRepository.class);
    private final MetricsDigestService service = new MetricsDigestService(digestRepository, new ObjectMapper());

    private ConnectorFeed feed() {
        ConnectorFeed feed = new ConnectorFeed();
        feed.setId("feed-1");
        feed.setProjectId("proj-1");
        feed.setQuietPeriods(0);
        return feed;
    }

    private IngestSpec metricSpec() {
        MetricSpec clicks = new MetricSpec("clicks", "Clicks", null, Aggregation.SUM, "clicks", null,
                null, null, Direction.UP_IS_GOOD, 50.0, null, null);
        DigestSpec digest = new DigestSpec("trend", "date", List.of(clicks), List.of(), "marketing/metrics/x.md", null);
        return new IngestSpec("weekly", "label", "desc", IngestMode.SNAPSHOT, "op",
                "metrics.digest.{connector}.{ingest}", null, null, "KNOWLEDGE", "marketing", digest);
    }

    private IngestItem itemWithPeriodKey(String periodKey, Map<String, Object> payload) throws Exception {
        String json = new ObjectMapper().writeValueAsString(payload);
        return new IngestItem("metrics.digest.gsc.weekly", "ref", null, "application/json", json,
                null, "dedup-1", Map.of("periodKey", periodKey));
    }

    @Test
    void missingPeriodKeyMetadata_throws() {
        IngestItem item = new IngestItem("t", "ref", null, "application/json", "{}", null, "d1", Map.of());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.record(feed(), metricSpec(), item, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("periodKey");
    }

    @Test
    void alreadyDigestedPeriod_isANoOp() throws Exception {
        when(digestRepository.findByFeedIdAndPeriodKey("feed-1", "2026-W30"))
                .thenReturn(Optional.of(new ConnectorFeedDigest()));
        IngestItem item = itemWithPeriodKey("2026-W30", Map.of("trend", List.of()));

        service.record(feed(), metricSpec(), item, null);

        verify(digestRepository, never()).save(any());
    }

    @Test
    void firstEverPeriod_hasNoPriorBaselineSoIsNeverMaterialButStillPersistsSkippedDigestPlusBaseline() throws Exception {
        when(digestRepository.findByFeedIdAndPeriodKey("feed-1", "2026-W30")).thenReturn(Optional.empty());
        Map<String, Object> payload = Map.of("trend", List.of(Map.of("date", "2026-07-20", "clicks", 500)));
        IngestItem item = itemWithPeriodKey("2026-W30", payload);
        ConnectorFeed feed = feed();

        service.record(feed, metricSpec(), item, null);

        ArgumentCaptor<ConnectorFeedDigest> captor = ArgumentCaptor.forClass(ConnectorFeedDigest.class);
        verify(digestRepository).save(captor.capture());
        ConnectorFeedDigest saved = captor.getValue();
        assertThat(saved.getFeedId()).isEqualTo("feed-1");
        assertThat(saved.getProjectId()).isEqualTo("proj-1");
        assertThat(saved.getPeriodKey()).isEqualTo("2026-W30");
        // No prior baseline means value==last by construction (delta 0) -- never material on the very
        // first period, same as MetricsChangeDetectorTest's own coverage of this case.
        assertThat(saved.isMaterial()).isFalse();
        assertThat(saved.getStatus()).isEqualTo(DigestStatus.SKIPPED);
        assertThat(saved.getDedupKey()).isEqualTo("knowledge-digest:feed-1:2026-W30");
        assertThat(saved.getChangeReport()).containsKey("metrics");

        // Baseline persisted onto the feed for the next pull to read back regardless of materiality.
        assertThat(feed.getLastStats()).isNotNull();
        assertThat(feed.getQuietPeriods()).isEqualTo(1);
    }

    @Test
    void bigJumpAfterBaselineEstablished_isMaterialAndPersistsPendingDigest() throws Exception {
        ConnectorFeed feed = feed();
        IngestSpec spec = metricSpec();
        when(digestRepository.findByFeedIdAndPeriodKey("feed-1", "period-1")).thenReturn(Optional.empty());
        when(digestRepository.findByFeedIdAndPeriodKey("feed-1", "period-2")).thenReturn(Optional.empty());

        service.record(feed, spec, itemWithPeriodKey("period-1",
                Map.of("trend", List.of(Map.of("date", "2026-07-20", "clicks", 500)))), null);
        service.record(feed, spec, itemWithPeriodKey("period-2",
                Map.of("trend", List.of(Map.of("date", "2026-07-21", "clicks", 5000)))), null);

        ArgumentCaptor<ConnectorFeedDigest> captor = ArgumentCaptor.forClass(ConnectorFeedDigest.class);
        verify(digestRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        ConnectorFeedDigest secondSave = captor.getAllValues().get(1);
        assertThat(secondSave.getPeriodKey()).isEqualTo("period-2");
        assertThat(secondSave.isMaterial()).isTrue();
        assertThat(secondSave.getStatus()).isEqualTo(DigestStatus.PENDING);
        assertThat(feed.getQuietPeriods()).isEqualTo(0);
    }

    @Test
    void steadyRepeatedPeriod_isNonMaterialAndPersistsSkippedDigest() throws Exception {
        ConnectorFeed feed = feed();
        MetricsAggregator aggregator = new MetricsAggregator();
        MetricsChangeDetector detector = new MetricsChangeDetector();
        IngestSpec spec = metricSpec();

        // Feed a handful of identical periods first (through the real detector) so a steady baseline
        // with a non-zero variance signal exists, then assert the NEXT identical period is skipped.
        Map<String, Object> flatPayload = Map.of("trend", List.of(Map.of("date", "2026-07-20", "clicks", 4200)));
        MetricsBaseline baseline = null;
        for (int i = 0; i < 6; i++) {
            MetricsSnapshot snapshot = aggregator.aggregate(flatPayload, spec, null);
            ChangeDetectionResult result = detector.detect(snapshot, baseline, spec.digest(), "period-" + i, 0);
            baseline = result.updatedBaseline();
        }
        feed.setLastStats(new ObjectMapper().convertValue(baseline, Map.class));
        feed.setQuietPeriods(0);

        when(digestRepository.findByFeedIdAndPeriodKey("feed-1", "period-final")).thenReturn(Optional.empty());
        IngestItem item = itemWithPeriodKey("period-final", flatPayload);

        service.record(feed, spec, item, null);

        ArgumentCaptor<ConnectorFeedDigest> captor = ArgumentCaptor.forClass(ConnectorFeedDigest.class);
        verify(digestRepository).save(captor.capture());
        ConnectorFeedDigest saved = captor.getValue();
        assertThat(saved.isMaterial()).isFalse();
        assertThat(saved.getStatus()).isEqualTo(DigestStatus.SKIPPED);
    }
}
