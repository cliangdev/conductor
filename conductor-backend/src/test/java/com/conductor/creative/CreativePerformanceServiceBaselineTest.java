package com.conductor.creative;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.User;
import com.conductor.marketing.insights.PostBaselineService;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.service.ProjectSecurityService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** get_creative's performance call builds the project baseline at most once, however many variants it has. */
class CreativePerformanceServiceBaselineTest {

    @Test
    void aFamilyPerformanceCallBuildsTheBaselineOnce() {
        CreativeRepository creatives = mock(CreativeRepository.class);
        CreativeAttributionResolver resolver = mock(CreativeAttributionResolver.class);
        PostPublishTargetMetricRepository metrics = mock(PostPublishTargetMetricRepository.class);
        ProjectSecurityService security = mock(ProjectSecurityService.class);
        PostBaselineService baselines = mock(PostBaselineService.class);
        CreativePerformanceService service = new CreativePerformanceService(creatives, resolver, metrics, security, baselines);

        User caller = new User();
        caller.setId("u");
        when(security.isProjectMember("p", "u")).thenReturn(true);
        Creative a = creative("c-a", "a");
        Creative b = creative("c-b", "b");
        when(creatives.findByIdAndProjectId("c-a", "p")).thenReturn(Optional.of(a));
        when(creatives.findAllByNumberAndProjectId(1, "p")).thenReturn(List.of(a, b));
        PostPublishTarget ta = new PostPublishTarget();
        ta.setId("t-a");
        PostPublishTarget tb = new PostPublishTarget();
        tb.setId("t-b");
        when(resolver.resolvePublishedTargetsByCreative(anyCollection())).thenReturn(Map.of("c-a", List.of(ta), "c-b", List.of(tb)));
        when(baselines.baselines(eq("p"), any(OffsetDateTime.class))).thenReturn(PostBaselineService.Baselines.empty(OffsetDateTime.now()));

        service.familyPerformance("p", "c-a", caller);

        verify(baselines, times(1)).baselines(eq("p"), any(OffsetDateTime.class));
    }

    private static Creative creative(String id, String letter) {
        Creative c = new Creative();
        c.setId(id);
        c.setProjectId("p");
        c.setNumber(1);
        c.setVariantLetter(letter);
        return c;
    }
}
