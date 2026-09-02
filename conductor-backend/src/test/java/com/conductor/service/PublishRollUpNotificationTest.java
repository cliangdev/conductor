package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.MemberRole;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.PublishLane;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.notification.ChannelGroup;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalBus;
import com.conductor.signal.SignalTypes;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;

/**
 * That finishing a publish announces itself (MKT-3).
 *
 * <p>Its own class, and a small deliberate cost. Spying a bean forks the Spring context cache, which
 * {@code docs/testing-guidelines.md} says to protect, so the fork is isolated here rather than paid by
 * {@code PublishOutcomeServiceTest}, which shares the common context with everything else.
 *
 * <p>Asserted at the {@link SignalBus} rather than at a webhook because the bus is what every consumer
 * subscribes to — the notification sink, the knowledge sink and the workflow triggers all hang off this
 * one publish, so proving it happens proves all three can see it.
 *
 * <p>The gap this closes was real and documented: the Post-level roll-up wrote a status directly and told
 * nobody, so the one outcome anybody waits for — did it go out, or did it fail? — was the only status
 * change in the product that notified no one, and a Discord channel went quiet at exactly the moment it
 * mattered.
 */
class PublishRollUpNotificationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private PublishOutcomeService service;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private WorkflowSeeder workflowSeeder;
    @Autowired private ConnectionRepository connectionRepository;

    @MockitoSpyBean private SignalBus signalBus;

    private User creator;
    private Project project;
    private String connectionId;
    private int nextSequenceNumber = 1;

    @BeforeEach
    void setUp() {
        creator = new User();
        creator.setFirebaseUid("rollup-uid-" + UUID.randomUUID());
        creator.setEmail(UUID.randomUUID() + "@example.com");
        creator.setName("Roll-up Creator");
        creator = userRepository.save(creator);

        project = new Project();
        project.setName("Roll-up Project");
        project.setKey("RU" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(creator);
        project = projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(creator);
        membership.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(membership);

        // The roll-up reads the published/failed statuses off the Post's own version-pinned statechart.
        workflowSeeder.seedMarketing(project);

        Connection connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId("meta");
        connection.setAuthType("oauth2");
        connection.setStatus("ACTIVE");
        connectionId = connectionRepository.save(connection).getId();
    }

    @Test
    void aPostThatFinishesPublishingAnnouncesTheStatusChange() {
        PostPublishTarget target = publishingTarget();

        service.recordSuccess(target.getId(), "ig-1", "https://instagram.com/p/1");

        assertThat(workItemRepository.findById(target.getWorkItem().getId()).orElseThrow()
                .getCurrentStatus()).isEqualTo("PUBLISHED");
        assertThat(statusSignals()).anySatisfy(signal -> {
            assertThat(signal.payload()).containsEntry("toStatus", "PUBLISHED");
            // Routes to a project's Publishing channel rather than its engineering one.
            assertThat(signal.payload()).containsEntry(ChannelGroup.META_PUBLISHES, "true");
        });
    }

    @Test
    void aPostThatFailsToPublishAnnouncesThatToo() {
        PostPublishTarget target = publishingTarget();

        service.recordFailure(target.getId(), "Instagram rejected the media");

        assertThat(statusSignals())
                .anySatisfy(signal -> assertThat(signal.payload()).containsEntry("toStatus", "FAILED"));
    }

    /** Every status-changed signal the bus saw, so an assertion never depends on ordering. */
    private List<Signal> statusSignals() {
        ArgumentCaptor<Signal> captor = ArgumentCaptor.forClass(Signal.class);
        verify(signalBus, atLeastOnce()).publish(captor.capture());
        return captor.getAllValues().stream()
                .filter(s -> SignalTypes.CONDUCTOR_WORK_ITEM_STATUS_CHANGED.equals(s.type()))
                .filter(s -> project.getId().equals(s.projectId()))
                .toList();
    }

    /** A Post sitting in its scheduled status with one in-flight target — one outcome away from settling. */
    private PostPublishTarget publishingTarget() {
        WorkItem post = new WorkItem();
        post.setProject(project);
        post.setCreatedBy(creator);
        post.setType("POST");
        post.setTitle("Launch teaser");
        post.setWorkflow("MARKETING");
        post.setWorkflowVersion(1);
        post.setCurrentStatus(NativeHandoffService.SCHEDULED_STATUS);
        post.setSequenceNumber(nextSequenceNumber++);
        post = workItemRepository.save(post);

        PostPublishTarget target = new PostPublishTarget();
        target.setWorkItem(post);
        target.setConnectorId("meta");
        target.setConnectionId(connectionId);
        target.setPlatform("instagram");
        target.setLane(PublishLane.APP_MANAGED);
        target.setState(PostPublishTargetState.PUBLISHING);
        target.setFireTime(OffsetDateTime.now());
        target.setIdempotencyKey("pub:" + UUID.randomUUID());
        return targetRepository.saveAndFlush(target);
    }
}
