package com.conductor.service.publish;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.ProjectSecurityService;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublishMetricsQueryServiceTest {

    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-09-04T06:00:00Z");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-09-04T12:00:00Z");

    private ProjectSecurityService security;
    private WorkItemRepository workItemRepository;
    private PostPublishTargetRepository targetRepository;
    private PostPublishTargetMetricRepository metricRepository;
    private PublishMetricsQueryService service;
    private User caller;
    private WorkItem post;
    private PostPublishTarget fb;
    private PostPublishTarget ig;

    @BeforeEach
    void setUp() {
        security = mock(ProjectSecurityService.class);
        workItemRepository = mock(WorkItemRepository.class);
        targetRepository = mock(PostPublishTargetRepository.class);
        metricRepository = mock(PostPublishTargetMetricRepository.class);
        service = new PublishMetricsQueryService(security, workItemRepository, targetRepository, metricRepository);
        caller = new User();
        caller.setId("u-1");
        when(security.isProjectMember("proj-1", "u-1")).thenReturn(true);

        Project project = new Project();
        project.setId("proj-1");
        project.setKey("MK");
        post = new WorkItem();
        post.setId("post-1");
        post.setProject(project);
        post.setTitle("Launch");
        post.setSequenceNumber(7);
        when(workItemRepository.findById("post-1")).thenReturn(Optional.of(post));

        fb = target("t-fb", "facebook", "Acme Page", "https://fb/1");
        ig = target("t-ig", "instagram", "@acme", "https://ig/1");
        when(targetRepository.findAllByWorkItemId("post-1")).thenReturn(List.of(fb, ig));
        when(targetRepository.findById("t-fb")).thenReturn(Optional.of(fb));
        when(targetRepository.findById("t-ig")).thenReturn(Optional.of(ig));
    }

    private PostPublishTarget target(String id, String platform, String label, String permalink) {
        PostPublishTarget t = new PostPublishTarget();
        t.setId(id);
        t.setWorkItem(post);
        t.setPlatform(platform);
        t.setPlatformAccountLabel(label);
        t.setPermalink(permalink);
        return t;
    }

    private static PostPublishTargetMetric snapshot(String targetId, String platform, OffsetDateTime at, Long views, Long likes,
                                                    boolean unavailable) {
        PostPublishTargetMetric m = new PostPublishTargetMetric();
        m.setTargetId(targetId);
        m.setWorkItemId("post-1");
        m.setProjectId("proj-1");
        m.setPlatform(platform);
        m.setPeriodKey(at.toString());
        m.setObservedAt(at);
        m.setViews(views);
        m.setLikes(likes);
        m.setUnavailable(unavailable);
        return m;
    }

    @Test
    void aPostsMetricsAreOneSeriesPerDestinationPlusTheLatestTotals() {
        when(metricRepository.findAllByWorkItemIdOrderByObservedAtAsc("post-1")).thenReturn(List.of(
                snapshot("t-fb", "facebook", T1, 100L, 10L, false),
                snapshot("t-ig", "instagram", T1, null, 5L, false),
                snapshot("t-fb", "facebook", T2, 150L, 12L, false),
                snapshot("t-ig", "instagram", T2, null, 8L, false)));

        PublishMetricsQueryService.PostMetrics metrics = service.forPost("proj-1", "post-1", null, caller);

        assertThat(metrics.targets()).hasSize(2);
        PublishMetricsQueryService.TargetMetrics facebook = metrics.targets().get(0);
        assertThat(facebook.platform()).isEqualTo("facebook");
        assertThat(facebook.accountLabel()).isEqualTo("Acme Page");
        assertThat(facebook.permalink()).isEqualTo("https://fb/1");
        assertThat(facebook.series()).hasSize(2);
        assertThat(facebook.latest().views()).isEqualTo(150L);
        assertThat(metrics.totals().views()).isEqualTo(150L);
        assertThat(metrics.totals().likes()).as("12 + 8").isEqualTo(20L);
        assertThat(metrics.totals().observedAt()).isEqualTo(T2);

        PublishMetricsQueryService.PostMetrics windowed = service.forPost("proj-1", "post-1", T2, caller);
        assertThat(windowed.targets().get(0).series()).hasSize(1);
    }

    @Test
    void anUnavailableDestinationIsListedButNotTotalled() {
        when(metricRepository.findAllByWorkItemIdOrderByObservedAtAsc("post-1")).thenReturn(List.of(
                snapshot("t-fb", "facebook", T1, 100L, 10L, false),
                snapshot("t-ig", "instagram", T1, null, null, true)));

        PublishMetricsQueryService.PostMetrics metrics = service.forPost("proj-1", "post-1", null, caller);

        assertThat(metrics.targets()).hasSize(2);
        assertThat(metrics.targets().get(1).latest().unavailable()).isTrue();
        assertThat(metrics.totals().views()).isEqualTo(100L);
    }

    @Test
    void nothingReadYetMeansNoTotals() {
        when(metricRepository.findAllByWorkItemIdOrderByObservedAtAsc("post-1")).thenReturn(List.of());
        PublishMetricsQueryService.PostMetrics metrics = service.forPost("proj-1", "post-1", null, caller);
        assertThat(metrics.targets()).isEmpty();
        assertThat(metrics.totals()).isNull();
    }

    @Test
    void topPostsRankTheLatestSnapshotsByTheChosenMetric() {
        when(metricRepository.findLatestPerTarget(eq("proj-1"), isNull(), any())).thenReturn(List.of(
                snapshot("t-fb", "facebook", T2, 150L, 12L, false),
                snapshot("t-ig", "instagram", T2, 900L, 8L, false)));

        List<PublishMetricsQueryService.TopPost> byViews = service.topPosts("proj-1", null, null, null, 20, caller);
        assertThat(byViews).extracting(PublishMetricsQueryService.TopPost::targetId).containsExactly("t-ig", "t-fb");
        assertThat(byViews.get(0).displayId()).isEqualTo("MK-7");
        assertThat(byViews.get(0).title()).isEqualTo("Launch");
        assertThat(byViews.get(0).metric()).isEqualTo("views");
        assertThat(byViews.get(0).value()).isEqualTo(900L);

        List<PublishMetricsQueryService.TopPost> byLikes = service.topPosts("proj-1", "likes", null, null, 1, caller);
        assertThat(byLikes).extracting(PublishMetricsQueryService.TopPost::targetId).containsExactly("t-fb");

        assertThatThrownBy(() -> service.topPosts("proj-1", "vibes", null, null, 20, caller))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("vibes");
    }

    @Test
    void aNonMemberSeesNothing() {
        User stranger = new User();
        stranger.setId("u-2");
        assertThatThrownBy(() -> service.forPost("proj-1", "post-1", null, stranger)).isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> service.topPosts("proj-1", null, null, null, 20, stranger)).isInstanceOf(EntityNotFoundException.class);
    }
}
