package com.conductor.marketing.insights;

import com.conductor.creative.CreativeTagAttribution.TagValue;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.marketing.insights.PostBaselineService.Baseline;
import com.conductor.marketing.insights.PostBaselineService.Baselines;
import com.conductor.marketing.insights.PostBaselineService.LiftSummary;
import com.conductor.marketing.insights.PostBaselineService.Observation;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Settle windows, the baseline fallback, the lift maths, the verdict rules and tag grouping. */
class PostBaselineServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-09T12:00:00Z");

    private final PostPublishTargetRepository targets = mock(PostPublishTargetRepository.class);
    private final PostPublishTargetMetricRepository metrics = mock(PostPublishTargetMetricRepository.class);
    private final PostBaselineService service = new PostBaselineService(targets, metrics);
    private int n;

    private static Baselines baselines(Map<String, Baseline> platformFormat, Map<String, Baseline> platform) {
        return new Baselines(platformFormat, platform, List.of(), List.of(), NOW.minusDays(90), NOW);
    }

    @Test
    void aFacebookPostAtFourDaysIsUnsettledWhileATikTokPostAtFourDaysIsSettled() {
        OffsetDateTime fourDaysAgo = NOW.minusDays(4);
        assertThat(PostSettleWindows.isSettled("facebook", fourDaysAgo, NOW)).isFalse();
        assertThat(PostSettleWindows.isSettled("tiktok", fourDaysAgo, NOW)).isTrue();
        assertThat(PostSettleWindows.isSettled("instagram", NOW.minusHours(71), NOW)).isFalse();
        assertThat(PostSettleWindows.isSettled("youtube", NOW.minusHours(72), NOW)).isTrue();
        assertThat(PostSettleWindows.isSettled("facebook", NOW.minusDays(7), NOW)).isTrue();
        assertThat(PostSettleWindows.isSettled("somewhere-else", NOW.minusHours(80), NOW)).isTrue();
        assertThat(PostSettleWindows.isSettled("somewhere-else", NOW.minusHours(10), NOW)).isFalse();
    }

    @Test
    void theBaselineFallsBackFromPlatformAndFormatToPlatformToNone() {
        List<PostPublishTarget> all = new ArrayList<>();
        List<PostPublishTargetMetric> latest = new ArrayList<>();
        for (int i = 0; i < 10; i++) add(all, latest, "tiktok", "REEL", NOW.minusDays(10), 1000L, null);
        for (int i = 0; i < 2; i++) add(all, latest, "tiktok", "FEED", NOW.minusDays(10), 50L, null);
        for (int i = 0; i < 3; i++) add(all, latest, "instagram", "REEL", NOW.minusDays(10), 200L, null);
        for (int i = 0; i < 12; i++) add(all, latest, "facebook", "FEED", NOW.minusDays(3), 999L, null);
        when(targets.findPublishedByFireTimeWindow(anyString(), any(), any(), any())).thenReturn(all);
        when(metrics.findLatestAvailableForTargets(anyList())).thenReturn(latest);

        Baselines b = service.baselines("p", NOW);

        assertThat(b.forBucket("tiktok", "REEL").medianViews()).isEqualTo(1000.0);
        assertThat(b.forBucket("tiktok", "FEED").medianViews()).isEqualTo(1000.0); // the platform's median, not 50
        assertThat(b.forBucket("instagram", "REEL")).isNull();
        assertThat(b.forBucket("facebook", "FEED")).isNull(); // unsettled posts never make a baseline
        assertThat(b.lift(obs("instagram", "REEL", 500L)).viewLift()).isNull();
        assertThat(b.settled()).hasSize(15);
        assertThat(b.unsettled()).hasSize(12);
        assertThat(b.to()).isEqualTo(NOW);
        assertThat(b.from()).isEqualTo(NOW.minusDays(90));
    }

    @Test
    void aViralOutlierBarelyMovesTheMedianLiftWhilePooledViewsJump() {
        Baselines b = baselines(Map.of(PostBaselineService.key("tiktok", "REEL"), new Baseline(100.0, null)), Map.of());
        List<Observation> posts = new ArrayList<>();
        for (long views : new long[] {80, 90, 100, 110, 120}) posts.add(obs("tiktok", "REEL", views));
        double before = b.summarize(posts).medianViewLift();
        long pooledBefore = posts.stream().mapToLong(Observation::views).sum();

        posts.add(obs("tiktok", "REEL", 40_000L));
        double after = b.summarize(posts).medianViewLift();
        long pooledAfter = posts.stream().mapToLong(Observation::views).sum();

        assertThat(pooledAfter).isGreaterThan(pooledBefore * 50);
        assertThat(before).isCloseTo(1.0, within(1e-9));
        assertThat(after).isCloseTo(1.05, within(1e-9));
        LiftSummary s = b.summarize(posts);
        assertThat(s.beatBaselineShare()).isCloseTo(3.0 / 6, within(1e-9));
        assertThat(s.withBaselinePosts()).isEqualTo(6);
        assertThat(s.beatBaselinePosts()).isEqualTo(3);
    }

    @Test
    void retentionLiftNeedsBothSidesAndEngagementIsAMedianOfPerPostRates() {
        Baselines b = baselines(Map.of(), Map.of("youtube", new Baseline(1000.0, 40.0)));
        Observation a = new Observation("a", "youtube", "SHORT", NOW.minusDays(9), 1000L, 100L, 0L, 0L, 0L, 60.0);
        Observation noPct = new Observation("b", "youtube", "SHORT", NOW.minusDays(9), 2000L, 20L, 0L, 0L, 0L, null);
        Observation noViews = new Observation("c", "youtube", "SHORT", NOW.minusDays(9), null, 5L, 0L, 0L, 0L, 20.0);

        assertThat(b.lift(a).retentionLift()).isCloseTo(1.5, within(1e-9));
        assertThat(b.lift(noPct).retentionLift()).isNull();
        assertThat(b.lift(noViews).viewLift()).isNull();
        LiftSummary s = b.summarize(List.of(a, noPct, noViews));
        assertThat(s.medianRetentionLift()).isCloseTo(1.0, within(1e-9));
        assertThat(s.withRetentionBaselinePosts()).isEqualTo(2);
        assertThat(s.beatRetentionBaselinePosts()).isEqualTo(1);
        assertThat(s.medianEngagementRate()).isCloseTo((0.1 + 0.01) / 2, within(1e-9));
        LiftSummary none = b.summarize(List.of());
        assertThat(none.medianViewLift()).isNull();
        assertThat(none.viewVerdict()).isEqualTo("NOT_ENOUGH_DATA");
    }

    @Test
    void verdictBoundaries() {
        // WORKS needs median >= 1.2 AND at least 2/3 beating the baseline.
        assertThat(LessonVerdictRules.verdict(3, 2, 1.2)).isEqualTo("WORKS");
        assertThat(LessonVerdictRules.verdict(3, 2, 1.19)).isEqualTo("NO_DIFFERENCE");
        assertThat(LessonVerdictRules.verdict(3, 1, 2.0)).isEqualTo("NO_DIFFERENCE"); // lift is high but only 1/3 beat it
        assertThat(LessonVerdictRules.verdict(6, 4, 1.5)).isEqualTo("WORKS");
        assertThat(LessonVerdictRules.verdict(6, 3, 1.5)).isEqualTo("NO_DIFFERENCE");
        // DOESNT_WORK needs median <= 0.8 AND at most 1/3 beating it.
        assertThat(LessonVerdictRules.verdict(3, 1, 0.8)).isEqualTo("DOESNT_WORK");
        assertThat(LessonVerdictRules.verdict(3, 1, 0.81)).isEqualTo("NO_DIFFERENCE");
        assertThat(LessonVerdictRules.verdict(3, 2, 0.5)).isEqualTo("NO_DIFFERENCE");
        assertThat(LessonVerdictRules.verdict(3, 0, 0.1)).isEqualTo("DOESNT_WORK");
        assertThat(LessonVerdictRules.verdict(6, 2, 0.8)).isEqualTo("DOESNT_WORK");
        assertThat(LessonVerdictRules.verdict(6, 3, 0.8)).isEqualTo("NO_DIFFERENCE");
        // Fewer than 3 posts with the baseline, or no median: never a verdict.
        assertThat(LessonVerdictRules.verdict(2, 2, 5.0)).isEqualTo("NOT_ENOUGH_DATA");
        assertThat(LessonVerdictRules.verdict(0, 0, null)).isEqualTo("NOT_ENOUGH_DATA");
    }

    @Test
    void theDefaultVerdictReadsRetentionOnlyWhenEnoughPostsHaveItAndTheyAreMostOfThePosts() {
        // 3 of 4 posts with retention: at least 3 and at least 2/3, so retention. Same for every tag key.
        LiftSummary covered = new LiftSummary(1.5, 1.0, 0.05, 0.7, 4, 4, 3, 0, "WORKS", "DOESNT_WORK");
        assertThat(LessonVerdictRules.defaultFor(covered))
                .isEqualTo(new LessonVerdictRules.Default("DOESNT_WORK", "RETENTION_LIFT"));
        // Only 2 retention posts: under MIN_POSTS.
        LiftSummary few = new LiftSummary(1.5, 1.0, 0.05, 0.7, 2, 2, 2, 0, "WORKS", "NOT_ENOUGH_DATA");
        assertThat(LessonVerdictRules.defaultFor(few)).isEqualTo(new LessonVerdictRules.Default("WORKS", "VIEW_LIFT"));
        // 3 retention posts but under 2/3 of 5 view posts.
        LiftSummary thin = new LiftSummary(1.5, 1.0, 0.05, 0.7, 5, 5, 3, 0, "WORKS", "DOESNT_WORK");
        assertThat(LessonVerdictRules.defaultFor(thin)).isEqualTo(new LessonVerdictRules.Default("WORKS", "VIEW_LIFT"));
    }

    @Test
    void twentyTikTokPostsAtViewLiftOnePointFiveBeatThreeYouTubePostsAtRetentionPointSeven() {
        Baselines b = baselines(Map.of(), Map.of("tiktok", new Baseline(100.0, null), "youtube", new Baseline(100.0, 40.0)));
        List<Observation> posts = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            posts.add(new Observation("tt" + i, "tiktok", "REEL", NOW.minusDays(9), 150L, 0L, 0L, 0L, 0L, null));
        }
        for (int i = 0; i < 3; i++) {
            posts.add(new Observation("yt" + i, "youtube", "SHORT", NOW.minusDays(9), 150L, 0L, 0L, 0L, 0L, 28.0));
        }

        LiftSummary lift = b.summarize(posts);
        LessonVerdictRules.Default verdict = LessonVerdictRules.defaultFor(lift);

        assertThat(lift.withRetentionBaselinePosts()).isEqualTo(3);
        assertThat(lift.medianRetentionLift()).isCloseTo(0.7, within(1e-9));
        assertThat(lift.retentionVerdict()).isEqualTo("DOESNT_WORK"); // the YouTube-only read would have said so
        assertThat(verdict).isEqualTo(new LessonVerdictRules.Default("WORKS", "VIEW_LIFT"));
    }

    @Test
    void aBucketNeedsTenPostsWithTheMetricNotTenPostsAndTheBaselineIsChosenPerMetric() {
        List<PostPublishTarget> all = new ArrayList<>();
        List<PostPublishTargetMetric> latest = new ArrayList<>();
        // youtube SHORT: 10 posts, only 1 with retention -> a views baseline, no retention baseline.
        for (int i = 0; i < 10; i++) {
            add(all, latest, "youtube", "SHORT", NOW.minusDays(10), 1000L, i == 0 ? 50.0 : null);
        }
        // youtube LONG: 10 posts all with retention -> its own retention baseline, and views.
        for (int i = 0; i < 10; i++) add(all, latest, "youtube", "LONG", NOW.minusDays(10), 2000L, 40.0);
        when(targets.findPublishedByFireTimeWindow(anyString(), any(), any(), any())).thenReturn(all);
        when(metrics.findLatestAvailableForTargets(anyList())).thenReturn(latest);

        Baselines b = service.baselines("p", NOW);

        Baseline shortForm = b.forBucket("youtube", "SHORT");
        assertThat(shortForm.medianViews()).isEqualTo(1000.0);
        // No retention in SHORT (1 value): that metric falls back to the youtube platform bucket, 11 values.
        assertThat(shortForm.medianAvgViewPct()).isEqualTo(40.0);
        assertThat(b.forBucket("youtube", "LONG").medianAvgViewPct()).isEqualTo(40.0);

        // A platform whose only retention values are too few has no retention baseline at all.
        List<PostPublishTarget> few = new ArrayList<>();
        List<PostPublishTargetMetric> fewLatest = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            add(few, fewLatest, "youtube", "SHORT", NOW.minusDays(10), 1000L, i < 2 ? 50.0 : null);
        }
        when(targets.findPublishedByFireTimeWindow(anyString(), any(), any(), any())).thenReturn(few);
        when(metrics.findLatestAvailableForTargets(anyList())).thenReturn(fewLatest);
        Baseline sparse = service.baselines("p", NOW).forBucket("youtube", "SHORT");
        assertThat(sparse.medianViews()).isEqualTo(1000.0);
        assertThat(sparse.medianAvgViewPct()).isNull();
    }

    @Test
    void lessonsSplitIntoOneBucketPerIdAndPreviewRoundsIsNotGrouped() {
        Observation o = obs("tiktok", "REEL", 100L);
        Map<String, Set<TagValue>> tags = Map.of(o.targetId(), Set.of(
                new TagValue("lessons", "#3,#12"), new TagValue("previewRounds", "2"), new TagValue("hookType", "question")));

        Map<TagValue, List<Observation>> grouped = TagGrouping.group(List.of(o), tags);

        assertThat(grouped.keySet()).containsExactlyInAnyOrder(
                new TagValue("lessons", "#3"), new TagValue("lessons", "#12"), new TagValue("hookType", "question"));
        assertThat(TagGrouping.followedLessons(o.targetId(), tags)).isTrue();
        assertThat(TagGrouping.followedLessons("other", tags)).isFalse();
    }

    private Observation obs(String platform, String format, long views) {
        return new Observation("o" + (++n), platform, format, NOW.minusDays(9), views, 0L, 0L, 0L, 0L, null);
    }

    private void add(List<PostPublishTarget> all, List<PostPublishTargetMetric> latest, String platform, String format,
                     OffsetDateTime fireTime, long views, Double avgViewPct) {
        PostPublishTarget t = new PostPublishTarget();
        t.setId("t" + (++n));
        t.setPlatform(platform);
        t.setFormat(format);
        t.setFireTime(fireTime);
        all.add(t);
        PostPublishTargetMetric m = new PostPublishTargetMetric();
        m.setTargetId(t.getId());
        m.setViews(views);
        if (avgViewPct != null) {
            m.setExtra(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("avg_view_pct", avgViewPct));
        }
        latest.add(m);
    }
}
