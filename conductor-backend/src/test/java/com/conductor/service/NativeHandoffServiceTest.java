package com.conductor.service;

import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.conductor.service.publish.PublishingWorkflow;
import com.conductor.service.publish.PublishPlatformRegistry;
import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.PublishLane;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.integration.ActionResult;
import com.conductor.repository.PostPublishTargetRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetAssetRepository;

/**
 * Unit coverage for {@link NativeHandoffService}'s decision logic: which native targets it hands to a
 * platform and when, what it sends, and — the load-bearing half — that every exit from the scheduled
 * status takes a handed-off post back down before anything else happens. The DB-level guarantees (the
 * deferred sweep's finder, the atomic claim, and the caller-transaction rollback on a failed revocation)
 * are proven against real Postgres in {@code NativeHandoffIntegrationTest}.
 *
 * <p>The publish and revoke action bodies (T5.2 Facebook, T5.4 YouTube) are deliberately not exercised:
 * {@link ActionInvocationService} is the seam, and it is stubbed here.
 */
class NativeHandoffServiceTest {

    private AssetRepository assetRepository;
    private PostPublishTargetAssetRepository targetAssetRepository;
    private PostPublishTargetRepository targetRepository;
    private ActiveConnectionResolver connectionResolver;
    private ActionInvocationService actionInvocationService;
    private PublishOutcomeService publishOutcomeService;
    private EntityManager entityManager;
    private Query bulkUpdate;

    private NativeHandoffService service;

    private Project project;
    private Connection connection;

    @BeforeEach
    void setUp() {
        targetRepository = mock(PostPublishTargetRepository.class);
        assetRepository = mock(AssetRepository.class);
        targetAssetRepository = mock(PostPublishTargetAssetRepository.class);
        connectionResolver = mock(ActiveConnectionResolver.class);
        actionInvocationService = mock(ActionInvocationService.class);
        publishOutcomeService = mock(PublishOutcomeService.class);
        entityManager = mock(EntityManager.class);
        bulkUpdate = mock(Query.class, RETURNS_SELF);

        when(entityManager.createQuery(anyString())).thenReturn(bulkUpdate);
        when(bulkUpdate.executeUpdate()).thenReturn(1);

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
                .thenReturn(ActionResult.ok(Map.of("post_id", "page_1_post_99", "video_id", "vid_99")));

        service = newService(true);
    }

    private NativeHandoffService newService(boolean enabled) {
        PublishPlatformRegistry registry = new PublishPlatformRegistry();
        NativeHandoffService s = new NativeHandoffService(
                registry, new PublishingWorkflow(registry, org.mockito.Mockito.mock(WorkflowDefinitionResolver.class)),
                targetRepository, connectionResolver, actionInvocationService, publishOutcomeService,
                new PublishInputBuilder(registry, new PublishTargetMediaResolver(assetRepository, targetAssetRepository)),
                enabled);
        s.entityManager = entityManager;
        s.self = s;
        return s;
    }

