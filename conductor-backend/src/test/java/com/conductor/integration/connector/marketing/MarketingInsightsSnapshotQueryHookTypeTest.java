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

        // WORKS hook rows first (bold-claim 1.8 over question 1.1, not its 3.0 view lift), then the
        // NOT_ENOUGH_DATA view row.
        assertThat(rows).extracting(r -> r.get("hookType")).containsExactly("bold-claim", "question", "stat");
        assertThat(rows.get(0).get("verdictMetric")).isEqualTo(LessonVerdictRules.HOOK_LIFT);
        assertThat(rows.get(0).get("medianViewLift")).isNull();
        assertThat(rows.get(0).get("medianHookLift")).isEqualTo(1.8);
        assertThat(rows.get(2).get("verdictMetric")).isEqualTo(LessonVerdictRules.VIEW_LIFT);
    }

    private static PostBaselineService.LiftSummary viewOnly(Double viewLift, String viewVerdict) {
        return new PostBaselineService.LiftSummary(viewLift, null, null, null, 3, 0, 0, 0, viewVerdict,
                LessonVerdictRules.NOT_ENOUGH_DATA, null, null, 0, 0, LessonVerdictRules.NOT_ENOUGH_DATA);
    }

    private static PostBaselineService.LiftSummary hookOnly(Double hookLift, String hookVerdict) {
        return new PostBaselineService.LiftSummary(null, null, null, null, 3, 0, 0, 0,
                LessonVerdictRules.NOT_ENOUGH_DATA, LessonVerdictRules.NOT_ENOUGH_DATA, hookLift, null, 3, 0,
                hookVerdict);
    }

    @Test
    void ordersByVerdictTierThenMetricThenLift() {
        String[] names = {"w-view", "w-hook", "dw-hook", "nd-view", "w-view-hi", "nede"};
        PostBaselineService.LiftSummary[] lifts = {
                viewOnly(2.5, LessonVerdictRules.WORKS), hookOnly(1.3, LessonVerdictRules.WORKS),
                hookOnly(0.5, LessonVerdictRules.DOESNT_WORK), viewOnly(1.0, LessonVerdictRules.NO_DIFFERENCE),
                viewOnly(2.9, LessonVerdictRules.WORKS), viewOnly(1.0, LessonVerdictRules.NOT_ENOUGH_DATA)};
        List<PostBaselineService.Observation> observations = new ArrayList<>();
        Map<String, Set<CreativeTagAttribution.TagValue>> tagMap = new java.util.HashMap<>();
        PostBaselineService.Baselines evidence = mock(PostBaselineService.Baselines.class);
        for (int i = 0; i < names.length; i++) {
            PostBaselineService.Observation o = obs("t-" + i);
            observations.add(o);
            tagMap.put("t-" + i, Set.of(new CreativeTagAttribution.TagValue("hookType", names[i])));
            when(evidence.summarize(List.of(o))).thenReturn(lifts[i]);
        }
        when(evidence.settled()).thenReturn(observations);
        CreativeTagAttribution tags = mock(CreativeTagAttribution.class);
        when(tags.tagsByTarget(anyString())).thenReturn(tagMap);
        PostBaselineService baselines = mock(PostBaselineService.class);
        when(baselines.baselines(anyString(), any(OffsetDateTime.class))).thenReturn(evidence);
        PostPublishTargetRepository targets = mock(PostPublishTargetRepository.class);
        when(targets.findPublishedWithWorkItemByFireTimeWindow(anyString(), any(), any())).thenReturn(List.of());
        PostPublishTargetMetricRepository metrics = mock(PostPublishTargetMetricRepository.class);
        when(metrics.findLatestAvailableForTargets(anyList())).thenReturn(List.of());
        MarketingInsightsSnapshotQuery query = new MarketingInsightsSnapshotQuery(targets, metrics,
                mock(WorkItemRepository.class), tags, baselines);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) query.fetch("p", Instant.now()).get("byHookType");

        // WORKS: hook before view, then lift desc within a metric; then NO_DIFFERENCE, NOT_ENOUGH_DATA, DOESNT_WORK.
        assertThat(rows).extracting(r -> r.get("hookType"))
                .containsExactly("w-hook", "w-view-hi", "w-view", "nd-view", "nede", "dw-hook");
    }
}
