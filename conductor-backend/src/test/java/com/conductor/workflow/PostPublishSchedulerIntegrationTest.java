package com.conductor.workflow;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.PublishLane;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.integration.ActionResult;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.ActionInvocationService;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB-backed coverage for {@link PostPublishScheduler} against real Postgres. Two things can only be
 * proven here: that the due poll selects on lane, state, owning-post status and fire time the way the
 * SLO depends on, and — the load-bearing one — that the claim is genuinely atomic, so two ticks racing
 * the same row publish it exactly once.
 *
 * <p>{@link ActionInvocationService} is mocked: this test is about which rows get dispatched and how
 * often, not about what a connector does with them. The scheduler's own {@code @Scheduled} tick stays
 * off (its due query is globally scoped — see the class javadoc); every test drives
 * {@code runTick} explicitly, and asserts only on rows it created, per {@code docs/testing-guidelines.md}.
 */
class PostPublishSchedulerIntegrationTest extends AbstractNoneWebIntegrationTest {

    private static final ZoneId DENVER = ZoneId.of("America/Denver");

    @Autowired private PostPublishScheduler scheduler;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ConnectionRepository connectionRepository;

    @MockitoBean private ActionInvocationService actionInvocationService;

    private User creator;
    private Project project;
    private String connectionId;
    private int nextSequenceNumber = 1;

