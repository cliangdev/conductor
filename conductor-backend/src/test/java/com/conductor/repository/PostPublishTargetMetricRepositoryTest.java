package com.conductor.repository;

import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetMetric;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.PublishLane;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The metrics table's two contracts: one row per target per period, and a latest-per-target ranking read. */
class PostPublishTargetMetricRepositoryTest extends AbstractNoneWebIntegrationTest {

    @Autowired private PostPublishTargetMetricRepository metricRepository;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ConnectionRepository connectionRepository;

    private Project project;
    private String connectionId;
    private WorkItem post;
    private PostPublishTarget facebook;
    private PostPublishTarget instagram;

    @BeforeEach
    void setUp() {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Metrics");
        user = userRepository.save(user);
        project = new Project();
        project.setName("Metrics " + UUID.randomUUID());
        project.setKey("PM" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(user);
        project = projectRepository.save(project);
        com.conductor.entity.Connection connection = new com.conductor.entity.Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId("meta");
        connection.setAuthType("oauth2");
        connection.setStatus("ACTIVE");
        connectionId = connectionRepository.save(connection).getId();
        post = new WorkItem();
        post.setProject(project);
        post.setType("POST");
        post.setTitle("Measured");
        post.setCurrentStatus("PUBLISHED");
        post.setWorkflow("MARKETING");
        post.setWorkflowVersion(1);
        post.setCreatedBy(user);
        post.setSequenceNumber(1);
        post = workItemRepository.save(post);
        facebook = target("facebook", "fb-1");
        instagram = target("instagram", "ig-1");
    }

    private PostPublishTarget target(String platform, String platformPostId) {
        PostPublishTarget t = new PostPublishTarget();
        t.setWorkItem(post);
        t.setConnectorId("meta");
        t.setConnectionId(connectionId);
        t.setPlatform(platform);
        t.setLane(PublishLane.APP_MANAGED);
        t.setState(PostPublishTargetState.PUBLISHED);
        t.setPlatformPostId(platformPostId);
        t.setFireTime(OffsetDateTime.now().minusDays(1));
        t.setIdempotencyKey("pub:" + UUID.randomUUID());
        return targetRepository.saveAndFlush(t);
    }

    private PostPublishTargetMetric snapshot(PostPublishTarget target, String period, OffsetDateTime at, long views) {
        PostPublishTargetMetric m = new PostPublishTargetMetric();
        m.setTargetId(target.getId());
        m.setWorkItemId(post.getId());
        m.setProjectId(project.getId());
        m.setPlatform(target.getPlatform());
        m.setPeriodKey(period);
        m.setObservedAt(at);
        m.setViews(views);
        return metricRepository.saveAndFlush(m);
    }

    @Test
    void onePeriodHoldsOneSnapshotPerTarget() {
        snapshot(facebook, "2026-09-04T06", OffsetDateTime.parse("2026-09-04T06:05:00Z"), 10);
        assertThat(metricRepository.findByTargetIdAndPeriodKey(facebook.getId(), "2026-09-04T06")).isPresent();
        assertThatThrownBy(() -> snapshot(facebook, "2026-09-04T06", OffsetDateTime.parse("2026-09-04T06:50:00Z"), 11))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void latestPerTargetKeepsEachTargetsNewestSnapshotAndFilters() {
        snapshot(facebook, "2026-09-04T06", OffsetDateTime.parse("2026-09-04T06:05:00Z"), 10);
        snapshot(facebook, "2026-09-04T12", OffsetDateTime.parse("2026-09-04T12:05:00Z"), 40);
        snapshot(instagram, "2026-09-04T12", OffsetDateTime.parse("2026-09-04T12:06:00Z"), 25);

        List<PostPublishTargetMetric> latest = metricRepository.findLatestPerTarget(project.getId(), null, null);
        assertThat(latest).hasSize(2);
        assertThat(latest).filteredOn(m -> m.getTargetId().equals(facebook.getId()))
                .singleElement().extracting(PostPublishTargetMetric::getViews).isEqualTo(40L);

        assertThat(metricRepository.findLatestPerTarget(project.getId(), "instagram", null))
                .extracting(PostPublishTargetMetric::getTargetId).containsExactly(instagram.getId());
        assertThat(metricRepository.findLatestPerTarget(project.getId(), null, OffsetDateTime.parse("2026-09-04T12:06:00Z")))
                .extracting(PostPublishTargetMetric::getTargetId).containsExactly(instagram.getId());
        assertThat(metricRepository.findAllByWorkItemIdOrderByObservedAtAsc(post.getId()))
                .extracting(PostPublishTargetMetric::getViews).containsExactly(10L, 40L, 25L);
    }

    @Test
    void deletingTheTargetRemovesItsSnapshots() {
        snapshot(facebook, "2026-09-04T06", OffsetDateTime.parse("2026-09-04T06:05:00Z"), 10);
        targetRepository.delete(facebook);
        targetRepository.flush();
        assertThat(metricRepository.findAllByWorkItemIdOrderByObservedAtAsc(post.getId())).isEmpty();
    }
}
