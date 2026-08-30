package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.PublishLane;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.integration.ActionResult;
import com.conductor.entity.Asset;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DB-backed coverage for {@link NativeHandoffService} against real Postgres. Three things can only be
 * proven here: that a deferred hand-off really is picked up by a later sweep once its fire time comes
 * inside the platform's window, that the state a revocation writes actually lands, and — the load-bearing
 * one — that a failed revocation rolls the caller's status change back, so a Post can never leave the
 * scheduled status while its post is still scheduled on a platform.
 *
 * <p>It also carries {@link NativePublishConfirmationPoller}'s DB-backed coverage — the other half of the
 * same lane, and the only path that ever moves a {@code HANDED_OFF} row on. Both live here because they
 * share every fixture and, being one Spring context, one Postgres.
 *
 * <p>{@link ActionInvocationService} is mocked: the publish, revoke and read action bodies are separate
 * tasks (T5.2 Facebook, T5.4 YouTube, and the {@code get_facebook_post}/{@code get_video_status} reads),
 * and this test is about which rows are handed off, taken back down or confirmed, not about what a
 * connector does with them. The service's own {@code @Scheduled} sweep stays off in this
 * profile (its finder is globally scoped — see the class javadoc); every test drives {@code runTick}
 * explicitly and asserts only on rows it created, per {@code docs/testing-guidelines.md}.
 */
class NativeHandoffIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private NativeHandoffService service;
    @Autowired private NativePublishConfirmationPoller confirmationPollerBean;

    /**
     * The service's raw target, used ONLY to set tuning fields. {@code batchSize} lives on the target
     * instance, so assigning it through the CGLIB proxy writes a field nothing reads and {@code runSweep}
     * would silently keep the default batch against a globally scoped finder — passing today, but flaky the
     * moment another test leaves enough due rows to fill the batch. Method CALLS must still go through
     * {@code service} (the proxy): {@code unschedule} is {@code @Transactional}, and invoking it on the
     * target runs outside a session, which fails on the first lazy association.
     */
    private NativeHandoffService serviceTarget;

    /**
     * The poller behind its transactional proxy. Its tuning fields ({@code batchSize},
     * {@code maxConfirmationAttempts}) live on the target instance, so setting them on the CGLIB proxy
     * would be silently ignored — the proxy has fields of its own that nothing reads. Its own
     * {@code self} reference is still the proxy, so the {@code REQUIRES_NEW} claim keeps its transaction.
     */
    private NativePublishConfirmationPoller confirmationPoller;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ConnectionRepository connectionRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockitoBean private ActionInvocationService actionInvocationService;

    private User creator;
    private Project project;
    private String connectionId;
    private int nextSequenceNumber = 1;

    @BeforeEach
    void setUp() {
        serviceTarget = AopTestUtils.getTargetObject(service);
        confirmationPoller = AopTestUtils.getTargetObject(confirmationPollerBean);
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(ActionResult.ok(Map.of("post_id", "page_1_post_99", "video_id", "vid_99")));

        creator = new User();
        creator.setFirebaseUid("test-uid-" + UUID.randomUUID());
        creator.setEmail(UUID.randomUUID() + "@example.com");
        creator.setName("Native Handoff Creator");
        creator = userRepository.save(creator);

        project = new Project();
        project.setName("Native Handoff Project");
        project.setKey("NH" + String.valueOf(UUID.randomUUID()).substring(0, 6).toUpperCase());
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

    private PostPublishTarget target(WorkItem owner, String platform, PublishLane lane,
                                     PostPublishTargetState state, OffsetDateTime fireTime,
                                     String platformPostId) {
        PostPublishTarget target = new PostPublishTarget();
        target.setWorkItem(owner);
        target.setConnectorId("meta");
        target.setConnectionId(connectionId);
        target.setPlatform(platform);
        target.setLane(lane);
        target.setState(state);
        target.setFireTime(fireTime);
        target.setPlatformPostId(platformPostId);
        target.setIdempotencyKey("pub:" + UUID.randomUUID());
        return targetRepository.saveAndFlush(target);
    }

    private PostPublishTarget pendingNative(String platform, OffsetDateTime fireTime) {
        return target(post(NativeHandoffService.SCHEDULED_STATUS), platform, PublishLane.NATIVE,
                PostPublishTargetState.PENDING, fireTime, null);
    }

    private PostPublishTarget handedOff(String platform, String platformPostId) {
        return target(post(NativeHandoffService.SCHEDULED_STATUS), platform, PublishLane.NATIVE,
                PostPublishTargetState.HANDED_OFF, OffsetDateTime.now().plusDays(2), platformPostId);
    }

    private PostPublishTarget reload(PostPublishTarget target) {
        return targetRepository.findById(target.getId()).orElseThrow();
    }

    private WorkItem reload(WorkItem post) {
        return workItemRepository.findById(post.getId()).orElseThrow();
    }

    private Map<String, Object> capturedInput(String actionId, String idempotencyKeyFragment) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(actionInvocationService).invoke(any(), eq(actionId), input.capture(), key.capture(), any());
        assertThat(key.getValue()).contains(idempotencyKeyFragment);
        return input.getValue();
    }

    @AfterEach
    void resetBatchSize() {
        serviceTarget.batchSize = 50;
        confirmationPoller.batchSize = 50;
        confirmationPoller.maxConfirmationAttempts = 20;
    }

    private void runSweep(OffsetDateTime now) {
        // Keep the (globally scoped) batch generous so this test's own row is always reached.
        serviceTarget.batchSize = 500;
        service.runTick(now);
    }

    private void runConfirmation(OffsetDateTime now) {
        confirmationPoller.batchSize = 500;
        confirmationPoller.runTick(now);
    }

    /** A native row the platform already holds, whose fire time has passed — the poller's whole input. */
    private PostPublishTarget dueHandedOff(String platform, String platformPostId) {
        return target(post(NativeHandoffService.SCHEDULED_STATUS), platform, PublishLane.NATIVE,
                PostPublishTargetState.HANDED_OFF, OffsetDateTime.now().minusMinutes(5), platformPostId);
    }

    /** Answers only the confirmation read for {@code target}, so rows other tests left behind are unaffected. */
    private void platformAnswersFor(PostPublishTarget target, Map<String, Object> output) {
        when(actionInvocationService.invoke(any(), anyString(), any(),
                org.mockito.ArgumentMatchers.startsWith("confirm:" + target.getIdempotencyKey() + ":"), any()))
                .thenReturn(ActionResult.ok(output));
    }

    private void verifyConfirmationRead(PostPublishTarget target, String actionId, int attempt) {
        verify(actionInvocationService).invoke(any(), eq(actionId), any(),
                eq("confirm:" + target.getIdempotencyKey() + ":" + attempt), any());
    }

    // --- [auto] Native handoff occurs only inside the platform window ----------------------------

    @Test
    void aFacebookTargetTwoDaysOutIsHandedOffWithTheScheduleParameterAndStoresThePlatformPostId() {
        OffsetDateTime fireTime = OffsetDateTime.now().plusDays(2);
        PostPublishTarget target = pendingNative("facebook", fireTime);

        service.handoffForPost(target.getWorkItem());

        assertThat(capturedInput("publish_facebook_post", target.getIdempotencyKey()))
                .containsEntry("scheduled_publish_time", reload(target).getFireTime().toInstant().toString())
                .containsEntry("message", "Doors open at nine.");
        PostPublishTarget stored = reload(target);
        assertThat(stored.getState()).isEqualTo(PostPublishTargetState.HANDED_OFF);
        assertThat(stored.getPlatformPostId()).isEqualTo("page_1_post_99");
    }

    @Test
    void aFacebookTargetFortyFiveDaysOutStaysPendingUntilItsFireTimeComesInsideTheThirtyDayWindow() {
        OffsetDateTime now = OffsetDateTime.now();
        PostPublishTarget target = pendingNative("facebook", now.plusDays(45));

        runSweep(now);

        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.PENDING);
        verify(actionInvocationService, never())
                .invoke(any(), anyString(), any(), eq(target.getIdempotencyKey()), any());

        // Twenty days later the same row is twenty-five days out — now inside Facebook's window.
        runSweep(now.plusDays(20));

        PostPublishTarget stored = reload(target);
        assertThat(stored.getState()).isEqualTo(PostPublishTargetState.HANDED_OFF);
        assertThat(stored.getPlatformPostId()).isEqualTo("page_1_post_99");
    }

    @Test
    void aYouTubeTargetFarInTheFutureIsHandedOffImmediatelyBecauseYouTubeHasNoThirtyDayLimit() {
        OffsetDateTime fireTime = OffsetDateTime.now().plusDays(400);
        PostPublishTarget target = pendingNative("youtube", fireTime);

        service.handoffForPost(target.getWorkItem());

        assertThat(capturedInput("publish_video", target.getIdempotencyKey()))
                .containsEntry("publish_at", reload(target).getFireTime().toInstant().toString())
                .containsEntry("privacy_status", "private");
        PostPublishTarget stored = reload(target);
        assertThat(stored.getState()).isEqualTo(PostPublishTargetState.HANDED_OFF);
        assertThat(stored.getPlatformPostId()).isEqualTo("vid_99");
    }

    @Test
    void handingOffTheSamePostTwiceHandsItToThePlatformExactlyOnce() {
        PostPublishTarget target = pendingNative("facebook", OffsetDateTime.now().plusDays(2));

        service.handoffForPost(target.getWorkItem());
        service.handoffForPost(target.getWorkItem());

        verify(actionInvocationService)
                .invoke(any(), eq("publish_facebook_post"), any(), eq(target.getIdempotencyKey()), any());
        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.HANDED_OFF);
    }

    @Test
    void theDeferredSweepNeverHandsOffAPostThatWasUnscheduledBackToApproved() {
        OffsetDateTime now = OffsetDateTime.now();
        PostPublishTarget target = target(post("APPROVED"), "facebook", PublishLane.NATIVE,
                PostPublishTargetState.PENDING, now.plusDays(2), null);

        runSweep(now);

        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.PENDING);
        verify(actionInvocationService, never())
                .invoke(any(), anyString(), any(), eq(target.getIdempotencyKey()), any());
    }

    @Test
    void theScheduledSweepIsDisabledByConfigInThisProfileSoNothingFiresOnItsOwn() {
        PostPublishTarget target = pendingNative("facebook", OffsetDateTime.now().plusDays(2));

        service.sweepDeferredHandoffs();

        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.PENDING);
        verify(actionInvocationService, never())
                .invoke(any(), anyString(), any(), eq(target.getIdempotencyKey()), any());
    }

    // --- [auto] A rejected hand-off is recorded as a failed outcome ------------------------------

    @Test
    void aRejectedHandoffMovesTheRowToFailedWithThePlatformsOwnWords() {
        PostPublishTarget target = pendingNative("facebook", OffsetDateTime.now().plusDays(2));
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(ActionResult.error("(#100) The parameter scheduled_publish_time is required"));

        service.handoffForPost(target.getWorkItem());

        PostPublishTarget stored = reload(target);
        assertThat(stored.getState()).isEqualTo(PostPublishTargetState.FAILED);
        assertThat(stored.getErrorMessage())
                .isEqualTo("(#100) The parameter scheduled_publish_time is required");
        assertThat(stored.getAttempts()).isEqualTo(1);
    }

    // --- [auto] A native target confirmed live reaches PUBLISHED with its typed Asset -------------

    @Test
    void aHandedOffFacebookTargetConfirmedLiveIsPublishedAndRecordsAFacebookPostAsset() {
        PostPublishTarget target = dueHandedOff("facebook", "page_1_post_99");
        platformAnswersFor(target, Map.of(
                "is_published", true,
                "post_id", "page_1_post_99",
                "permalink", "https://facebook.com/page_1/posts/99"));

        runConfirmation(OffsetDateTime.now());

        verifyConfirmationRead(target, "get_facebook_post", 1);
        PostPublishTarget stored = reload(target);
        assertThat(stored.getState()).isEqualTo(PostPublishTargetState.PUBLISHED);
        assertThat(stored.getPermalink()).isEqualTo("https://facebook.com/page_1/posts/99");
        assertThat(assetRepository.findAllByWorkItemId(stored.getWorkItem().getId()))
                .extracting(Asset::getType, Asset::getRef)
                .containsExactly(tuple("facebook_post", "https://facebook.com/page_1/posts/99"));
    }

    @Test
    void aHandedOffYouTubeTargetGonePublicIsPublishedAndRecordsAYouTubeVideoAsset() {
        PostPublishTarget target = dueHandedOff("youtube", "vid_99");
        platformAnswersFor(target, Map.of(
                "privacy_status", "public",
                "video_id", "vid_99",
                "permalink", "https://youtu.be/vid_99"));

        runConfirmation(OffsetDateTime.now());

        verifyConfirmationRead(target, "get_video_status", 1);
        PostPublishTarget stored = reload(target);
        assertThat(stored.getState()).isEqualTo(PostPublishTargetState.PUBLISHED);
        assertThat(assetRepository.findAllByWorkItemId(stored.getWorkItem().getId()))
                .extracting(Asset::getType, Asset::getRef)
                .containsExactly(tuple("youtube_video", "https://youtu.be/vid_99"));
    }

    @Test
    void aHandedOffYouTubeTargetStillPrivateIsLeftHandedOffAndAskedAgainOnALaterTick() {
        PostPublishTarget target = dueHandedOff("youtube", "vid_99");
        platformAnswersFor(target, Map.of("privacy_status", "private", "video_id", "vid_99"));

        runConfirmation(OffsetDateTime.now());

        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.HANDED_OFF);
        verifyConfirmationRead(target, "get_video_status", 1);

        // A later tick asks again — under a key of its own, or the invocation store would replay the
        // first "still private" answer forever.
        platformAnswersFor(target, Map.of("privacy_status", "public", "video_id", "vid_99",
                "permalink", "https://youtu.be/vid_99"));
        runConfirmation(OffsetDateTime.now());

        verifyConfirmationRead(target, "get_video_status", 2);
        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.PUBLISHED);
    }

    // --- [auto] Confirmation polling is bounded and resolves into FAILED --------------------------

    @Test
    void confirmationPollingGivesUpIntoFailedOnceItsAttemptBoundIsReached() {
        confirmationPoller.maxConfirmationAttempts = 2;
        PostPublishTarget target = dueHandedOff("facebook", "page_1_post_99");
        platformAnswersFor(target, Map.of("is_published", false));

        runConfirmation(OffsetDateTime.now());
        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.HANDED_OFF);

        runConfirmation(OffsetDateTime.now());

        PostPublishTarget stored = reload(target);
        assertThat(stored.getState()).isEqualTo(PostPublishTargetState.FAILED);
        assertThat(stored.getErrorMessage())
                .contains("facebook")
                .contains("page_1_post_99")
                .contains("2 checks");

        // ...and it is genuinely done: a third tick never asks again.
        runConfirmation(OffsetDateTime.now());
        verify(actionInvocationService, never()).invoke(any(), anyString(), any(),
                eq("confirm:" + target.getIdempotencyKey() + ":3"), any());
    }

    // --- [auto] Terminal rows are never polled, and a doubled tick confirms only once -------------

    @Test
    void aRevokedOrAlreadyPublishedRowIsNeverPolled() {
        OffsetDateTime past = OffsetDateTime.now().minusMinutes(5);
        PostPublishTarget revoked = target(post(NativeHandoffService.SCHEDULED_STATUS), "facebook",
                PublishLane.NATIVE, PostPublishTargetState.REVOKED, past, "page_1_post_98");
        PostPublishTarget published = target(post(NativeHandoffService.SCHEDULED_STATUS), "youtube",
                PublishLane.NATIVE, PostPublishTargetState.PUBLISHED, past, "vid_98");

        runConfirmation(OffsetDateTime.now());

        for (PostPublishTarget t : List.of(revoked, published)) {
            verify(actionInvocationService, never()).invoke(any(), anyString(), any(),
                    org.mockito.ArgumentMatchers.startsWith("confirm:" + t.getIdempotencyKey()), any());
        }
        assertThat(reload(revoked).getState()).isEqualTo(PostPublishTargetState.REVOKED);
        assertThat(reload(published).getState()).isEqualTo(PostPublishTargetState.PUBLISHED);
    }

    @Test
    void anAppManagedHandedOffRowIsNeverPolledByThisPoller() {
        PostPublishTarget target = target(post(NativeHandoffService.SCHEDULED_STATUS), "facebook",
                PublishLane.APP_MANAGED, PostPublishTargetState.HANDED_OFF,
                OffsetDateTime.now().minusMinutes(5), "page_1_post_97");

        runConfirmation(OffsetDateTime.now());

        verify(actionInvocationService, never()).invoke(any(), anyString(), any(),
                org.mockito.ArgumentMatchers.startsWith("confirm:" + target.getIdempotencyKey()), any());
        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.HANDED_OFF);
    }

    @Test
    void aTargetWhoseFireTimeHasNotArrivedIsNeverPolled() {
        PostPublishTarget target = target(post(NativeHandoffService.SCHEDULED_STATUS), "facebook",
                PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF,
                OffsetDateTime.now().plusDays(2), "page_1_post_96");

        runConfirmation(OffsetDateTime.now());

        verify(actionInvocationService, never()).invoke(any(), anyString(), any(),
                org.mockito.ArgumentMatchers.startsWith("confirm:" + target.getIdempotencyKey()), any());
        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.HANDED_OFF);
    }

    @Test
    void aDoubledConfirmationTickConfirmsATargetExactlyOnce() {
        PostPublishTarget target = dueHandedOff("facebook", "page_1_post_99");
        platformAnswersFor(target, Map.of(
                "is_published", true,
                "permalink", "https://facebook.com/page_1/posts/99"));

        runConfirmation(OffsetDateTime.now());
        runConfirmation(OffsetDateTime.now());

        // The second tick's due query no longer sees the row at all: it is PUBLISHED.
        verifyConfirmationRead(target, "get_facebook_post", 1);
        verify(actionInvocationService, never()).invoke(any(), anyString(), any(),
                eq("confirm:" + target.getIdempotencyKey() + ":2"), any());
        assertThat(assetRepository.findAllByWorkItemId(reload(target).getWorkItem().getId())).hasSize(1);
    }

    @Test
    void theConfirmationTickIsDisabledByConfigInThisProfileSoNothingFiresOnItsOwn() {
        PostPublishTarget target = dueHandedOff("facebook", "page_1_post_99");
        platformAnswersFor(target, Map.of("is_published", true, "permalink", "https://example.com/p/99"));

        confirmationPoller.poll();

        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.HANDED_OFF);
        verify(actionInvocationService, never()).invoke(any(), anyString(), any(),
                org.mockito.ArgumentMatchers.startsWith("confirm:" + target.getIdempotencyKey()), any());
    }

    // --- [auto] Any exit from Scheduled revokes handed-off targets before the status change ------

    @Test
    void unschedulingAPostDeletesItsScheduledFacebookPostAndMovesTheRowToRevoked() {
        PostPublishTarget target = handedOff("facebook", "page_1_post_99");

        service.unschedule(target.getWorkItem());

        assertThat(capturedInput("delete_facebook_post", target.getIdempotencyKey()))
                .containsEntry("post_id", "page_1_post_99");
        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.REVOKED);
    }

    @Test
    void unschedulingAHandedOffYouTubeTargetRePrivatizesItAndClearsPublishAt() {
        PostPublishTarget target = handedOff("youtube", "vid_99");

        service.unschedule(target.getWorkItem());

        Map<String, Object> input = capturedInput("unpublish_video", target.getIdempotencyKey());
        assertThat(input).containsEntry("video_id", "vid_99");
        assertThat(input).containsEntry("privacy_status", "private");
        assertThat(input).containsKey("publish_at");
        assertThat(input.get("publish_at")).isNull();
        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.REVOKED);
    }

    @Test
    void unschedulingRevokesEveryHandedOffTargetOfThePost() {
        WorkItem owner = post(NativeHandoffService.SCHEDULED_STATUS);
        PostPublishTarget facebook = target(owner, "facebook", PublishLane.NATIVE,
                PostPublishTargetState.HANDED_OFF, OffsetDateTime.now().plusDays(2), "page_1_post_99");
        PostPublishTarget youtube = target(owner, "youtube", PublishLane.NATIVE,
                PostPublishTargetState.HANDED_OFF, OffsetDateTime.now().plusDays(2), "vid_99");

        service.unschedule(owner);

        assertThat(reload(facebook).getState()).isEqualTo(PostPublishTargetState.REVOKED);
        assertThat(reload(youtube).getState()).isEqualTo(PostPublishTargetState.REVOKED);
    }

    @Test
    void unschedulingLeavesTargetsThatWereNeverHandedOffPendingSoThePostStaysReschedulable() {
        PostPublishTarget target = pendingNative("facebook", OffsetDateTime.now().plusDays(2));

        service.unschedule(target.getWorkItem());

        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.PENDING);
        verify(actionInvocationService, never()).invoke(any(), anyString(), any(), anyString(), any());
    }

    // --- [auto] Revocation is idempotent and safe on targets never handed off --------------------

    @Test
    void revokingATargetWithNoPlatformPostIdIsANoOpAndDoesNotFail() {
        PostPublishTarget target = target(post(NativeHandoffService.SCHEDULED_STATUS), "facebook",
                PublishLane.NATIVE, PostPublishTargetState.HANDED_OFF,
                OffsetDateTime.now().plusDays(2), null);

        service.revoke(target);

        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.REVOKED);
        verify(actionInvocationService, never()).invoke(any(), anyString(), any(), anyString(), any());
    }

    @Test
    void unschedulingTheSamePostTwiceCallsThePlatformOnlyOnce() {
        PostPublishTarget target = handedOff("facebook", "page_1_post_99");

        service.unschedule(target.getWorkItem());
        service.unschedule(target.getWorkItem());

        verify(actionInvocationService)
                .invoke(any(), eq("delete_facebook_post"), any(), anyString(), any());
        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.REVOKED);
    }

    // --- [auto] A failed revocation rolls the caller's transaction back --------------------------

    @Test
    void aFailedRevocationRollsBackTheCallersStatusChangeSoNothingIsLeftHalfRevoked() {
        PostPublishTarget target = handedOff("facebook", "page_1_post_99");
        WorkItem owner = target.getWorkItem();
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(ActionResult.error("Graph API rejected the delete"));

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            WorkItem unscheduling = workItemRepository.findById(owner.getId()).orElseThrow();
            unscheduling.setCurrentStatus("APPROVED");
            workItemRepository.save(unscheduling);
            service.unschedule(unscheduling);
        })).isInstanceOf(BusinessException.class);

        assertThat(reload(owner).getCurrentStatus()).isEqualTo(NativeHandoffService.SCHEDULED_STATUS);
        assertThat(reload(target).getState()).isEqualTo(PostPublishTargetState.HANDED_OFF);
        assertThat(reload(target).getPlatformPostId()).isEqualTo("page_1_post_99");
    }

    // --- [auto] APP_MANAGED rows are untouched by every path here --------------------------------

    @Test
    void anAppManagedRowIsNeverHandedOffOrRevokedByThisService() {
        OffsetDateTime now = OffsetDateTime.now();
        WorkItem owner = post(NativeHandoffService.SCHEDULED_STATUS);
        PostPublishTarget pending = target(owner, "facebook", PublishLane.APP_MANAGED,
                PostPublishTargetState.PENDING, now.plusDays(2), null);
        PostPublishTarget live = target(owner, "youtube", PublishLane.APP_MANAGED,
                PostPublishTargetState.HANDED_OFF, now.plusDays(2), "vid_99");

        service.handoffForPost(owner);
        runSweep(now);
        service.unschedule(owner);

        assertThat(reload(pending).getState()).isEqualTo(PostPublishTargetState.PENDING);
        assertThat(reload(live).getState()).isEqualTo(PostPublishTargetState.HANDED_OFF);
        for (PostPublishTarget t : List.of(pending, live)) {
            verify(actionInvocationService, never())
                    .invoke(any(), anyString(), any(), eq(t.getIdempotencyKey()), any());
        }
    }
}
