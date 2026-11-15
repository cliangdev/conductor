package com.conductor.integration.connector.marketing;

import com.conductor.creative.CreativeTagAttribution;
import com.conductor.marketing.insights.LessonVerdictRules;
import com.conductor.marketing.insights.PostBaselineService;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.WorkItemRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** byHookType rows are ranked and filtered on the lift their verdict was read from. */
class MarketingInsightsSnapshotQueryHookTypeTest {

    private static PostBaselineService.Observation obs(String id) {
        return new PostBaselineService.Observation(id, "instagram", "REEL", OffsetDateTime.now().minusDays(10),
                100L, 0L, 0L, 0L, 0L, null, 0.5, null);
    }

    private static PostBaselineService.LiftSummary lift(Double viewLift, Double hookLift, int withBaseline, int withHook) {
        return new PostBaselineService.LiftSummary(viewLift, null, null, null, withBaseline, 0, 0, 0,
                LessonVerdictRules.NOT_ENOUGH_DATA, LessonVerdictRules.NOT_ENOUGH_DATA, hookLift, null, withHook, 0,
                LessonVerdictRules.WORKS);
    }

    @Test
    void hookLiftRowsAreKeptWithoutAViewLiftAndRankedOnTheirHookLift() {
        PostBaselineService.Observation a = obs("t-a");
        PostBaselineService.Observation b = obs("t-b");
        PostBaselineService.Observation c = obs("t-c");
        PostBaselineService.Observation d = obs("t-d");
        CreativeTagAttribution tags = mock(CreativeTagAttribution.class);
        when(tags.tagsByTarget(anyString())).thenReturn(Map.of(
                "t-a", Set.of(new CreativeTagAttribution.TagValue("hookType", "question")),
                "t-b", Set.of(new CreativeTagAttribution.TagValue("hookType", "bold-claim")),
                "t-c", Set.of(new CreativeTagAttribution.TagValue("hookType", "stat")),
                "t-d", Set.of(new CreativeTagAttribution.TagValue("hookType", "story"))));
        PostBaselineService.Baselines evidence = mock(PostBaselineService.Baselines.class);
        when(evidence.settled()).thenReturn(List.of(a, b, c, d));
        // question: hook-based verdict, high view lift but low hook lift; bold-claim: hook lift only, no view lift;
        // stat: view-based verdict (no hook coverage); story: view-based but no view lift at all (dropped).
        when(evidence.summarize(List.of(a))).thenReturn(lift(3.0, 1.1, 3, 3));
        when(evidence.summarize(List.of(b))).thenReturn(lift(null, 1.8, 0, 3));
        when(evidence.summarize(List.of(c))).thenReturn(lift(1.5, null, 3, 0));
        when(evidence.summarize(List.of(d))).thenReturn(lift(null, null, 0, 0));
        PostBaselineService baselines = mock(PostBaselineService.class);
        when(baselines.baselines(anyString(), any(OffsetDateTime.class))).thenReturn(evidence);
        PostPublishTargetRepository targets = mock(PostPublishTargetRepository.class);
        when(targets.findPublishedWithWorkItemByFireTimeWindow(anyString(), any(), any())).thenReturn(List.of());
        PostPublishTargetMetricRepository metrics = mock(PostPublishTargetMetricRepository.class);
        when(metrics.findLatestAvailableForTargets(anyList())).thenReturn(List.of());
        WorkItemRepository workItems = mock(WorkItemRepository.class);
        MarketingInsightsSnapshotQuery query =
                new MarketingInsightsSnapshotQuery(targets, metrics, workItems, tags, baselines);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = new ArrayList<>((List<Map<String, Object>>) query
                .fetch("p", Instant.now()).get("byHookType"));

        // Sorted on each row's own basis: bold-claim 1.8 (hook), stat 1.5 (view), question 1.1 (hook, not its 3.0 view lift).
        assertThat(rows).extracting(r -> r.get("hookType")).containsExactly("bold-claim", "stat", "question");
        assertThat(rows.get(0).get("verdictMetric")).isEqualTo(LessonVerdictRules.HOOK_LIFT);
        assertThat(rows.get(0).get("medianViewLift")).isNull();
        assertThat(rows.get(0).get("medianHookLift")).isEqualTo(1.8);
        assertThat(rows.get(1).get("verdictMetric")).isEqualTo(LessonVerdictRules.VIEW_LIFT);
    }
}
