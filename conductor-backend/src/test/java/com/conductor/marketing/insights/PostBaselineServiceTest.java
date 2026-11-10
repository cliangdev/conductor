package com.conductor.marketing.insights;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.WorkItem;
import com.conductor.marketing.insights.PostBaselineService.Baselines;
import com.conductor.marketing.insights.PostBaselineService.Observation;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The settle windows, the baseline's platform+format to platform to none fallback, and the lift maths. */
class PostBaselineServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-09T12:00:00Z");

    private final PostPublishTargetRepository targets = mock(PostPublishTargetRepository.class);
    private final PostPublishTargetMetricRepository metrics = mock(PostPublishTargetMetricRepository.class);
    private final PostBaselineService service = new PostBaselineService(targets, metrics);
    private int n;

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
        // tiktok REEL: 10 posts at 1000 views -> its own bucket.
        for (int i = 0; i < 10; i++) add(all, latest, "tiktok", "REEL", NOW.minusDays(10), 1000L);
        // tiktok FEED: only 2 posts at 50 views -> falls back to the tiktok median (12 posts).
        for (int i = 0; i < 2; i++) add(all, latest, "tiktok", "FEED", NOW.minusDays(10), 50L);
        // instagram: 3 posts, under the minimum -> no baseline at all.
        for (int i = 0; i < 3; i++) add(all, latest, "instagram", "REEL", NOW.minusDays(10), 200L);
        // unsettled facebook posts never count toward a baseline.
        for (int i = 0; i < 12; i++) add(all, latest, "facebook", "FEED", NOW.minusDays(3), 999L);
        when(targets.findPublishedByFireTimeWindow(anyString(), any(), any(), any())).thenReturn(all);
        when(metrics.findLatestAvailableForTargets(anyList())).thenReturn(latest);

        Baselines b = service.baselines("p", NOW);

        assertThat(b.forBucket("tiktok", "REEL").medianViews()).isEqualTo(1000.0);
        assertThat(b.forBucket("tiktok", "FEED").medianViews()).isEqualTo(1000.0); // the platform's median, not 50
        assertThat(b.forBucket("instagram", "REEL")).isNull();
        assertThat(b.forBucket("facebook", "FEED")).isNull();
        assertThat(b.lift(obs("instagram", "REEL", 500L)).viewLift()).isNull();
    }

    @Test
    void aViralOutlierBarelyMovesTheMedianLiftWhilePooledViewsJump() {
        Baselines b = new Baselines(
                java.util.Map.of(PostBaselineService.key("tiktok", "REEL"), new PostBaselineService.Baseline(100.0, null)),
                java.util.Map.of());
        List<Observation> posts = new ArrayList<>();
        for (long views : new long[] {80, 90, 100, 110, 120}) posts.add(obs("tiktok", "REEL", views));
        double before = b.summarize(posts).medianViewLift();
        long pooledBefore = posts.stream().mapToLong(Observation::views).sum();

        posts.add(obs("tiktok", "REEL", 40_000L)); // one viral post, 400x the median
        double after = b.summarize(posts).medianViewLift();
        long pooledAfter = posts.stream().mapToLong(Observation::views).sum();

        assertThat(pooledAfter).isGreaterThan(pooledBefore * 50);
        assertThat(before).isCloseTo(1.0, within(1e-9));
        assertThat(after).isCloseTo(1.05, within(1e-9)); // median of 6 lifts: (1.0 + 1.1) / 2
        assertThat(b.summarize(posts).beatBaselineShare()).isCloseTo(3.0 / 6, within(1e-9));
    }

    @Test
    void retentionLiftNeedsBothSidesAndEngagementIsAMedianOfPerPostRates() {
        Baselines b = new Baselines(java.util.Map.of(), java.util.Map.of("youtube",
                new PostBaselineService.Baseline(1000.0, 40.0)));
        Observation a = new Observation("youtube", "SHORT", NOW.minusDays(9), 1000L, 100L, 0L, 0L, 0L, 60.0);
        Observation noPct = new Observation("youtube", "SHORT", NOW.minusDays(9), 2000L, 20L, 0L, 0L, 0L, null);
        Observation noViews = new Observation("youtube", "SHORT", NOW.minusDays(9), null, 5L, 0L, 0L, 0L, 20.0);

        assertThat(b.lift(a).retentionLift()).isCloseTo(1.5, within(1e-9));
        assertThat(b.lift(noPct).retentionLift()).isNull();
        assertThat(b.lift(noViews).viewLift()).isNull();
        PostBaselineService.LiftSummary s = b.summarize(List.of(a, noPct, noViews));
        assertThat(s.medianRetentionLift()).isCloseTo(1.0, within(1e-9)); // 1.5 and 0.5 (20/40), the median of the two
        assertThat(s.medianEngagementRate()).isCloseTo((0.1 + 0.01) / 2, within(1e-9)); // pooled would be 0.04
        assertThat(b.summarize(List.of())).isEqualTo(new PostBaselineService.LiftSummary(null, null, null, null));
    }

    private static Observation obs(String platform, String format, long views) {
        return new Observation(platform, format, NOW.minusDays(9), views, 0L, 0L, 0L, 0L, null);
    }

    private void add(List<PostPublishTarget> all, List<PostPublishTargetMetric> latest, String platform, String format,
                     OffsetDateTime fireTime, long views) {
        PostPublishTarget t = new PostPublishTarget();
        t.setId("t" + (++n));
        t.setPlatform(platform);
        t.setFormat(format);
        t.setFireTime(fireTime);
        all.add(t);
        PostPublishTargetMetric m = new PostPublishTargetMetric();
        m.setTargetId(t.getId());
        m.setViews(views);
        latest.add(m);
    }
}
