package com.conductor.integration.ingest.digest;

import com.conductor.integration.Aggregation;
import com.conductor.integration.Direction;
import com.conductor.integration.DigestSpec;
import com.conductor.integration.DimensionSpec;
import com.conductor.integration.IngestMode;
import com.conductor.integration.IngestSpec;
import com.conductor.integration.MetricSpec;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DigestPayloadBuilderTest {

    private final DigestPayloadBuilder builder = new DigestPayloadBuilder();
    private final ObjectMapper mapper = new ObjectMapper();

    private IngestSpec gscLikeSpec() {
        MetricSpec clicks = new MetricSpec("clicks", "Clicks", null, Aggregation.SUM, "clicks", null,
                null, null, Direction.UP_IS_GOOD, 50.0, null, null);
        MetricSpec position = new MetricSpec("position", "Avg. position", null, Aggregation.WEIGHTED_MEAN,
                "position", "impressions", null, null, Direction.DOWN_IS_GOOD, 0.8, 0.05, null);
        DimensionSpec topQueries = new DimensionSpec("topQueries", "Top queries", "query", "clicks", 8, 25,
                25.0, 0.30, 5);
        DigestSpec digest = new DigestSpec("trend", "date", List.of(clicks, position), List.of(topQueries),
                "marketing/metrics/organic-search.md", null);
        return new IngestSpec("search_analytics_weekly", "GSC weekly digest", "desc", IngestMode.SNAPSHOT,
                "search_analytics", "metrics.digest.{connector}.{ingest}", null, null, "KNOWLEDGE",
                "marketing", digest);
    }

    private ChangeDetectionResult resultWith(List<MetricChange> changes, Map<String, List<DimensionMover>> movers,
                                             boolean material, String reason) {
        MetricsBaseline baseline = new MetricsBaseline("2026-W30", Map.of(), Map.of());
        return new ChangeDetectionResult(material, reason, 0, baseline, changes, movers);
    }

    @Test
    void payloadContainsNoSeriesPathKeyAndNoDimensionRowsBeyondTopN() throws Exception {
        IngestSpec spec = gscLikeSpec();
        List<MetricChange> changes = List.of(
                new MetricChange("clicks", 4300, 4200, 100, true, false, 4210, 400),
                new MetricChange("position", 12.0, 12.0, 0.0, false, false, 12.0, 0.1));
        Map<String, List<DimensionMover>> movers = Map.of("topQueries", List.of(
                new DimensionMover("q1", MoverKind.ENTERED, null, 1, null, 300.0),
                new DimensionMover("q2", MoverKind.ROSE, 4, 2, 100.0, 150.0)));

        ChangeDetectionResult result = resultWith(changes, movers, true, null);
        Map<String, Object> payload = builder.build(spec, "2026-W30", result);
        String json = mapper.writeValueAsString(payload);

        // The daily trend series (seriesPath == "trend") must never travel to the narrator.
        assertThat(json).doesNotContain("\"trend\"");
        // Only the two computed movers appear -- never a raw/full dimension row list.
        assertThat(json).contains("\"q1\"").contains("\"q2\"");
        assertThat(mapper.readTree(json).get("dimensions").get("topQueries")).hasSize(2);
    }

    @Test
    void includesNonMaterialMetricsWithMaterialFalse() {
        IngestSpec spec = gscLikeSpec();
        List<MetricChange> changes = List.of(
                new MetricChange("position", 12.0, 12.0, 0.0, false, false, 12.0, 0.1));
        ChangeDetectionResult result = resultWith(changes, Map.of(), false, null);

        Map<String, Object> payload = builder.build(spec, "2026-W30", result);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> metrics = (List<Map<String, Object>>) payload.get("metrics");
        assertThat(metrics).hasSize(1);
        assertThat(metrics.get(0).get("material")).isEqualTo(false);
        assertThat(metrics.get(0).get("key")).isEqualTo("position");
    }

    @Test
    void surfacesPeriodMoversComparedToLowConfidenceReasonDomainAndPagePath() {
        IngestSpec spec = gscLikeSpec();
        List<MetricChange> changes = List.of(
                new MetricChange("clicks", 100, 100, 0, false, true, 100, 0));
        ChangeDetectionResult result = resultWith(changes, Map.of(), true, "steady_state");

        Map<String, Object> payload = builder.build(spec, "2026-W30", result);

        assertThat(((Map<?, ?>) payload.get("period")).get("key")).isEqualTo("2026-W30");
        assertThat(payload.get("moversComparedTo")).isNotNull();
        assertThat(payload.get("lowConfidence")).isEqualTo(true);
        assertThat(payload.get("reason")).isEqualTo("steady_state");
        assertThat(payload.get("suggestedDomain")).isEqualTo("marketing");
        assertThat(payload.get("pagePath")).isEqualTo("marketing/metrics/organic-search.md");
    }
}
