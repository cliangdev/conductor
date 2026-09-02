package com.conductor.service;

import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetState;
import com.conductor.entity.Project;
import com.conductor.entity.PublishLane;
import com.conductor.entity.WorkItem;
import com.conductor.integration.ActionResult;
import com.conductor.repository.PostPublishTargetRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link NativePublishConfirmationPoller}'s decision logic: which handed-off rows it
 * asks a platform about, how it reads the platform's answer, and that it gives up into FAILED rather
 * than polling forever. The DB-level guarantees (the due query, that a confirmed target really lands as
 * PUBLISHED with its typed Asset, and that a doubled tick confirms a row only once) are proven against
 * real Postgres in {@code NativeHandoffIntegrationTest}, which owns this lane's DB-backed coverage.
 *
 * <p>{@link ActionInvocationService} is the seam and is stubbed: {@code get_facebook_post} and
 * {@code get_video_status} are the connectors' own actions, and this poller only knows their ids.
 */
class NativePublishConfirmationPollerTest {

    private PostPublishTargetRepository targetRepository;
    private ActiveConnectionResolver connectionResolver;
    private ActionInvocationService actionInvocationService;
    private PublishOutcomeService publishOutcomeService;
    private EntityManager entityManager;
    private TypedQuery<String> dueQuery;
    private Query attemptClaim;

    private NativePublishConfirmationPoller poller;

    private Project project;
    private Connection connection;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        targetRepository = mock(PostPublishTargetRepository.class);
        connectionResolver = mock(ActiveConnectionResolver.class);
        actionInvocationService = mock(ActionInvocationService.class);
        publishOutcomeService = mock(PublishOutcomeService.class);
        entityManager = mock(EntityManager.class);
        dueQuery = mock(TypedQuery.class, RETURNS_SELF);
        attemptClaim = mock(Query.class, RETURNS_SELF);

        when(entityManager.createQuery(anyString(), eq(String.class))).thenReturn(dueQuery);
        when(entityManager.createQuery(anyString())).thenReturn(attemptClaim);
        when(dueQuery.getResultList()).thenReturn(List.of());
        when(attemptClaim.executeUpdate()).thenReturn(1);

        project = new Project();
        project.setId("project-1");

        connection = new Connection();
        connection.setId("connection-1");
        connection.setProjectId(project.getId());
        connection.setConnectorId("meta");
        connection.setStatus("ACTIVE");

        when(connectionResolver.resolveById(eq("project-1"), eq("connection-1")))
                .thenReturn(Optional.of(connection));

