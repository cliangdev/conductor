package com.conductor.marketing.insights;

import com.conductor.entity.MemberRole;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.PublishLane;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.repository.PostPublishTargetMetricRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.publish.PublishPlatformRegistry;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves {@code MarketingInsightsQueryService} end to end against real Postgres: two destinations of
 * one project, each with a snapshot, read back through the same repositories the controller uses. The
 * pure aggregation rules (median, nulls, movers, bucketing) are covered without a database in
 * {@link MarketingInsightsQueryServiceTest}; this only proves the JPQL/native queries — the window
 * population query, the "latest available snapshot" query, and the Work Item lookup — agree with what
 * the service expects from them.
 *
 * <p>Per {@code docs/testing-guidelines.md} this rides the shared singleton Postgres and isolates itself
 * with a randomly keyed project rather than asserting on global counts.
 */
@Transactional
class MarketingInsightsQueryServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private PostPublishTargetMetricRepository metricRepository;
    @Autowired private ProjectSecurityService projectSecurityService;

    private MarketingInsightsQueryService service;
    private User creator;
    private Project project;
    private int nextSequenceNumber = 1;

    @BeforeEach
    void setUp() {
        service = new MarketingInsightsQueryService(projectSecurityService, targetRepository, metricRepository,
                workItemRepository, new PublishPlatformRegistry());

        creator = new User();
        creator.setFirebaseUid("test-uid-" + UUID.randomUUID());
        creator.setEmail(UUID.randomUUID() + "@example.com");
        creator.setName("Insights Test Creator");
        creator = userRepository.save(creator);

        project = new Project();
        project.setName("Marketing Insights Test Project");
        project.setKey("MI" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(creator);
        project = projectRepository.save(project);
    }

    private WorkItem newPost(String title, String caption) {
        WorkItem item = new WorkItem();
        item.setProject(project);
        item.setType("POST");
        item.setTitle(title);
        item.setDescription(caption);
        item.setCreatedBy(creator);
        item.setWorkflow("MARKETING");
        item.setWorkflowVersion(1);
        item.setCurrentStatus("PUBLISHED");
        item.setSequenceNumber(nextSequenceNumber++);
        return workItemRepository.saveAndFlush(item);
    }

    private PostPublishTarget newTarget(WorkItem item, String platform, OffsetDateTime fireTime) {
        PostPublishTarget target = new PostPublishTarget();
        target.setWorkItem(item);
        target.setPlatform(platform);
        target.setLane(PublishLane.NATIVE);
        target.setState(PostPublishTargetState.PUBLISHED);
        target.setFireTime(fireTime);
        target.setIdempotencyKey("insights-test:" + UUID.randomUUID());
        return targetRepository.saveAndFlush(target);
    }

    private void newSnapshot(PostPublishTarget target, OffsetDateTime observedAt, long views, long likes) {
        PostPublishTargetMetric metric = new PostPublishTargetMetric();
        metric.setTargetId(target.getId());
        metric.setWorkItemId(target.getWorkItem().getId());
        metric.setProjectId(project.getId());
        metric.setPlatform(target.getPlatform());
        metric.setPeriodKey(observedAt.toString());
        metric.setObservedAt(observedAt);
        metric.setViews(views);
        metric.setLikes(likes);
        metricRepository.saveAndFlush(metric);
    }

    private User newMember(MemberRole role) {
        User user = new User();
        user.setFirebaseUid("member-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Insights Test Member");
        user = userRepository.save(user);

        ProjectMember member = new ProjectMember();
        member.setProject(project);
        member.setUser(user);
        member.setRole(role);
        projectMemberRepository.saveAndFlush(member);
        return user;
    }

    @Test
    void theEndpointsPopulationIsPublishedTargetsInsideTheWindowWithTheirLatestSnapshot() {
        OffsetDateTime now = OffsetDateTime.now();
        WorkItem launch = newPost("Launch", "Come see our launch");
        WorkItem recap = newPost("Recap", "Weekly recap");

        PostPublishTarget facebook = newTarget(launch, "facebook", now.minusDays(2));
        newSnapshot(facebook, now.minusDays(2).plusHours(1), 100, 10);

        PostPublishTarget instagram = newTarget(recap, "instagram", now.minusDays(1));
        newSnapshot(instagram, now.minusDays(1).plusHours(1), 200, 30);

        // Outside the 7d window and not part of the assertions below — proves the window actually excludes it.
        WorkItem stale = newPost("Stale", "Old post");
        PostPublishTarget staleTarget = newTarget(stale, "facebook", now.minusDays(20));
        newSnapshot(staleTarget, now.minusDays(20).plusHours(1), 999, 999);

        User caller = newMember(MemberRole.ADMIN);

        MarketingInsightsQueryService.Insights insights = service.compute(project.getId(), "7d", null, caller);

        assertThat(insights.window().days()).isEqualTo(7);
        assertThat(insights.totals().posts()).isEqualTo(2);
        assertThat(insights.totals().views()).isEqualTo(300L);
        assertThat(insights.totals().likes()).isEqualTo(40L);
        assertThat(insights.byPlatform()).extracting(MarketingInsightsQueryService.Group::key)
                .containsExactlyInAnyOrder("facebook", "instagram");
        assertThat(insights.topPosts()).extracting(MarketingInsightsQueryService.Post::workflowSlug)
                .containsOnly("MARKETING");
        assertThat(insights.topPosts()).extracting(MarketingInsightsQueryService.Post::targetId)
                .doesNotContain(staleTarget.getId());
        assertThat(insights.coverage().platforms()).containsExactlyInAnyOrder("facebook", "instagram");
    }

    @Test
    void aCallerWhoIsNotAProjectMemberIsRefused() {
        User stranger = new User();
        stranger.setFirebaseUid("stranger-" + UUID.randomUUID());
        stranger.setEmail(UUID.randomUUID() + "@example.com");
        stranger.setName("Stranger");
        stranger = userRepository.save(stranger);
        User finalStranger = stranger;

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.compute(project.getId(), "30d", null, finalStranger))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    }
}