    @BeforeEach
    void setUp() {
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(ActionResult.ok(Map.of()));

        creator = new User();
        creator.setFirebaseUid("test-uid-" + UUID.randomUUID());
        creator.setEmail(UUID.randomUUID() + "@example.com");
        creator.setName("Due Post Scheduler Creator");
        creator = userRepository.save(creator);

        project = new Project();
        project.setName("Due Post Scheduler Project");
        project.setKey("DP" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
        project.setCreatedBy(creator);
        project = projectRepository.save(project);

        Connection connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId("meta");
        connection.setAuthType("OAUTH2");
        connection.setStatus("ACTIVE");
        connection.setConfigJson("{}");
        connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        connectionId = connectionRepository.saveAndFlush(connection).getId();
    }

    @AfterEach
    void resetSeam() {
        scheduler.beforeClaimUpdate = () -> { };
    }

    // --- fixtures -------------------------------------------------------------------------------

    private WorkItem post(String status) {
        WorkItem item = new WorkItem();
        item.setProject(project);
        item.setType("POST");
        item.setTitle("Launch day teaser");
        item.setDescription("Doors open at nine.");
        item.setCreatedBy(creator);
        item.setWorkflow("MARKETING");
        item.setWorkflowVersion(1);
        item.setCurrentStatus(status);
        item.setSequenceNumber(nextSequenceNumber++);
        return workItemRepository.saveAndFlush(item);
    }

    private PostPublishTarget target(WorkItem owner, PublishLane lane, PostPublishTargetState state,
                                     OffsetDateTime fireTime) {
        PostPublishTarget target = new PostPublishTarget();
        target.setWorkItem(owner);
        target.setConnectorId("meta");
        target.setConnectionId(connectionId);
        target.setPlatform("facebook");
        target.setLane(lane);
        target.setState(state);
        target.setFireTime(fireTime);
        target.setIdempotencyKey("pub:" + UUID.randomUUID());
        return targetRepository.saveAndFlush(target);
    }

    private PostPublishTarget dueTarget() {
        return target(post(PostPublishScheduler.SCHEDULED_STATUS), PublishLane.APP_MANAGED,
                PostPublishTargetState.PENDING, OffsetDateTime.now().minusMinutes(1));
    }

    private PostPublishTargetState stateOf(PostPublishTarget target) {
        return targetRepository.findById(target.getId()).orElseThrow().getState();
    }

    private void verifyDispatched(PostPublishTarget target, int expectedTimes) {
        verify(actionInvocationService, times(expectedTimes))
                .invoke(any(), eq("publish_facebook_post"), any(), eq(target.getIdempotencyKey()), any());
    }

    private void verifyNeverDispatched(PostPublishTarget target) {
        verify(actionInvocationService, never())
                .invoke(any(), anyString(), any(), eq(target.getIdempotencyKey()), any());
    }

    // --- [auto] A due app-managed target is dispatched within 60 seconds of its fire time ---

    @Test
    void aTargetDueAtNineAmDenverIsDispatchedWithinTheFirstMinuteAfterItsFireTime() {
        ZonedDateTime nineAmDenver = ZonedDateTime.now(DENVER)
                .minusDays(1)
                .withHour(9).withMinute(0).withSecond(0).withNano(0);
        PostPublishTarget target = target(post(PostPublishScheduler.SCHEDULED_STATUS),
                PublishLane.APP_MANAGED, PostPublishTargetState.PENDING,
                nineAmDenver.toOffsetDateTime());

        // The first tick that can see it: one 30s interval after 09:00:00, still inside 09:01:00.
        scheduler.runTick(nineAmDenver.plusSeconds(30).toOffsetDateTime());

        verifyDispatched(target, 1);
        assertThat(stateOf(target)).isEqualTo(PostPublishTargetState.PUBLISHING);
    }

    @Test
    void aTargetWhoseFireTimeHasNotArrivedIsNotDispatched() {
        ZonedDateTime nineAmDenver = ZonedDateTime.now(DENVER)
                .plusDays(1)
                .withHour(9).withMinute(0).withSecond(0).withNano(0);
        PostPublishTarget target = target(post(PostPublishScheduler.SCHEDULED_STATUS),
                PublishLane.APP_MANAGED, PostPublishTargetState.PENDING,
                nineAmDenver.toOffsetDateTime());

        scheduler.runTick(nineAmDenver.minusSeconds(1).toOffsetDateTime());

        verifyNeverDispatched(target);
        assertThat(stateOf(target)).isEqualTo(PostPublishTargetState.PENDING);
    }

    // --- [auto] A repeated tick or a restart never publishes the same (post, target) twice ---

    @Test
    void runningThePollTwiceOverTheSameDueRowDispatchesExactlyOnce() {
        PostPublishTarget target = dueTarget();

        scheduler.runTick(OffsetDateTime.now());
        scheduler.runTick(OffsetDateTime.now());

        verifyDispatched(target, 1);
        assertThat(stateOf(target)).isEqualTo(PostPublishTargetState.PUBLISHING);
    }

    @Test
    void aRowLeftMidFlightInPublishingByARestartIsNeverReDispatched() {
        PostPublishTarget target = target(post(PostPublishScheduler.SCHEDULED_STATUS),
                PublishLane.APP_MANAGED, PostPublishTargetState.PUBLISHING,
                OffsetDateTime.now().minusMinutes(1));

        scheduler.runTick(OffsetDateTime.now());

        verifyNeverDispatched(target);
        assertThat(stateOf(target)).isEqualTo(PostPublishTargetState.PUBLISHING);
    }

    @Test
    void anAlreadyPublishedRowIsNeverReDispatched() {
        PostPublishTarget target = target(post(PostPublishScheduler.SCHEDULED_STATUS),
                PublishLane.APP_MANAGED, PostPublishTargetState.PUBLISHED,
                OffsetDateTime.now().minusMinutes(1));

        scheduler.runTick(OffsetDateTime.now());

        verifyNeverDispatched(target);
        assertThat(stateOf(target)).isEqualTo(PostPublishTargetState.PUBLISHED);
    }

    @Test
    void aRevokedRowIsNeverDispatched() {
        PostPublishTarget target = target(post(PostPublishScheduler.SCHEDULED_STATUS),
                PublishLane.APP_MANAGED, PostPublishTargetState.REVOKED,
                OffsetDateTime.now().minusMinutes(1));

        scheduler.runTick(OffsetDateTime.now());

        verifyNeverDispatched(target);
        assertThat(stateOf(target)).isEqualTo(PostPublishTargetState.REVOKED);
    }

    @Test
    void twoConcurrentTicksClaimTheSameRowExactlyOnce() throws Exception {
        PostPublishTarget target = dueTarget();

        int racers = 2;
        CountDownLatch bothHaveReadTheRow = new CountDownLatch(racers);
        // Held inside claimInNewTx AFTER the row was read as PENDING and BEFORE the conditional UPDATE,
        // so both transactions are provably past the read when either one writes. Without an atomic
        // claim both would then see PENDING and both would publish.
        scheduler.beforeClaimUpdate = () -> {
            bothHaveReadTheRow.countDown();
            try {
                assertThat(bothHaveReadTheRow.await(20, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };

        AtomicInteger claims = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        for (int i = 0; i < racers; i++) {
            Thread thread = new Thread(() -> {
                try {
                    if (scheduler.claimInNewTx(target.getId()) != null) {
                        claims.incrementAndGet();
                    }
                } catch (Throwable t) {
                    synchronized (failures) {
                        failures.add(t);
                    }
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(60_000);
        }

        assertThat(failures).isEmpty();
        assertThat(claims.get()).isEqualTo(1);
        assertThat(stateOf(target)).isEqualTo(PostPublishTargetState.PUBLISHING);
    }

    // --- lane and owning-post gating -------------------------------------------------------------

    @Test
    void aDueNativeLaneRowIsIgnoredByThisPoll() {
        PostPublishTarget target = target(post(PostPublishScheduler.SCHEDULED_STATUS),
                PublishLane.NATIVE, PostPublishTargetState.PENDING,
                OffsetDateTime.now().minusMinutes(1));

        scheduler.runTick(OffsetDateTime.now());

        verifyNeverDispatched(target);
        assertThat(stateOf(target)).isEqualTo(PostPublishTargetState.PENDING);
    }

    @Test
    void aTargetWhosePostIsNotInItsScheduledStatusIsNeverDispatched() {
        PostPublishTarget target = target(post("DRAFT"), PublishLane.APP_MANAGED,
                PostPublishTargetState.PENDING, OffsetDateTime.now().minusMinutes(1));

        scheduler.runTick(OffsetDateTime.now());

        verifyNeverDispatched(target);
        assertThat(stateOf(target)).isEqualTo(PostPublishTargetState.PENDING);
    }

    @Test
    void aTargetWhosePostWasUnscheduledBackToApprovedIsNeverDispatched() {
        PostPublishTarget target = target(post("APPROVED"), PublishLane.APP_MANAGED,
                PostPublishTargetState.PENDING, OffsetDateTime.now().minusMinutes(1));

        scheduler.runTick(OffsetDateTime.now());

        verifyNeverDispatched(target);
        assertThat(stateOf(target)).isEqualTo(PostPublishTargetState.PENDING);
    }

    // --- [auto] The scheduler can be disabled by config for tests and the local profile ---

    @Test
    void theScheduledTickIsDisabledByConfigInThisProfileSoNothingFiresOnItsOwn() {
        PostPublishTarget target = dueTarget();

        scheduler.poll();

        verifyNeverDispatched(target);
        assertThat(stateOf(target)).isEqualTo(PostPublishTargetState.PENDING);
    }
}