        poller = newPoller(true);
    }

    private NativePublishConfirmationPoller newPoller(boolean enabled) {
        NativePublishConfirmationPoller p = new NativePublishConfirmationPoller(
                targetRepository, connectionResolver, actionInvocationService, publishOutcomeService, enabled);
        p.entityManager = entityManager;
        p.self = p;
        return p;
    }

    // --- fixtures -------------------------------------------------------------------------------

    private WorkItem post() {
        WorkItem item = new WorkItem();
        item.setId("post-1");
        item.setProject(project);
        item.setTitle("Launch day teaser");
        item.setDescription("Doors open at nine.");
        item.setCurrentStatus(NativeHandoffService.SCHEDULED_STATUS);
        return item;
    }

    private PostPublishTarget handedOff(String platform, String platformPostId) {
        PostPublishTarget target = new PostPublishTarget();
        target.setId("target-1");
        target.setWorkItem(post());
        target.setConnectorId("meta");
        target.setConnectionId("connection-1");
        target.setPlatform(platform);
        target.setLane(PublishLane.NATIVE);
        target.setState(PostPublishTargetState.HANDED_OFF);
        target.setFireTime(OffsetDateTime.now().minusMinutes(1));
        target.setPlatformPostId(platformPostId);
        target.setIdempotencyKey("pub:post-1:" + platform + ":connection-1");
        return target;
    }

    private void given(PostPublishTarget target) {
        when(dueQuery.getResultList()).thenReturn(List.of(target.getId()));
        when(targetRepository.findById(target.getId())).thenReturn(Optional.of(target));
    }

    private void platformAnswers(Map<String, Object> output) {
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(ActionResult.ok(output));
    }

    private Map<String, Object> capturedInput(String actionId) {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        verify(actionInvocationService).invoke(any(), eq(actionId), input.capture(), anyString(), any());
        return input.getValue();
    }

    // --- [auto] A native target confirmed live reaches PUBLISHED with its permalink --------------

    @Test
    void aHandedOffFacebookTargetIsAskedAboutThroughGetFacebookPostOnItsOwnConnection() {
        PostPublishTarget target = handedOff("facebook", "page_1_post_99");
        given(target);
        platformAnswers(Map.of("is_published", false));

        poller.runTick(OffsetDateTime.now());

        verify(actionInvocationService).invoke(eq(connection), eq("get_facebook_post"), any(),
                anyString(), any());
        assertThat(capturedInput("get_facebook_post")).containsEntry("post_id", "page_1_post_99");
    }

    @Test
    void aFacebookPostThePlatformReportsAsPublishedIsRecordedAsPublishedWithItsPermalink() {
        given(handedOff("facebook", "page_1_post_99"));
        platformAnswers(Map.of(
                "is_published", true,
                "post_id", "page_1_post_99",
                "permalink", "https://facebook.com/page_1/posts/99"));

        poller.runTick(OffsetDateTime.now());

        verify(publishOutcomeService).recordSuccess("target-1", "page_1_post_99",
                "https://facebook.com/page_1/posts/99");
    }

    @Test
    void aYouTubeVideoThePlatformReportsAsPublicIsRecordedAsPublished() {
        PostPublishTarget target = handedOff("youtube", "vid_99");
        given(target);
        platformAnswers(Map.of(
                "privacy_status", "public",
                "video_id", "vid_99",
                "permalink", "https://youtu.be/vid_99"));

        poller.runTick(OffsetDateTime.now());

        assertThat(capturedInput("get_video_status")).containsEntry("video_id", "vid_99");
        verify(publishOutcomeService).recordSuccess("target-1", "vid_99", "https://youtu.be/vid_99");
    }

    @Test
    void aPlatformThatReportsNoPostIdOfItsOwnStillPublishesUnderTheStoredOne() {
        given(handedOff("facebook", "page_1_post_99"));
        platformAnswers(Map.of("is_published", true, "permalink", "https://facebook.com/page_1/posts/99"));

        poller.runTick(OffsetDateTime.now());

        verify(publishOutcomeService).recordSuccess("target-1", "page_1_post_99",
                "https://facebook.com/page_1/posts/99");
    }

    // --- [auto] A target the platform has not published yet stays HANDED_OFF ---------------------

    @Test
    void aYouTubeVideoStillPrivateIsLeftHandedOffForALaterTick() {
        given(handedOff("youtube", "vid_99"));
        platformAnswers(Map.of("privacy_status", "private", "video_id", "vid_99"));

        poller.runTick(OffsetDateTime.now());

        verifyNoInteractions(publishOutcomeService);
    }

    @Test
    void aFacebookPostStillScheduledIsLeftHandedOffForALaterTick() {
        given(handedOff("facebook", "page_1_post_99"));
        platformAnswers(Map.of("is_published", false));

        poller.runTick(OffsetDateTime.now());

        verifyNoInteractions(publishOutcomeService);
    }

    @Test
    void aReadThatFailsOutrightIsLeftHandedOffForALaterTick() {
        given(handedOff("facebook", "page_1_post_99"));
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(ActionResult.error("Graph API is rate limiting us"));

        poller.runTick(OffsetDateTime.now());

        verifyNoInteractions(publishOutcomeService);
    }

    @Test
    void anAnswerWithNoRecognisableLivenessSignalIsNeverTreatedAsPublished() {
        given(handedOff("facebook", "page_1_post_99"));
        platformAnswers(Map.of("something_else", "entirely"));

        poller.runTick(OffsetDateTime.now());

        verifyNoInteractions(publishOutcomeService);
    }

    // --- [auto] Confirmation polling is bounded and resolves into FAILED -------------------------

    @Test
    void pollingGivesUpIntoFailedOnceTheAttemptBoundIsReached() {
        PostPublishTarget target = handedOff("facebook", "page_1_post_99");
        target.setAttempts(poller.maxConfirmationAttempts - 1);
        given(target);
        platformAnswers(Map.of("is_published", false));

        poller.runTick(OffsetDateTime.now());

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(publishOutcomeService).recordFailure(eq("target-1"), message.capture());
        assertThat(message.getValue())
                .contains("facebook")
                .contains(String.valueOf(poller.maxConfirmationAttempts))
                .contains("page_1_post_99");
    }

    @Test
    void aTargetShortOfTheAttemptBoundIsRetriedRatherThanFailed() {
        PostPublishTarget target = handedOff("facebook", "page_1_post_99");
        target.setAttempts(poller.maxConfirmationAttempts - 2);
        given(target);
        platformAnswers(Map.of("is_published", false));

        poller.runTick(OffsetDateTime.now());

        verify(publishOutcomeService, never()).recordFailure(anyString(), anyString());
    }

    @Test
    void aReadThatKeepsFailingAlsoGivesUpIntoFailedRatherThanPollingForever() {
        PostPublishTarget target = handedOff("youtube", "vid_99");
        target.setAttempts(poller.maxConfirmationAttempts - 1);
        given(target);
        when(actionInvocationService.invoke(any(), anyString(), any(), anyString(), any()))
                .thenReturn(ActionResult.error("Quota exceeded"));

        poller.runTick(OffsetDateTime.now());

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(publishOutcomeService).recordFailure(eq("target-1"), message.capture());
        assertThat(message.getValue()).contains("Quota exceeded");
    }

    @Test
    void eachAttemptUsesAnIdempotencyKeyOfItsOwnSoTheReadIsNeverServedFromAStaleInvocation() {
        PostPublishTarget target = handedOff("facebook", "page_1_post_99");
        target.setAttempts(3);
        given(target);
        platformAnswers(Map.of("is_published", false));

        poller.runTick(OffsetDateTime.now());

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(actionInvocationService).invoke(any(), anyString(), any(), key.capture(), any());
        assertThat(key.getValue())
                .isNotEqualTo(target.getIdempotencyKey())
                .contains(target.getIdempotencyKey())
                .endsWith(":4");
    }

    // --- [auto] Terminal and foreign rows are never polled ---------------------------------------

    @ParameterizedTest
    @EnumSource(value = PostPublishTargetState.class,
            names = {"PENDING", "PUBLISHING", "PUBLISHED", "REVOKED", "FAILED"})
    void aRowThatIsNoLongerHandedOffIsNeverPolled(PostPublishTargetState state) {
        PostPublishTarget target = handedOff("facebook", "page_1_post_99");
        target.setState(state);
        given(target);

        poller.runTick(OffsetDateTime.now());

        verifyNoInteractions(actionInvocationService);
        verifyNoInteractions(publishOutcomeService);
        verify(attemptClaim, never()).executeUpdate();
    }

    @Test
    void anAppManagedRowIsNeverPolledEvenWhenHandedToThisPoller() {
        PostPublishTarget target = handedOff("facebook", "page_1_post_99");
        target.setLane(PublishLane.APP_MANAGED);
        given(target);

        poller.runTick(OffsetDateTime.now());

        verifyNoInteractions(actionInvocationService);
        verify(attemptClaim, never()).executeUpdate();
    }

    @Test
    void aTargetWhoseFireTimeHasNotArrivedIsNeverPolled() {
        PostPublishTarget target = handedOff("facebook", "page_1_post_99");
        target.setFireTime(OffsetDateTime.now().plusHours(2));
        given(target);

        poller.runTick(OffsetDateTime.now());

        verifyNoInteractions(actionInvocationService);
        verify(attemptClaim, never()).executeUpdate();
    }

    @Test
    void aTargetWithNoPlatformPostIdCannotBeAskedAboutAndIsSkipped() {
        given(handedOff("facebook", null));

        poller.runTick(OffsetDateTime.now());

        verifyNoInteractions(actionInvocationService);
        verify(attemptClaim, never()).executeUpdate();
    }

    @Test
    void aPlatformWithNoConfirmationActionIsSkippedRatherThanGuessedAt() {
        given(handedOff("instagram", "media_99"));

        poller.runTick(OffsetDateTime.now());

        verifyNoInteractions(actionInvocationService);
        verify(attemptClaim, never()).executeUpdate();
    }

    @Test
    void anUnresolvableConnectionIsSkippedWithoutBurningAnAttempt() {
        given(handedOff("facebook", "page_1_post_99"));
        when(connectionResolver.resolveById(anyString(), anyString())).thenReturn(Optional.empty());

        poller.runTick(OffsetDateTime.now());

        verifyNoInteractions(actionInvocationService);
        verify(attemptClaim, never()).executeUpdate();
    }

    @Test
    void losingTheAttemptClaimRaceToAConcurrentTickNeverAsksThePlatform() {
        given(handedOff("facebook", "page_1_post_99"));
        when(attemptClaim.executeUpdate()).thenReturn(0);

        poller.runTick(OffsetDateTime.now());

        verify(attemptClaim).executeUpdate();
        verifyNoInteractions(actionInvocationService);
        verifyNoInteractions(publishOutcomeService);
    }

    @Test
    void oneTargetsFailureNeverBlocksTheRestOfTheTick() {
        PostPublishTarget healthy = handedOff("facebook", "page_1_post_99");
        healthy.setId("target-ok");
        when(dueQuery.getResultList()).thenReturn(List.of("target-boom", "target-ok"));
        when(targetRepository.findById("target-boom")).thenThrow(new IllegalStateException("boom"));
        when(targetRepository.findById("target-ok")).thenReturn(Optional.of(healthy));
        platformAnswers(Map.of("is_published", true, "permalink", "https://facebook.com/page_1/posts/99"));

        poller.runTick(OffsetDateTime.now());

        verify(publishOutcomeService).recordSuccess(eq("target-ok"), anyString(), anyString());
    }

    // --- [auto] The poller can be disabled by config for tests and the local profile -------------

    @Test
    void aDisabledPollerNeverEvenQueriesForHandedOffTargets() {
        poller = newPoller(false);
        given(handedOff("facebook", "page_1_post_99"));

        poller.poll();

        verifyNoInteractions(entityManager);
        verifyNoInteractions(actionInvocationService);
        verifyNoInteractions(publishOutcomeService);
    }

    @Test
    void theTickIsFrequentEnoughToConfirmAPostSoonAfterItGoesLive() throws Exception {
        Method poll = NativePublishConfirmationPoller.class.getMethod("poll");
        Scheduled scheduled = poll.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelay()).isEqualTo(60_000L);
    }

    @Test
    void theDueQuerySelectsOnlyHandedOffNativeRowsWhoseFireTimeHasPassed() {
        poller.runTick(OffsetDateTime.now());

        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        verify(entityManager).createQuery(jpql.capture(), eq(String.class));
        assertThat(jpql.getValue())
                .contains("state = com.conductor.entity.PostPublishTargetState.HANDED_OFF")
                .contains("lane = com.conductor.entity.PublishLane.NATIVE")
                .contains("fireTime <= :now");
    }

    @Test
    void theAttemptClaimIsConditionalOnTheRowStillBeingHandedOffAtItsCurrentAttemptCount() {
        given(handedOff("facebook", "page_1_post_99"));
        platformAnswers(Map.of("is_published", false));

        poller.runTick(OffsetDateTime.now());

        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        verify(entityManager, times(1)).createQuery(jpql.capture());
        assertThat(jpql.getValue())
                .contains("attempts = t.attempts + 1")
                .contains("state = com.conductor.entity.PostPublishTargetState.HANDED_OFF")
                .contains("t.attempts = :attempts");
    }
}
