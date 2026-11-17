package com.conductor.creative;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.User;
import com.conductor.marketing.insights.PostBaselineService;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.service.ProjectSecurityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Hold and hook rates are reported per platform and never blended into one number across platforms. */
class CreativePerformancePlatformRatesTest {

    private final ObjectMapper om = new ObjectMapper();

    private PostPublishTarget target(String id, String platform) {
        PostPublishTarget t = new PostPublishTarget();
        t.setId(id);
        t.setPlatform(platform);
        t.setFireTime(OffsetDateTime.now().minusHours(1));
        return t;
    }

    private PostPublishTargetMetric metric(String targetId, String extraJson) throws Exception {
        PostPublishTargetMetric m = new PostPublishTargetMetric();
        m.setTargetId(targetId);
        m.setViews(100L);
        m.setExtra(om.readTree(extraJson));
        return m;
    }

    private CreativePerformanceService.VariantPerformance run(List<PostPublishTarget> targets,
                                                              List<PostPublishTargetMetric> metrics) {
        CreativeRepository creatives = mock(CreativeRepository.class);
        CreativeAttributionResolver resolver = mock(CreativeAttributionResolver.class);
        PostPublishTargetMetricRepository metricRepo = mock(PostPublishTargetMetricRepository.class);
        ProjectSecurityService security = mock(ProjectSecurityService.class);
        PostBaselineService baselines = mock(PostBaselineService.class);
        CreativePerformanceService service = new CreativePerformanceService(creatives, resolver, metricRepo, security, baselines);
        User caller = new User();
        caller.setId("u");
        when(security.isProjectMember("p", "u")).thenReturn(true);
        Creative a = new Creative();
        a.setId("c-a");
        a.setProjectId("p");
        a.setNumber(1);
        a.setVariantLetter("a");
        when(creatives.findByIdAndProjectId("c-a", "p")).thenReturn(Optional.of(a));
        when(creatives.findAllByNumberAndProjectId(1, "p")).thenReturn(List.of(a));
        when(resolver.resolvePublishedTargetsByCreative(anyCollection())).thenReturn(Map.of("c-a", targets));
        when(metricRepo.findLatestAvailableForTargets(anyList())).thenReturn(metrics);
        return service.familyPerformance("p", "c-a", caller).family().get(0);
    }

    @Test
    void holdRateOnTwoPlatformsIsReportedPerPlatformAndTheBlendIsNull() throws Exception {
        var perf = run(List.of(target("t-ig", "instagram"), target("t-yt", "youtube")), List.of(
                metric("t-ig", "{\"ig_reels_avg_watch_time\":5000,\"video_duration_s\":10,\"reels_skip_rate\":20}"),
                metric("t-yt", "{\"avg_view_pct\":30}")));

        assertThat(perf.holdRate()).isNull();
        assertThat(perf.byPlatform()).hasSize(2);
        var ig = perf.byPlatform().stream().filter(p -> p.platform().equals("instagram")).findFirst().orElseThrow();
        var yt = perf.byPlatform().stream().filter(p -> p.platform().equals("youtube")).findFirst().orElseThrow();
        assertThat(ig.holdRate()).isCloseTo(0.5, within(1e-9));
        assertThat(ig.hookRate()).isCloseTo(0.8, within(1e-9));
        assertThat(yt.holdRate()).isCloseTo(0.3, within(1e-9));
        assertThat(yt.hookRate()).isNull();
        // Only one platform reports a hook rate, so the single number is that platform's.
        assertThat(perf.hookRate()).isCloseTo(0.8, within(1e-9));
    }

    @Test
    void aSinglePlatformKeepsItsTopLevelRates() throws Exception {
        var perf = run(List.of(target("t-yt", "youtube")), List.of(metric("t-yt", "{\"avg_view_pct\":40}")));

        assertThat(perf.holdRate()).isCloseTo(0.4, within(1e-9));
        assertThat(perf.byPlatform()).singleElement().satisfies(p -> assertThat(p.holdRate()).isCloseTo(0.4, within(1e-9)));
    }
}
