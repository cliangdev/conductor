package com.conductor.marketing.insights;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.publish.PublishPlatformRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketingInsightsQueryServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-16T18:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ProjectSecurityService security;
    private PostPublishTargetRepository targetRepository;
    private PostPublishTargetMetricRepository metricRepository;
    private WorkItemRepository workItemRepository;
    private MarketingInsightsQueryService service;
    private User caller;
    private Project project;

    @BeforeEach
    void setUp() {
        security = mock(ProjectSecurityService.class);
        targetRepository = mock(PostPublishTargetRepository.class);
        metricRepository = mock(PostPublishTargetMetricRepository.class);
        workItemRepository = mock(WorkItemRepository.class);
        service = new MarketingInsightsQueryService(security, targetRepository, metricRepository, workItemRepository,
                new PublishPlatformRegistry());
        caller = new User();
        caller.setId("u-1");
        when(security.isProjectMember("proj-1", "u-1")).thenReturn(true);

        project = new Project();
        project.setId("proj-1");
        project.setKey("MK");

        // No null-fireTime targets and no snapshots unless a test says otherwise.
        when(targetRepository.findPublishedWithoutFireTime(anyString(), any())).thenReturn(List.of());
        when(metricRepository.findAllByTargetIdInOrderByTargetIdAscObservedAtAsc(anyList())).thenReturn(List.of());
    }

    private WorkItem post(String id, String title, int sequence, String caption) {
        WorkItem post = new WorkItem();
        post.setId(id);
        post.setProject(project);
        post.setTitle(title);
        post.setSequenceNumber(sequence);
        post.setDescription(caption);
        post.setWorkflow("MARKETING");
        return post;
    }

    private PostPublishTarget target(String id, WorkItem post, String platform, OffsetDateTime fireTime, String format) {
        PostPublishTarget t = new PostPublishTarget();
        t.setId(id);
        t.setWorkItem(post);
        t.setPlatform(platform);
        t.setState(PostPublishTargetState.PUBLISHED);
        t.setFireTime(fireTime);
        t.setFormat(format);
        t.setPlatformAccountLabel(platform + "-account");
        t.setPermalink("https://" + platform + "/" + id);
        return t;
    }

    private PostPublishTargetMetric metric(String targetId, String platform, OffsetDateTime observedAt, Long views,
                                           Long likes, Long comments, Long shares, Long saves) {
        return metric(targetId, platform, observedAt, views, likes, comments, shares, saves, null);
    }

    private PostPublishTargetMetric metric(String targetId, String platform, OffsetDateTime observedAt, Long views,
                                           Long likes, Long comments, Long shares, Long saves, Double avgViewPct) {
        PostPublishTargetMetric m = new PostPublishTargetMetric();
        m.setId("m-" + targetId + "-" + observedAt);
        m.setTargetId(targetId);
        m.setWorkItemId(targetId + "-item");
        m.setProjectId("proj-1");
        m.setPlatform(platform);
        m.setPeriodKey(observedAt.toString());
        m.setObservedAt(observedAt);
        m.setViews(views);
        m.setLikes(likes);
        m.setComments(comments);
        m.setShares(shares);
        m.setSaves(saves);
        if (avgViewPct != null) {
            JsonNode extra = MAPPER.createObjectNode().put("avg_view_pct", avgViewPct);
            m.setExtra(extra);
        }
        return m;
    }

    private void givenPopulation(String platformFilter, List<PostPublishTarget> targets,
                                 List<PostPublishTargetMetric> latest) {
        when(targetRepository.findPublishedByFireTimeWindow(eq("proj-1"), eq(platformFilter), any(), any()))
                .thenReturn(targets);
        List<String> ids = targets.stream().map(PostPublishTarget::getId).toList();
        when(metricRepository.findLatestAvailableForTargets(eq(ids))).thenReturn(latest);
    }

    @Test
    void totalsSumTheLatestSnapshotPerDestinationAndComputeEngagementRate() {
        WorkItem postA = post("post-a", "Launch", 1, "Come see our launch!  It is great.");
        WorkItem postB = post("post-b", "Recap", 2, "Recap of the week");
        PostPublishTarget fb = target("t-fb", postA, "facebook", NOW.minusDays(1), "FEED");
        PostPublishTarget ig = target("t-ig", postB, "instagram", NOW.minusDays(2), "REEL");

        givenPopulation(null, List.of(fb, ig), List.of(
                metric("t-fb", "facebook", NOW.minusDays(1), 100L, 10L, 2L, 1L, 0L),
                metric("t-ig", "instagram", NOW.minusDays(2), 200L, 20L, 0L, 0L, 5L)));
        when(targetRepository.findPublishedByFireTimeWindow(eq("proj-1"), eq(null), any(), any()))
                .thenReturn(List.of(fb, ig))
                // second call is for the previous window — nothing happened before it
                .thenReturn(List.of());
        when(workItemRepository.findAllById(any())).thenReturn(List.of(postA, postB));

        MarketingInsightsQueryService.Insights insights = service.compute("proj-1", InsightsWindowSpec.parse("30d"),
                null, NOW);

        assertThat(insights.window().days()).isEqualTo(30);
        assertThat(insights.totals().posts()).isEqualTo(2);
        assertThat(insights.totals().views()).isEqualTo(300L);
        assertThat(insights.totals().likes()).isEqualTo(30L);
        assertThat(insights.totals().engagementRate()).isCloseTo(38.0 / 300, within(0.0001));
        assertThat(insights.totals().medianViews()).isEqualTo(150L);

        assertThat(insights.byPlatform()).extracting(MarketingInsightsQueryService.Group::key)
                .containsExactly("facebook", "instagram");
        assertThat(insights.byPlatform().get(0).label()).isEqualTo("Facebook");

        assertThat(insights.topPosts()).extracting(MarketingInsightsQueryService.Post::targetId)
                .containsExactly("t-fb", "t-ig");
        assertThat(insights.topPosts().get(0).captionExcerpt()).isEqualTo("Come see our launch! It is great.");
        assertThat(insights.topPosts().get(0).displayId()).isEqualTo("MK-1");
        assertThat(insights.topPosts().get(0).workflowSlug()).isEqualTo("MARKETING");

        // Fewer than ten destinations reported, so bottomPosts stays empty.
        assertThat(insights.bottomPosts()).isEmpty();
    }

    @Test
    void aWorkItemWithNoWorkflowFallsBackToTheLegacyDefault() {
        WorkItem postA = post("post-a", "Legacy post", 1, "caption");
        postA.setWorkflow(null);
        PostPublishTarget fb = target("t-fb", postA, "facebook", NOW.minusHours(1), "FEED");

        givenPopulation(null, List.of(fb), List.of(
                metric("t-fb", "facebook", NOW.minusHours(1), 100L, 10L, 0L, 0L, 0L)));
        when(workItemRepository.findAllById(any())).thenReturn(List.of(postA));

        MarketingInsightsQueryService.Insights insights = service.compute("proj-1", InsightsWindowSpec.parse("7d"),
                null, NOW);

        assertThat(insights.topPosts().get(0).workflowSlug()).isEqualTo("ENGINEERING");
    }

    @Test
    void nullCountersAreZeroInSumsAndAGroupWithNoViewsHasNoEngagementRateOrMedian() {
        WorkItem postA = post("post-a", "No views yet", 1, null);
        PostPublishTarget target = target("t-1", postA, "tiktok", NOW.minusHours(3), "FEED");

        givenPopulation(null, List.of(target), List.of(
                metric("t-1", "tiktok", NOW.minusHours(3), 0L, null, null, null, null)));
        when(workItemRepository.findAllById(any())).thenReturn(List.of(postA));

        MarketingInsightsQueryService.Insights insights = service.compute("proj-1", InsightsWindowSpec.parse("7d"),
                null, NOW);

        assertThat(insights.totals().posts()).isEqualTo(1);
        assertThat(insights.totals().views()).isZero();
        assertThat(insights.totals().likes()).isZero();
        assertThat(insights.totals().engagementRate()).isNull();
        assertThat(insights.totals().medianViews()).isNull();
        // views < 1, so it never enters the top/bottom rankings.
        assertThat(insights.topPosts()).isEmpty();
    }

    @Test
    void aDestinationWithNoSnapshotYetIsExcludedFromGroupsButCountedInCoverage() {
        WorkItem postA = post("post-a", "Reported", 1, "caption");
        WorkItem postB = post("post-b", "Not reported yet", 2, "caption");
        PostPublishTarget reported = target("t-1", postA, "instagram", NOW.minusHours(1), "FEED");
        PostPublishTarget notReported = target("t-2", postB, "instagram", NOW.minusHours(2), "FEED");

        when(targetRepository.findPublishedByFireTimeWindow(eq("proj-1"), eq(null), any(), any()))
                .thenReturn(List.of(reported, notReported));
        when(metricRepository.findLatestAvailableForTargets(any())).thenReturn(List.of(
                metric("t-1", "instagram", NOW.minusHours(1), 50L, 5L, 0L, 0L, 0L)));
        when(workItemRepository.findAllById(any())).thenReturn(List.of(postA));

        MarketingInsightsQueryService.Insights insights = service.compute("proj-1", InsightsWindowSpec.parse("7d"),
                null, NOW);

        assertThat(insights.totals().posts()).isEqualTo(1);
        assertThat(insights.coverage().platforms()).containsExactly("instagram");
        assertThat(insights.coverage().notes()).contains("Instagram reported no views for 1 of 2 destinations.");
    }

    @Test
    void aPlatformThatNeverReportsAMetricGetsACoverageNote() {
        WorkItem postA = post("post-a", "Feed post", 1, "caption");
        PostPublishTarget fb = target("t-fb", postA, "facebook", NOW.minusHours(1), "FEED");

        givenPopulation(null, List.of(fb), List.of(
                metric("t-fb", "facebook", NOW.minusHours(1), 100L, 10L, 2L, 1L, null)));
        when(workItemRepository.findAllById(any())).thenReturn(List.of(postA));

        MarketingInsightsQueryService.Insights insights = service.compute("proj-1", InsightsWindowSpec.parse("7d"),
                null, NOW);

        assertThat(insights.coverage().notes()).contains("Facebook does not report saves.");
    }

    @Test
    void hourAndWeekdayGroupsBucketByTheUtcFireTime() {
        WorkItem postA = post("post-a", "Morning", 1, "caption");
        WorkItem postB = post("post-b", "Evening", 2, "caption");
        OffsetDateTime monday9am = OffsetDateTime.parse("2026-09-14T09:00:00Z");
        OffsetDateTime tuesday5pm = OffsetDateTime.parse("2026-09-15T17:00:00Z");
        PostPublishTarget a = target("t-a", postA, "facebook", monday9am, "FEED");
        PostPublishTarget b = target("t-b", postB, "facebook", tuesday5pm, "FEED");

        givenPopulation(null, List.of(a, b), List.of(
                metric("t-a", "facebook", monday9am, 10L, 1L, 0L, 0L, 0L),
                metric("t-b", "facebook", tuesday5pm, 20L, 2L, 0L, 0L, 0L)));
        when(workItemRepository.findAllById(any())).thenReturn(List.of(postA, postB));

        MarketingInsightsQueryService.Insights insights = service.compute("proj-1", InsightsWindowSpec.parse("30d"),
                null, NOW);

        assertThat(insights.byHour()).extracting(MarketingInsightsQueryService.Group::key).containsExactly("9", "17");
        assertThat(insights.byWeekday()).extracting(MarketingInsightsQueryService.Group::key)
                .containsExactly("MONDAY", "TUESDAY");
    }

    @Test
    void topAndBottomPostsSplitAtTenReportedDestinations() {
        List<PostPublishTarget> targets = new ArrayList<>();
        List<PostPublishTargetMetric> metrics = new ArrayList<>();
        List<WorkItem> posts = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            WorkItem p = post("post-" + i, "Post " + i, i, "caption " + i);
            posts.add(p);
            PostPublishTarget t = target("t-" + i, p, "facebook", NOW.minusHours(i + 1), "FEED");
            targets.add(t);
            // engagement rate increases with i: likes = i, views fixed at 100
            metrics.add(metric("t-" + i, "facebook", NOW.minusHours(i + 1), 100L, (long) i, 0L, 0L, 0L));
        }
        givenPopulation(null, targets, metrics);
        when(workItemRepository.findAllById(any())).thenReturn(posts);

        MarketingInsightsQueryService.Insights insights = service.compute("proj-1", InsightsWindowSpec.parse("30d"),
                null, NOW);

        assertThat(insights.topPosts()).hasSize(10);
        assertThat(insights.topPosts().get(0).targetId()).isEqualTo("t-9");
        assertThat(insights.bottomPosts()).hasSize(5);
        assertThat(insights.bottomPosts().get(0).targetId()).isEqualTo("t-0");
    }

    @Test
    void moversCompareTheCurrentWindowWithTheEqualLengthWindowBeforeIt() {
        WorkItem postA = post("post-a", "Current", 1, "caption");
        WorkItem postB = post("post-b", "Previous", 2, "caption");
        PostPublishTarget current = target("t-cur", postA, "facebook", NOW.minusDays(1), "FEED");
        PostPublishTarget previous = target("t-prev", postB, "facebook", NOW.minusDays(9), "FEED");

        when(targetRepository.findPublishedByFireTimeWindow(eq("proj-1"), eq(null), any(), any()))
                .thenReturn(List.of(current))
                .thenReturn(List.of(previous));
        when(metricRepository.findLatestAvailableForTargets(eq(List.of("t-cur"))))
                .thenReturn(List.of(metric("t-cur", "facebook", NOW.minusDays(1), 200L, 20L, 0L, 0L, 0L)));
        when(metricRepository.findLatestAvailableForTargets(eq(List.of("t-prev"))))
                .thenReturn(List.of(metric("t-prev", "facebook", NOW.minusDays(9), 100L, 10L, 0L, 0L, 0L)));
        when(workItemRepository.findAllById(any())).thenReturn(List.of(postA, postB));

        MarketingInsightsQueryService.Insights insights = service.compute("proj-1", InsightsWindowSpec.parse("7d"),
                null, NOW);

        MarketingInsightsQueryService.Mover viewsMover = insights.movers().stream()
                .filter(m -> m.metric().equals("views")).findFirst().orElseThrow();
        assertThat(viewsMover.current()).isEqualTo(200.0);
        assertThat(viewsMover.previous()).isEqualTo(100.0);
        assertThat(viewsMover.deltaPct()).isCloseTo(100.0, within(0.0001));
    }

    @Test
    void anUnknownWindowIsRejected() {
        assertThatThrownBy(() -> service.compute("proj-1", "45d", null, caller))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void anUnknownPlatformIsRejected() {
        when(targetRepository.findPublishedByFireTimeWindow(anyString(), any(), any(), any())).thenReturn(List.of());
        assertThatThrownBy(() -> service.compute("proj-1", "30d", "myspace", caller))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void aNonMemberSeesNothing() {
        User stranger = new User();
        stranger.setId("u-2");
        assertThatThrownBy(() -> service.compute("proj-1", "30d", null, stranger))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    }
}
