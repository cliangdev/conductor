package com.conductor.repository;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.PublishLane;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * DB-backed coverage for {@code V126__create_post_publish_target.sql} (COND-23). The three things
 * that can only be proven against real Postgres with Flyway + {@code ddl-auto=validate}: that the
 * migration applies and every documented column round-trips, that the uniqueness guarantees actually
 * reject a double-target and a reused idempotency key, and that the due-poll / native-handoff
 * finders select on lane, state and fire time the way the scheduler will depend on.
 *
 * <p>Per {@code docs/testing-guidelines.md} this rides the shared singleton Postgres and isolates
 * itself with random ids, asserting on rows it created rather than on global result sizes.
 */
@Transactional
class PostPublishTargetRepositoryTest extends AbstractNoneWebIntegrationTest {

    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ConnectionRepository connectionRepository;

    @PersistenceContext private EntityManager entityManager;

    private User creator;
    private Project project;
    private String connectionId;
    private int nextSequenceNumber = 1;

    @BeforeEach
    void setUp() {
        creator = new User();
        creator.setFirebaseUid("test-uid-" + UUID.randomUUID());
        creator.setEmail(UUID.randomUUID() + "@example.com");
        creator.setName("Publish Target Creator");
        creator = userRepository.save(creator);

        project = new Project();
        project.setName("Publishing Pipeline Test Project");
        project.setKey("PP" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(creator);
        project = projectRepository.save(project);

        connectionId = newConnection().getId();
    }

    private Connection newConnection() {
        Connection connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId("meta");
        connection.setAuthType("OAUTH2");
        connection.setStatus("ACTIVE");
        connection.setConfigJson("{}");
        connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        return connectionRepository.saveAndFlush(connection);
    }

    private WorkItem newWorkItem() {
        WorkItem item = new WorkItem();
        item.setProject(project);
        item.setType("POST");
        item.setTitle("Publish the thing");
        item.setCreatedBy(creator);
        item.setWorkflow("MARKETING");
        item.setWorkflowVersion(1);
        item.setCurrentStatus("DRAFT");
        item.setSequenceNumber(nextSequenceNumber++);
        return workItemRepository.saveAndFlush(item);
    }

    private PostPublishTarget target(WorkItem item, String platform, PublishLane lane,
                                     PostPublishTargetState state, OffsetDateTime fireTime) {
        PostPublishTarget target = new PostPublishTarget();
        target.setWorkItem(item);
        target.setConnectorId("meta");
        target.setConnectionId(connectionId);
        target.setPlatform(platform);
        target.setLane(lane);
        target.setState(state);
        target.setFireTime(fireTime);
        target.setIdempotencyKey("pub:" + UUID.randomUUID());
        return target;
    }

    private PostPublishTarget save(PublishLane lane, PostPublishTargetState state, OffsetDateTime fireTime) {
        return targetRepository.saveAndFlush(target(newWorkItem(), "facebook", lane, state, fireTime));
    }

    private List<String> idsOf(List<PostPublishTarget> targets) {
        return targets.stream().map(PostPublishTarget::getId).toList();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theMigrationCreatesTheDuePollIndexAndBothUniquenessGuarantees() {
        // ddl-auto=validate proves the columns; it says nothing about indexes or constraints, so read
        // them back from the catalog. Both UNIQUE constraints surface here as their backing indexes.
        List<String> indexNames = entityManager
                .createNativeQuery("SELECT indexname FROM pg_indexes WHERE tablename = 'post_publish_target'")
                .getResultList();

        assertThat(indexNames).contains(
                "idx_post_publish_target_due",
                "uq_post_publish_target_item_platform_connection",
                "uq_post_publish_target_idempotency_key");
    }

    @Test
    void everyDocumentedColumnRoundTripsThroughPostgres() {
        OffsetDateTime fireTime = OffsetDateTime.now().plusHours(2);
        PostPublishTarget target = target(newWorkItem(), "instagram", PublishLane.NATIVE,
                PostPublishTargetState.HANDED_OFF, fireTime);
        target.setPlatformAccountLabel("@conductor");
        target.setPlatformPostId("17841400000000000_123");
        target.setPermalink("https://instagram.com/p/abc123");
        target.setErrorMessage("transient upstream 500");
        target.setAttempts(3);
        target.setCaptionOverride("A shorter caption for IG");
        target.setResumeCheckpoint("{\"sessionUri\":\"https://upload/x\",\"byteOffset\":1024}");

        String id = targetRepository.saveAndFlush(target).getId();
        entityManager.clear();

        PostPublishTarget reloaded = targetRepository.findById(id).orElseThrow();
        assertThat(reloaded.getConnectorId()).isEqualTo("meta");
        assertThat(reloaded.getConnectionId()).isEqualTo(connectionId);
        assertThat(reloaded.getPlatform()).isEqualTo("instagram");
        assertThat(reloaded.getPlatformAccountLabel()).isEqualTo("@conductor");
        assertThat(reloaded.getLane()).isEqualTo(PublishLane.NATIVE);
        assertThat(reloaded.getState()).isEqualTo(PostPublishTargetState.HANDED_OFF);
        assertThat(reloaded.getFireTime()).isCloseTo(fireTime, within(1, ChronoUnit.SECONDS));
        assertThat(reloaded.getPlatformPostId()).isEqualTo("17841400000000000_123");
        assertThat(reloaded.getPermalink()).isEqualTo("https://instagram.com/p/abc123");
        assertThat(reloaded.getErrorMessage()).isEqualTo("transient upstream 500");
        assertThat(reloaded.getAttempts()).isEqualTo(3);
        assertThat(reloaded.getCaptionOverride()).isEqualTo("A shorter caption for IG");
        assertThat(reloaded.getResumeCheckpoint()).contains("byteOffset");
        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
    }

    @Test
    void aSecondTargetForTheSameWorkItemPlatformAndConnectionIsRejected() {
        WorkItem item = newWorkItem();
        targetRepository.saveAndFlush(target(item, "facebook", PublishLane.APP_MANAGED,
                PostPublishTargetState.PENDING, OffsetDateTime.now()));

        PostPublishTarget duplicate = target(item, "facebook", PublishLane.NATIVE,
                PostPublishTargetState.PENDING, OffsetDateTime.now());

        assertThatThrownBy(() -> targetRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aSecondPlatformOnTheSameConnectionIsAllowedForTheSameWorkItem() {
        // One Meta connection yields both facebook and instagram targets -- the unique constraint is
        // on the (work item, platform, connection) triple, not on (work item, connection).
        WorkItem item = newWorkItem();
        targetRepository.saveAndFlush(target(item, "facebook", PublishLane.APP_MANAGED,
                PostPublishTargetState.PENDING, OffsetDateTime.now()));
        targetRepository.saveAndFlush(target(item, "instagram", PublishLane.APP_MANAGED,
                PostPublishTargetState.PENDING, OffsetDateTime.now()));

        assertThat(targetRepository.findAllByWorkItemId(item.getId()))
                .extracting(PostPublishTarget::getPlatform)
                .containsExactlyInAnyOrder("facebook", "instagram");
    }

    @Test
    void anIdempotencyKeyCannotBeReusedAcrossWorkItems() {
        PostPublishTarget first = target(newWorkItem(), "facebook", PublishLane.APP_MANAGED,
                PostPublishTargetState.PENDING, OffsetDateTime.now());
        targetRepository.saveAndFlush(first);

        PostPublishTarget second = target(newWorkItem(), "facebook", PublishLane.APP_MANAGED,
                PostPublishTargetState.PENDING, OffsetDateTime.now());
        second.setIdempotencyKey(first.getIdempotencyKey());

        assertThatThrownBy(() -> targetRepository.saveAndFlush(second))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findAllByWorkItemIdAndStateReturnsOnlyThatState() {
        WorkItem item = newWorkItem();
        PostPublishTarget pending = targetRepository.saveAndFlush(target(item, "facebook",
                PublishLane.APP_MANAGED, PostPublishTargetState.PENDING, OffsetDateTime.now()));
        targetRepository.saveAndFlush(target(item, "instagram", PublishLane.APP_MANAGED,
                PostPublishTargetState.PUBLISHED, OffsetDateTime.now()));

        assertThat(targetRepository.findAllByWorkItemIdAndState(item.getId(), PostPublishTargetState.PENDING))
                .extracting(PostPublishTarget::getId)
                .containsExactly(pending.getId());
    }

    @Test
    void duePollReturnsOnlyAppManagedPendingTargetsWhoseFireTimeHasPassed() {
        OffsetDateTime now = OffsetDateTime.now();
        PostPublishTarget due = save(PublishLane.APP_MANAGED, PostPublishTargetState.PENDING, now.minusMinutes(1));
        PostPublishTarget notYetDue = save(PublishLane.APP_MANAGED, PostPublishTargetState.PENDING, now.plusHours(1));
        PostPublishTarget nativeLane = save(PublishLane.NATIVE, PostPublishTargetState.PENDING, now.minusMinutes(1));
        PostPublishTarget alreadyPublishing = save(PublishLane.APP_MANAGED, PostPublishTargetState.PUBLISHING, now.minusMinutes(1));
        PostPublishTarget unscheduled = save(PublishLane.APP_MANAGED, PostPublishTargetState.PENDING, null);

        List<String> found = idsOf(targetRepository.findDueAppManagedTargets(now));

        assertThat(found).contains(due.getId());
        assertThat(found).doesNotContain(notYetDue.getId(), nativeLane.getId(),
                alreadyPublishing.getId(), unscheduled.getId());
    }

    @Test
    void nativeHandoffReturnsOnlyNativePendingTargetsInsideTheWindow() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime windowOpensBefore = now.plusMinutes(30);
        PostPublishTarget insideWindow = save(PublishLane.NATIVE, PostPublishTargetState.PENDING, now.plusMinutes(5));
        PostPublishTarget outsideWindow = save(PublishLane.NATIVE, PostPublishTargetState.PENDING, now.plusHours(4));
        PostPublishTarget appManaged = save(PublishLane.APP_MANAGED, PostPublishTargetState.PENDING, now.plusMinutes(5));
        PostPublishTarget alreadyHandedOff = save(PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF, now.plusMinutes(5));

        List<String> found = idsOf(targetRepository.findNativeHandoffTargets(windowOpensBefore));

        assertThat(found).contains(insideWindow.getId());
        assertThat(found).doesNotContain(outsideWindow.getId(), appManaged.getId(), alreadyHandedOff.getId());
    }

    @Test
    void deletingTheOwningWorkItemCascadesItsTargetsAway() {
        WorkItem item = newWorkItem();
        targetRepository.saveAndFlush(target(item, "facebook", PublishLane.APP_MANAGED,
                PostPublishTargetState.PENDING, OffsetDateTime.now()));
        targetRepository.saveAndFlush(target(item, "instagram", PublishLane.NATIVE,
                PostPublishTargetState.PENDING, OffsetDateTime.now()));

        // Detach the targets first: the cascade is the DB's (ON DELETE CASCADE), and Hibernate would
        // otherwise flush the still-managed rows against the work item it has just marked removed.
        entityManager.clear();
        workItemRepository.delete(workItemRepository.findById(item.getId()).orElseThrow());
        entityManager.flush();
        entityManager.clear();

        assertThat(targetRepository.findAllByWorkItemId(item.getId())).isEmpty();
    }
}
