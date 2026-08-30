package com.conductor.workflow;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.PublishLane;
import com.conductor.entity.WorkItem;
import com.conductor.integration.ActionResult;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.service.ActionInvocationService;
import com.conductor.service.ActiveConnectionResolver;
import com.conductor.service.PublishOutcomeService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link PostPublishScheduler}'s decision logic: which rows it will and will not
 * dispatch, and that a lost claim never turns into a publish. The DB-level guarantees (the due query
 * itself, and the atomicity of the claim under two concurrent ticks) are proven against real Postgres
 * in {@code PostPublishSchedulerIntegrationTest}.
 */
class PostPublishSchedulerTest {

    private PostPublishTargetRepository targetRepository;
    private ActiveConnectionResolver connectionResolver;
    private ActionInvocationService actionInvocationService;
    private PublishOutcomeService publishOutcomeService;
    private EntityManager entityManager;
    private Query claimQuery;

    private PostPublishScheduler scheduler;

    private Project project;
    private Connection connection;

    @BeforeEach
    void setUp() {
        targetRepository = mock(PostPublishTargetRepository.class);
        connectionResolver = mock(ActiveConnectionResolver.class);
        actionInvocationService = mock(ActionInvocationService.class);
        publishOutcomeService = mock(PublishOutcomeService.class);
        entityManager = mock(EntityManager.class);
        claimQuery = mock(Query.class, RETURNS_SELF);

        when(entityManager.createQuery(anyString())).thenReturn(claimQuery);
        claimSucceeds();

        project = new Project();
        project.setId("project-1");

        connection = new Connection();
        connection.setId("connection-1");
        connection.setProjectId(project.getId());
        connection.setConnectorId("meta");
        connection.setStatus("ACTIVE");

        when(connectionResolver.resolveById(eq("project-1"), eq("connection-1")))
                .thenReturn(Optional.of(connection));
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(ActionResult.ok(Map.of()));

        scheduler = newScheduler(true);
    }

    private PostPublishScheduler newScheduler(boolean enabled) {
        PostPublishScheduler s = new PostPublishScheduler(
                targetRepository, connectionResolver, actionInvocationService, publishOutcomeService, enabled);
        s.entityManager = entityManager;
        s.self = s;
        return s;
    }

    private void claimSucceeds() {
        when(claimQuery.executeUpdate()).thenReturn(1);
    }

    private void claimLost() {
        when(claimQuery.executeUpdate()).thenReturn(0);
    }

    private WorkItem post(String status) {
        WorkItem item = new WorkItem();
        item.setId("post-1");
        item.setProject(project);
        item.setTitle("Launch day teaser");
        item.setDescription("Doors open at nine.");
        item.setCurrentStatus(status);
        return item;
    }

    private PostPublishTarget target(WorkItem owner, String platform, PublishLane lane,
                                     PostPublishTargetState state) {
        PostPublishTarget target = new PostPublishTarget();
        target.setId("target-1");
        target.setWorkItem(owner);
        target.setConnectorId("meta");
        target.setConnectionId("connection-1");
        target.setPlatform(platform);
        target.setLane(lane);
        target.setState(state);
        target.setFireTime(OffsetDateTime.now().minusMinutes(1));
        target.setIdempotencyKey("pub:post-1:facebook:connection-1");
        return target;
    }

    private PostPublishTarget dueTarget() {
        return target(post(PostPublishScheduler.SCHEDULED_STATUS), "facebook",
                PublishLane.APP_MANAGED, PostPublishTargetState.PENDING);
    }

    private void given(PostPublishTarget target) {
        when(targetRepository.findDueAppManagedTargets(any())).thenReturn(List.of(target));
        when(targetRepository.findById(target.getId())).thenReturn(Optional.of(target));
    }

    // --- [auto] A due app-managed target is dispatched within 60 seconds of its fire time ---