    // --- fixtures -------------------------------------------------------------------------------

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
                                     PostPublishTargetState state, OffsetDateTime fireTime) {
        PostPublishTarget target = new PostPublishTarget();
        target.setId("target-1");
        target.setWorkItem(owner);
        target.setConnectorId("meta");
        target.setConnectionId("connection-1");
        target.setPlatform(platform);
        target.setLane(lane);
        target.setState(state);
        target.setFireTime(fireTime);
        target.setIdempotencyKey("pub:post-1:" + platform + ":connection-1");
        return target;
    }

    private PostPublishTarget pendingNative(String platform, Duration lead) {
        return target(post(NativeHandoffService.SCHEDULED_STATUS), platform, PublishLane.NATIVE,
                PostPublishTargetState.PENDING, OffsetDateTime.now().plus(lead));
    }

    /** Wires {@code target} up for both entry points: the per-post hand-off and the deferred sweep. */
    private void given(PostPublishTarget target) {
        when(targetRepository.findAllByWorkItemIdAndState("post-1", PostPublishTargetState.PENDING))
                .thenReturn(List.of(target));
        when(targetRepository.findNativeHandoffTargets(any())).thenReturn(List.of(target));
        when(targetRepository.findById(target.getId())).thenReturn(Optional.of(target));
    }

    private void givenHandedOff(PostPublishTarget target) {
        when(targetRepository.findAllByWorkItemIdAndState("post-1", PostPublishTargetState.HANDED_OFF))
                .thenReturn(List.of(target));
        when(targetRepository.findById(target.getId())).thenReturn(Optional.of(target));
    }

    private Map<String, Object> capturedInput(String actionId) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        verify(actionInvocationService).invoke(any(), eq(actionId), input.capture(), anyString(), any());
        return input.getValue();
    }

    // --- [auto] Native handoff occurs only inside the platform window ---------------------------

    @Test
    void aFacebookTargetTwoDaysOutIsHandedOffWithItsScheduleParameter() {
        PostPublishTarget target = pendingNative("facebook", Duration.ofDays(2));
        given(target);

        service.handoffForPost(target.getWorkItem());

        Map<String, Object> input = capturedInput("publish_facebook_post");
        assertThat(input)
                .containsEntry("scheduled_publish_time", target.getFireTime().toInstant().toString())
                .containsEntry("message", "Doors open at nine.")
                .containsEntry("work_item_id", "post-1")
                .containsEntry("target_id", "target-1");
    }

    @Test
    void aHandedOffFacebookTargetStoresThePlatformPostId() {
        given(pendingNative("facebook", Duration.ofDays(2)));

        service.handoffForPost(post(NativeHandoffService.SCHEDULED_STATUS));

        verify(bulkUpdate).setParameter("platformPostId", "page_1_post_99");
    }

    @Test
    void theHandoffClaimMovesTheRowToHandedOffBeforeTheConnectorIsInvoked() {
        given(pendingNative("facebook", Duration.ofDays(2)));

        service.handoffForPost(post(NativeHandoffService.SCHEDULED_STATUS));

        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(entityManager, org.mockito.Mockito.atLeastOnce()).createQuery(query.capture());
        assertThat(query.getAllValues().get(0))
                .contains("HANDED_OFF")
                .contains("state = com.conductor.entity.PostPublishTargetState.PENDING")
                .contains("lane = com.conductor.entity.PublishLane.NATIVE");
    }

    @Test
    void aFacebookTargetFortyFiveDaysOutStaysPendingAndIsNeverHandedOff() {
        given(pendingNative("facebook", Duration.ofDays(45)));

        service.handoffForPost(post(NativeHandoffService.SCHEDULED_STATUS));

        verifyNoInteractions(actionInvocationService);
        verify(bulkUpdate, never()).executeUpdate();
    }

    @Test
    void aFacebookTargetInsideTheTenMinuteFloorIsNeverHandedOff() {
        given(pendingNative("facebook", Duration.ofMinutes(3)));

        service.handoffForPost(post(NativeHandoffService.SCHEDULED_STATUS));

        verifyNoInteractions(actionInvocationService);
        verify(bulkUpdate, never()).executeUpdate();
    }

    @Test
    void aYouTubeTargetFarInTheFutureIsHandedOffImmediatelyBecauseYouTubeHasNoThirtyDayLimit() {
        PostPublishTarget target = pendingNative("youtube", Duration.ofDays(400));
        given(target);

        service.handoffForPost(target.getWorkItem());

        Map<String, Object> input = capturedInput("publish_video");
        assertThat(input)
                .containsEntry("publish_at", target.getFireTime().toInstant().toString())
                .containsEntry("privacy_status", "private")
                .containsEntry("description", "Doors open at nine.");
    }

    // --- [auto] ...and is deferred and later completed for far-future fire times -----------------

    @Test
    void aDeferredFacebookTargetIsHandedOffOnceItsFireTimeComesInsideTheThirtyDayWindow() {
        OffsetDateTime fireTime = OffsetDateTime.now().plusDays(45);
        PostPublishTarget target = target(post(NativeHandoffService.SCHEDULED_STATUS), "facebook",
                PublishLane.NATIVE, PostPublishTargetState.PENDING, fireTime);
        given(target);

        service.runTick(OffsetDateTime.now());
        verifyNoInteractions(actionInvocationService);

        // Twenty days later the same row is now twenty-five days out — inside the window.
        service.runTick(OffsetDateTime.now().plusDays(20));

        verify(actionInvocationService).invoke(any(), eq("publish_facebook_post"), any(),
                eq("pub:post-1:facebook:connection-1"), any());
    }

    @Test
    void theDeferredSweepLooksFurtherAheadThanFacebooksWindowSoYouTubeIsNeverStranded() {
        given(pendingNative("youtube", Duration.ofDays(400)));

        OffsetDateTime now = OffsetDateTime.now();
        service.runTick(now);

        ArgumentCaptor<OffsetDateTime> horizon = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(targetRepository).findNativeHandoffTargets(horizon.capture());
        assertThat(horizon.getValue()).isAfter(now.plus(NativeHandoffService.FACEBOOK_MAX_LEAD));
        verify(actionInvocationService).invoke(any(), eq("publish_video"), any(), anyString(), any());
    }

    @Test
    void theDeferredSweepNeverHandsOffAPostThatIsNoLongerScheduled() {
        PostPublishTarget target = target(post("APPROVED"), "facebook", PublishLane.NATIVE,
                PostPublishTargetState.PENDING, OffsetDateTime.now().plusDays(2));
        given(target);

        service.runTick(OffsetDateTime.now());

        verifyNoInteractions(actionInvocationService);
        verify(bulkUpdate, never()).executeUpdate();
    }

    @Test
    void aDisabledSweepNeverEvenQueriesForDeferredTargets() {
        service = newService(false);
        given(pendingNative("facebook", Duration.ofDays(2)));

        service.sweepDeferredHandoffs();

        verify(targetRepository, never()).findNativeHandoffTargets(any());
        verifyNoInteractions(actionInvocationService);
    }

    @Test
    void theDeferredSweepTicksOftenEnoughToCloseAThirtyDayBoundary() throws Exception {
        Method sweep = NativeHandoffService.class.getMethod("sweepDeferredHandoffs");
        Scheduled scheduled = sweep.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelay()).isEqualTo(60_000L);
    }

    @ParameterizedTest
    @EnumSource(value = PostPublishTargetState.class,
            names = {"HANDED_OFF", "PUBLISHING", "PUBLISHED", "REVOKED", "FAILED"})
    void aRowThatIsNoLongerPendingIsNeverHandedOffAgain(PostPublishTargetState state) {
        given(target(post(NativeHandoffService.SCHEDULED_STATUS), "facebook", PublishLane.NATIVE,
                state, OffsetDateTime.now().plusDays(2)));

        service.runTick(OffsetDateTime.now());

        verifyNoInteractions(actionInvocationService);
        verify(bulkUpdate, never()).executeUpdate();
    }

    @Test
    void losingTheClaimRaceToAConcurrentPassNeverHandsOff() {
        given(pendingNative("facebook", Duration.ofDays(2)));
        when(bulkUpdate.executeUpdate()).thenReturn(0);

        service.runTick(OffsetDateTime.now());

        verify(bulkUpdate).executeUpdate();
        verifyNoInteractions(actionInvocationService);
    }

    @Test
    void anUnresolvableConnectionIsSkippedWithoutClaimingTheRow() {
        given(pendingNative("facebook", Duration.ofDays(2)));
        when(connectionResolver.resolveById(anyString(), anyString())).thenReturn(Optional.empty());

        service.handoffForPost(post(NativeHandoffService.SCHEDULED_STATUS));

        verifyNoInteractions(actionInvocationService);
        verify(bulkUpdate, never()).executeUpdate();
    }

    // --- [auto] Any exit from Scheduled revokes handed-off targets, and nothing goes live --------

    @Test
    void unschedulingAPostDeletesItsScheduledFacebookPostAndRevokesTheRow() {
        PostPublishTarget target = target(post(NativeHandoffService.SCHEDULED_STATUS), "facebook",
                PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF, OffsetDateTime.now().plusDays(2));
        target.setPlatformPostId("page_1_post_99");
        givenHandedOff(target);

        service.unschedule(target.getWorkItem());

        Map<String, Object> input = capturedInput("delete_facebook_post");
        assertThat(input).containsEntry("post_id", "page_1_post_99");
        assertThat(target.getState()).isEqualTo(PostPublishTargetState.REVOKED);
        verify(targetRepository).save(target);
    }

    @Test
    void unschedulingAHandedOffYouTubeTargetRePrivatizesItAndClearsPublishAt() {
        PostPublishTarget target = target(post(NativeHandoffService.SCHEDULED_STATUS), "youtube",
                PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF, OffsetDateTime.now().plusDays(2));
        target.setPlatformPostId("vid_99");
        givenHandedOff(target);

        service.unschedule(target.getWorkItem());

        Map<String, Object> input = capturedInput("unpublish_video");
        assertThat(input).containsEntry("video_id", "vid_99");
        assertThat(input).containsEntry("privacy_status", "private");
        assertThat(input).containsKey("publish_at");
        assertThat(input.get("publish_at")).isNull();
        assertThat(target.getState()).isEqualTo(PostPublishTargetState.REVOKED);
    }

    @Test
    void revocationUsesAKeyOfItsOwnSoItCanNeverCollapseOntoThePublishInvocation() {
        PostPublishTarget target = target(post(NativeHandoffService.SCHEDULED_STATUS), "facebook",
                PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF, OffsetDateTime.now().plusDays(2));
        target.setPlatformPostId("page_1_post_99");
        givenHandedOff(target);

        service.unschedule(target.getWorkItem());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(actionInvocationService).invoke(any(), anyString(), any(), key.capture(), any());
        assertThat(key.getValue())
                .isNotEqualTo(target.getIdempotencyKey())
                .contains(target.getIdempotencyKey())
                .contains("page_1_post_99");
    }

    @Test
    void unschedulingLeavesTargetsThatWereNeverHandedOffAloneSoThePostStaysReschedulable() {
        PostPublishTarget pending = pendingNative("facebook", Duration.ofDays(2));
        when(targetRepository.findAllByWorkItemIdAndState("post-1", PostPublishTargetState.HANDED_OFF))
                .thenReturn(List.of());

        service.unschedule(pending.getWorkItem());

        verifyNoInteractions(actionInvocationService);
        assertThat(pending.getState()).isEqualTo(PostPublishTargetState.PENDING);
        verify(targetRepository, never()).save(any());
    }

    // --- [auto] Revocation is idempotent and safe on targets never handed off -------------------

    @Test
    void revokingATargetWithNoPlatformPostIdIsANoOpAndDoesNotFail() {
        PostPublishTarget target = target(post(NativeHandoffService.SCHEDULED_STATUS), "facebook",
                PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF, OffsetDateTime.now().plusDays(2));

        service.revoke(target);

        verifyNoInteractions(actionInvocationService);
        assertThat(target.getState()).isEqualTo(PostPublishTargetState.REVOKED);
    }

    @Test
    void revokingAnAlreadyRevokedTargetTouchesNothing() {
        PostPublishTarget target = target(post(NativeHandoffService.SCHEDULED_STATUS), "facebook",
                PublishLane.NATIVE, PostPublishTargetState.REVOKED, OffsetDateTime.now().plusDays(2));
        target.setPlatformPostId("page_1_post_99");

        service.revoke(target);

        verifyNoInteractions(actionInvocationService);
        verify(targetRepository, never()).save(any());
    }

    @Test
    void revokingTheSameTargetTwiceCallsThePlatformUnderOneIdempotencyKey() {
        PostPublishTarget target = target(post(NativeHandoffService.SCHEDULED_STATUS), "facebook",
                PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF, OffsetDateTime.now().plusDays(2));
        target.setPlatformPostId("page_1_post_99");

        service.revoke(target);
        service.revoke(target);

        // The second call short-circuits on REVOKED, so exactly one platform call was ever made.
        verify(actionInvocationService).invoke(any(), eq("delete_facebook_post"), any(), anyString(), any());
    }

    // --- [auto] A failed revocation propagates rather than leaving a live scheduled post ---------

    @Test
    void aFailedRevocationThrowsSoTheCallersStatusChangeNeverCommits() {
        PostPublishTarget target = target(post(NativeHandoffService.SCHEDULED_STATUS), "facebook",
                PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF, OffsetDateTime.now().plusDays(2));
        target.setPlatformPostId("page_1_post_99");
        givenHandedOff(target);
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(ActionResult.error("Graph API rejected the delete"));

        assertThatThrownBy(() -> service.unschedule(target.getWorkItem()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("page_1_post_99");

        assertThat(target.getState()).isEqualTo(PostPublishTargetState.HANDED_OFF);
        verify(targetRepository, never()).save(any());
    }

    @Test
    void aRevocationWhoseConnectionIsGoneThrowsRatherThanSilentlyLeavingThePostLive() {
        PostPublishTarget target = target(post(NativeHandoffService.SCHEDULED_STATUS), "facebook",
                PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF, OffsetDateTime.now().plusDays(2));
        target.setPlatformPostId("page_1_post_99");
        givenHandedOff(target);
        when(connectionResolver.resolveById(anyString(), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.unschedule(target.getWorkItem()))
                .isInstanceOf(BusinessException.class);

        assertThat(target.getState()).isEqualTo(PostPublishTargetState.HANDED_OFF);
    }

    @Test
    void aTargetRevokedWhileItsHandoffWasInFlightIsTakenBackDownImmediately() {
        given(pendingNative("facebook", Duration.ofDays(2)));
        // Claim succeeds; the completion update finds the row already REVOKED and touches nothing.
        when(bulkUpdate.executeUpdate()).thenReturn(1, 0);

        service.handoffForPost(post(NativeHandoffService.SCHEDULED_STATUS));

        verify(actionInvocationService).invoke(any(), eq("publish_facebook_post"), any(), anyString(), any());
        verify(actionInvocationService).invoke(any(), eq("delete_facebook_post"), any(), anyString(), any());
    }

    // --- [auto] APP_MANAGED rows are untouched by every path here --------------------------------

    @Test
    void anAppManagedRowIsNeverHandedOffEvenWhenHandedToThisService() {
        PostPublishTarget target = target(post(NativeHandoffService.SCHEDULED_STATUS), "facebook",
                PublishLane.APP_MANAGED, PostPublishTargetState.PENDING, OffsetDateTime.now().plusDays(2));
        given(target);

        service.handoffForPost(target.getWorkItem());
        service.runTick(OffsetDateTime.now());

        verifyNoInteractions(actionInvocationService);
        verify(bulkUpdate, never()).executeUpdate();
    }

    @Test
    void anAppManagedRowIsNeverRevokedByThisService() {
        PostPublishTarget target = target(post(NativeHandoffService.SCHEDULED_STATUS), "facebook",
                PublishLane.APP_MANAGED, PostPublishTargetState.HANDED_OFF, OffsetDateTime.now().plusDays(2));
        target.setPlatformPostId("page_1_post_99");
        givenHandedOff(target);

        service.unschedule(target.getWorkItem());

        verifyNoInteractions(actionInvocationService);
        assertThat(target.getState()).isEqualTo(PostPublishTargetState.HANDED_OFF);
        verify(targetRepository, never()).save(any());
    }

    // --- miscellaneous gating --------------------------------------------------------------------

    @Test
    void aPerTargetCaptionOverrideWinsOverThePostsSharedCopy() {
        PostPublishTarget target = pendingNative("facebook", Duration.ofDays(2));
        target.setCaptionOverride("Doors open at nine sharp. #launch");
        given(target);

        service.handoffForPost(target.getWorkItem());

        assertThat(capturedInput("publish_facebook_post"))
                .containsEntry("message", "Doors open at nine sharp. #launch");
    }

    @Test
    void aPlatformWithNoNativeHandoffIsSkippedRatherThanGuessedAt() {
        given(pendingNative("instagram", Duration.ofDays(2)));

        service.handoffForPost(post(NativeHandoffService.SCHEDULED_STATUS));

        verifyNoInteractions(actionInvocationService);
        verify(bulkUpdate, never()).executeUpdate();
    }

    @Test
    void aRejectedHandoffIsRecordedAsAFailedOutcomeRatherThanQueuedForAnotherAttempt() {
        PostPublishTarget target = pendingNative("facebook", Duration.ofDays(2));
        given(target);
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(ActionResult.error("Meta action 'publish_facebook_post' is not implemented yet"));

        service.handoffForPost(target.getWorkItem());

        // Claimed HANDED_OFF, then filed through the one place outcomes are recorded — never returned to
        // PENDING, because the platform may have accepted the post before failing to say so.
        verify(publishOutcomeService).recordFailure("target-1",
                "Meta action 'publish_facebook_post' is not implemented yet");
    }

    @Test
    void aHandoffThatReportedNothingBackIsAlsoRecordedAsAFailedOutcome() {
        PostPublishTarget target = pendingNative("facebook", Duration.ofDays(2));
        given(target);
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(null);

        service.handoffForPost(target.getWorkItem());

        verify(publishOutcomeService).recordFailure(eq("target-1"), anyString());
    }

    @Test
    void anExpiredTokenRejectionGoesThroughTheOutcomeRecorderSoTheConnectionCanBeMarkedUnhealthy() {
        PostPublishTarget target = pendingNative("facebook", Duration.ofDays(2));
        given(target);
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(ActionResult.error("OAuthException: Error validating access token: Session has expired"));

        service.handoffForPost(target.getWorkItem());

        // The classification (and the health report it triggers) is PublishOutcomeService's; what matters
        // here is that the hand-off no longer bypasses it with a plain state write.
        verify(publishOutcomeService).recordFailure(eq("target-1"),
                eq("OAuthException: Error validating access token: Session has expired"));
    }

    @Test
    void aSuccessfulHandoffRecordsNoOutcomeBecauseThePostHasNotPublishedYet() {
        given(pendingNative("facebook", Duration.ofDays(2)));

        service.handoffForPost(post(NativeHandoffService.SCHEDULED_STATUS));

        verifyNoInteractions(publishOutcomeService);
    }

    @Test
    void oneTargetsFailureNeverBlocksTheRestOfTheSweep() {
        PostPublishTarget exploding = pendingNative("facebook", Duration.ofDays(2));
        exploding.setId("target-boom");
        PostPublishTarget healthy = pendingNative("facebook", Duration.ofDays(2));
        healthy.setId("target-ok");
        healthy.setIdempotencyKey("pub:post-1:facebook:connection-ok");

        when(targetRepository.findNativeHandoffTargets(any())).thenReturn(List.of(exploding, healthy));
        when(targetRepository.findById("target-boom")).thenThrow(new IllegalStateException("boom"));
        when(targetRepository.findById("target-ok")).thenReturn(Optional.of(healthy));

        service.runTick(OffsetDateTime.now());

        verify(actionInvocationService).invoke(any(), anyString(), any(),
                eq("pub:post-1:facebook:connection-ok"), any());
    }

    // --- [auto] Unscheduling stands manual targets down too (MKT-2) -----------------------------

    private PostPublishTarget manual(PostPublishTargetState state) {
        PostPublishTarget target = new PostPublishTarget();
        target.setId("manual-1");
        target.setWorkItem(post(NativeHandoffService.SCHEDULED_STATUS));
        target.setPlatform("tiktok");
        target.setLane(PublishLane.MANUAL);
        target.setState(state);
        target.setFireTime(OffsetDateTime.now());
        target.setIdempotencyKey("pub:post-1:tiktok:manual");
        return target;
    }

    private void givenAwaitingManual(PostPublishTarget target) {
        when(targetRepository.findAllByWorkItemIdAndState("post-1", PostPublishTargetState.AWAITING_MANUAL))
                .thenReturn(List.of(target));
    }

    @Test
    void unschedulingStandsAWaitingManualTargetBackDownToPending() {
        // AWAITING_MANUAL means "someone still has to post this". Once the Post is pulled back that is no
        // longer true, and leaving the row flagged keeps asking a human to publish a post that has been
        // withdrawn — the manual-lane equivalent of leaving a scheduled post live on a platform.
        PostPublishTarget target = manual(PostPublishTargetState.AWAITING_MANUAL);
        givenAwaitingManual(target);

        service.unschedule(target.getWorkItem());

        assertThat(target.getState()).isEqualTo(PostPublishTargetState.PENDING);
        verify(targetRepository).save(target);
    }

    @Test
    void unschedulingLeavesAManualTargetAHumanAlreadyPublishedAlone() {
        // A manual target marked PUBLISHED describes a post that really is live and out of Conductor's
        // reach. Nothing here can take it down, and reverting the row would erase the only record of it.
        PostPublishTarget published = manual(PostPublishTargetState.PUBLISHED);
        when(targetRepository.findAllByWorkItemIdAndState("post-1", PostPublishTargetState.AWAITING_MANUAL))
                .thenReturn(List.of());

        service.unschedule(published.getWorkItem());

        assertThat(published.getState()).isEqualTo(PostPublishTargetState.PUBLISHED);
        verify(targetRepository, never()).save(published);
    }

    @Test
    void standingDownAManualTargetCallsNoPlatform() {
        // There is no platform to call. If this ever invoked an action it would mean a manual target had
        // acquired a connector, which the DB CHECK constraint forbids.
        givenAwaitingManual(manual(PostPublishTargetState.AWAITING_MANUAL));

        service.unschedule(post(NativeHandoffService.SCHEDULED_STATUS));

        verifyNoInteractions(actionInvocationService);
    }

}