    @Test
    void tickIntervalIsWellInsideTheSixtySecondPublishingSlo() throws Exception {
        Method poll = PostPublishScheduler.class.getMethod("poll");
        Scheduled scheduled = poll.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelay()).isEqualTo(30_000L);
        assertThat(scheduled.fixedDelay()).isLessThan(60_000L);
    }

    @Test
    void aDueAppManagedTargetIsDispatchedThroughItsOwnConnectionAndIdempotencyKey() {
        PostPublishTarget target = dueTarget();
        given(target);

        scheduler.poll();

        verify(actionInvocationService).invoke(eq(connection), eq("publish_facebook_post"), any(),
                eq("pub:post-1:facebook:connection-1"), any());
    }

    @Test
    void theClaimAdvancesTheRowToPublishingBeforeTheConnectorIsInvoked() {
        given(dueTarget());

        scheduler.poll();

        verify(entityManager).createQuery(anyString());
        verify(claimQuery).executeUpdate();
        verify(actionInvocationService).invoke(any(), anyString(), any(), anyString(), any());
    }

    @Test
    void theDispatchedInputCarriesThePostsCopyAndItsPublishingHandles() {
        given(dueTarget());

        scheduler.poll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        verify(actionInvocationService).invoke(any(), anyString(), input.capture(), anyString(), any());
        assertThat(input.getValue())
                .containsEntry("message", "Doors open at nine.")
                .containsEntry("work_item_id", "post-1")
                .containsEntry("target_id", "target-1");
    }

    @Test
    void aPerTargetCaptionOverrideWinsOverThePostsSharedCopy() {
        PostPublishTarget target = dueTarget();
        target.setCaptionOverride("Doors open at nine sharp. #launch");
        given(target);

        scheduler.poll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        verify(actionInvocationService).invoke(any(), anyString(), input.capture(), anyString(), any());
        assertThat(input.getValue()).containsEntry("message", "Doors open at nine sharp. #launch");
    }

    @Test
    void eachPlatformIsDispatchedThroughItsOwnPublishActionAndCaptionParameter() {
        assertPlatformDispatch("instagram", "publish_instagram_media", "caption");
        assertPlatformDispatch("youtube", "publish_video", "description");
        assertPlatformDispatch("tiktok", "publish_video", "title");
    }

    private void assertPlatformDispatch(String platform, String expectedAction, String captionParam) {
        setUp();
        PostPublishTarget target = target(post(PostPublishScheduler.SCHEDULED_STATUS), platform,
                PublishLane.APP_MANAGED, PostPublishTargetState.PENDING);
        given(target);

        scheduler.poll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        verify(actionInvocationService).invoke(any(), eq(expectedAction), input.capture(), anyString(), any());
        assertThat(input.getValue()).containsEntry(captionParam, "Doors open at nine.");
    }

    // --- [auto] A repeated tick or a restart never publishes the same (post, target) twice ---

    @ParameterizedTest
    @EnumSource(value = PostPublishTargetState.class,
            names = {"PUBLISHING", "PUBLISHED", "HANDED_OFF", "REVOKED", "FAILED"})
    void aRowThatIsNoLongerPendingIsNeverDispatched(PostPublishTargetState state) {
        given(target(post(PostPublishScheduler.SCHEDULED_STATUS), "facebook", PublishLane.APP_MANAGED, state));

        scheduler.poll();

        verifyNoInteractions(actionInvocationService);
        verify(claimQuery, never()).executeUpdate();
    }

    @Test
    void losingTheClaimRaceToAConcurrentTickNeverPublishes() {
        given(dueTarget());
        claimLost();

        scheduler.poll();

        verify(claimQuery).executeUpdate();
        verifyNoInteractions(actionInvocationService);
    }

    @Test
    void aNativeLaneRowIsIgnoredEvenWhenTheDueQueryHandsItOver() {
        given(target(post(PostPublishScheduler.SCHEDULED_STATUS), "facebook",
                PublishLane.NATIVE, PostPublishTargetState.PENDING));

        scheduler.poll();

        verifyNoInteractions(actionInvocationService);
        verify(claimQuery, never()).executeUpdate();
    }

    @Test
    void aTargetWhosePostIsNotInItsScheduledStatusIsNeverDispatched() {
        given(target(post("DRAFT"), "facebook", PublishLane.APP_MANAGED, PostPublishTargetState.PENDING));

        scheduler.poll();

        verifyNoInteractions(actionInvocationService);
        verify(claimQuery, never()).executeUpdate();
    }

    @Test
    void aRowVanishingBetweenTheDueQueryAndTheClaimIsSkipped() {
        PostPublishTarget target = dueTarget();
        when(targetRepository.findDueAppManagedTargets(any())).thenReturn(List.of(target));
        when(targetRepository.findById(target.getId())).thenReturn(Optional.empty());

        scheduler.poll();

        verifyNoInteractions(actionInvocationService);
    }

    @Test
    void anUnknownPlatformIsSkippedRatherThanDispatchedToAGuessedAction() {
        given(target(post(PostPublishScheduler.SCHEDULED_STATUS), "myspace",
                PublishLane.APP_MANAGED, PostPublishTargetState.PENDING));

        scheduler.poll();

        verifyNoInteractions(actionInvocationService);
        verify(claimQuery, never()).executeUpdate();
    }

    @Test
    void anUnresolvableConnectionIsSkippedWithoutClaimingTheRow() {
        given(dueTarget());
        when(connectionResolver.resolveById(anyString(), anyString())).thenReturn(Optional.empty());

        scheduler.poll();

        verifyNoInteractions(actionInvocationService);
        verify(claimQuery, never()).executeUpdate();
    }

    @Test
    void oneTargetsFailureNeverBlocksTheRestOfTheTick() {
        PostPublishTarget exploding = dueTarget();
        exploding.setId("target-boom");
        PostPublishTarget healthy = dueTarget();
        healthy.setId("target-ok");
        healthy.setIdempotencyKey("pub:post-1:facebook:connection-ok");

        when(targetRepository.findDueAppManagedTargets(any())).thenReturn(List.of(exploding, healthy));
        when(targetRepository.findById("target-boom")).thenThrow(new IllegalStateException("boom"));
        when(targetRepository.findById("target-ok")).thenReturn(Optional.of(healthy));

        scheduler.poll();

        verify(actionInvocationService).invoke(any(), anyString(), any(),
                eq("pub:post-1:facebook:connection-ok"), any());
    }

    @Test
    void aConnectorRejectionIsRecordedAsAnOutcomeRatherThanStrandingTheRowInPublishing() {
        given(dueTarget());
        ActionResult rejection =
                ActionResult.error("(#100) The parameter image_url is required");
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(rejection);

        scheduler.poll();

        // Claimed, dispatched, and then resolved: the row leaves PUBLISHING for a terminal state carrying
        // the platform's own words, which is what retry and the roll-up need to find.
        verify(claimQuery).executeUpdate();
        verify(publishOutcomeService).recordOutcome("target-1", rejection);
    }

    @Test
    void aSuccessfulDispatchIsRecordedAsAnOutcomeToo() {
        given(dueTarget());
        ActionResult published = ActionResult.ok(Map.of(
                "post_id", "page_1_post_99", "permalink", "https://facebook.com/page_1/posts/99"));
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(published);

        scheduler.poll();

        verify(publishOutcomeService).recordOutcome("target-1", published);
    }

    @Test
    void aDispatchThatReportedNothingBackIsStillHandedToTheOutcomeRecorder() {
        given(dueTarget());
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(null);

        scheduler.poll();

        verify(publishOutcomeService).recordOutcome("target-1", null);
    }

    @Test
    void aRowThatWasNeverClaimedNeverRecordsAnOutcome() {
        given(dueTarget());
        claimLost();

        scheduler.poll();

        verifyNoInteractions(publishOutcomeService);
    }

    // --- [auto] The scheduler can be disabled by config for tests and the local profile ---

    @Test
    void aDisabledSchedulerNeverEvenQueriesForDueTargets() {
        scheduler = newScheduler(false);
        given(dueTarget());

        scheduler.poll();

        verify(targetRepository, never()).findDueAppManagedTargets(any());
        verifyNoInteractions(actionInvocationService);
        verifyNoInteractions(publishOutcomeService);
        verifyNoInteractions(entityManager);
    }

    @Test
    void sensitiveValuesAreDeclaredEmptyRatherThanNull() {
        given(dueTarget());

        scheduler.poll();

        ArgumentCaptor<Collection<String>> sensitive = ArgumentCaptor.forClass(Collection.class);
        verify(actionInvocationService).invoke(any(), anyString(), any(), anyString(), sensitive.capture());
        assertThat(sensitive.getValue()).isEmpty();
    }
}
