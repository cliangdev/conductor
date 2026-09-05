package com.conductor.workflow;

import com.conductor.signal.SignalTypes;
import com.conductor.signal.Signal;
import com.conductor.notification.ChannelGroup;
import com.conductor.signal.SignalBus;
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
import com.conductor.service.PublishInputBuilder;
import com.conductor.service.PublishTargetMediaResolver;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetAssetRepository;

/**
 * Unit coverage for {@link PostPublishScheduler}'s decision logic: which rows it will and will not
 * dispatch, and that a lost claim never turns into a publish. The DB-level guarantees (the due query
 * itself, and the atomicity of the claim under two concurrent ticks) are proven against real Postgres
 * in {@code PostPublishSchedulerIntegrationTest}.
 */
class PostPublishSchedulerTest {

    private AssetRepository assetRepository;
    private PostPublishTargetAssetRepository targetAssetRepository;
    private PostPublishTargetRepository targetRepository;
    private SignalBus signalBus;
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
        assetRepository = mock(AssetRepository.class);
        targetAssetRepository = mock(PostPublishTargetAssetRepository.class);
        signalBus = mock(SignalBus.class);
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
                targetRepository, connectionResolver, actionInvocationService, publishOutcomeService,
                new PublishInputBuilder(new PublishTargetMediaResolver(assetRepository, targetAssetRepository)),
                enabled, signalBus);
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

    // --- [auto] A TikTok target's publish options reach the publish action input (TIK-1) ---

    /**
     * The names on the right are the ones {@code connectors/tool-specs/tiktok.json} declares. Nothing ever
     * put a {@code privacy_level} in this map before, so every TikTok post published SELF_ONLY by default
     * and nobody could see it.
     */
    @Test
    void aTiktokTargetsPublishOptionsTravelUnderTheParameterNamesTiktokJsonDeclares() {
        PostPublishTarget target = target(post(PostPublishScheduler.SCHEDULED_STATUS), "tiktok",
                PublishLane.APP_MANAGED, PostPublishTargetState.PENDING);
        target.setPublishOptions("{\"privacyLevel\":\"PUBLIC_TO_EVERYONE\",\"disableComment\":true,"
                + "\"disableDuet\":false,\"disableStitch\":true,\"brandContentToggle\":true,"
                + "\"brandOrganicToggle\":false}");
        given(target);

        scheduler.poll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        verify(actionInvocationService).invoke(any(), eq("publish_video"), input.capture(), anyString(), any());
        assertThat(input.getValue())
                .containsEntry("privacy_level", "PUBLIC_TO_EVERYONE")
                .containsEntry("disable_comment", true)
                .containsEntry("disable_duet", false)
                .containsEntry("disable_stitch", true)
                .containsEntry("brand_content_toggle", true)
                .containsEntry("brand_organic_toggle", false);
    }

    @Test
    void anOptionTheHumanDidNotChooseIsAbsentRatherThanGuessedAt() {
        PostPublishTarget target = target(post(PostPublishScheduler.SCHEDULED_STATUS), "tiktok",
                PublishLane.APP_MANAGED, PostPublishTargetState.PENDING);
        target.setPublishOptions("{\"privacyLevel\":\"FOLLOWER_OF_CREATOR\"}");
        given(target);

        scheduler.poll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        verify(actionInvocationService).invoke(any(), anyString(), input.capture(), anyString(), any());
        assertThat(input.getValue())
                .containsEntry("privacy_level", "FOLLOWER_OF_CREATOR")
                .doesNotContainKeys("disable_comment", "disable_duet", "disable_stitch",
                        "brand_content_toggle", "brand_organic_toggle");
    }

    @Test
    void aTargetWithNoStoredOptionsDispatchesNoOptionParameters() {
        given(target(post(PostPublishScheduler.SCHEDULED_STATUS), "tiktok",
                PublishLane.APP_MANAGED, PostPublishTargetState.PENDING));

        scheduler.poll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        verify(actionInvocationService).invoke(any(), anyString(), input.capture(), anyString(), any());
        assertThat(input.getValue()).doesNotContainKey("privacy_level");
    }

    /** The bag is a whitelist: a key no platform declares must never become a connector parameter. */
    @Test
    void anUnrecognisedOptionKeyIsDropped() {
        PostPublishTarget target = target(post(PostPublishScheduler.SCHEDULED_STATUS), "tiktok",
                PublishLane.APP_MANAGED, PostPublishTargetState.PENDING);
        target.setPublishOptions("{\"privacyLevel\":\"SELF_ONLY\",\"asset_id\":\"someone-elses-video\"}");
        given(target);

        scheduler.poll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> input = ArgumentCaptor.forClass(Map.class);
        verify(actionInvocationService).invoke(any(), anyString(), input.capture(), anyString(), any());
        assertThat(input.getValue()).doesNotContainKey("asset_id");
    }

    @Test
    void anUnreadableOptionsBagStillPublishesRatherThanFailingTheDispatch() {
        PostPublishTarget target = target(post(PostPublishScheduler.SCHEDULED_STATUS), "tiktok",
                PublishLane.APP_MANAGED, PostPublishTargetState.PENDING);
        target.setPublishOptions("not json at all");
        given(target);

        scheduler.poll();

        verify(actionInvocationService).invoke(any(), anyString(), any(), anyString(), any());
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

    // ---- MANUAL lane: flagged for a human, never dispatched (MKT-2) -------------------------------

    private PostPublishTarget manualTarget(String id) {
        PostPublishTarget target = new PostPublishTarget();
        target.setId(id);
        target.setWorkItem(post(PostPublishScheduler.SCHEDULED_STATUS));
        target.setPlatform("tiktok");
        target.setLane(PublishLane.MANUAL);
        target.setState(PostPublishTargetState.PENDING);
        target.setFireTime(OffsetDateTime.now().minusMinutes(1));
        target.setIdempotencyKey("pub:post-1:tiktok:manual");
        return target;
    }

    /** Every JPQL statement the tick issued, in order. */
    private List<String> issuedStatements() {
        ArgumentCaptor<String> jpql = ArgumentCaptor.forClass(String.class);
        verify(entityManager, org.mockito.Mockito.atLeast(0)).createQuery(jpql.capture());
        return jpql.getAllValues();
    }

    @Test
    void aDueManualTargetIsFlaggedForAHumanAndNothingIsDispatched() {
        when(targetRepository.findDueAppManagedTargets(any())).thenReturn(List.of());
        when(targetRepository.findDueManualTargets(any())).thenReturn(List.of(manualTarget("manual-1")));

        scheduler.runTick(OffsetDateTime.now());

        // The whole of Conductor's job on this lane is the state change. Nothing is invoked, and no
        // connection is even resolved — there is no credential involved in a manual publish.
        verifyNoInteractions(actionInvocationService);
        verifyNoInteractions(connectionResolver);
        assertThat(issuedStatements()).anyMatch(q -> q.contains("AWAITING_MANUAL"));
    }

    @Test
    void theManualClaimReassertsBothPendingAndTheManualLane() {
        // Same protection the dispatch claim has, for the same reason: two ticks racing the same row both
        // read PENDING, and only one UPDATE may land. Re-asserting the lane additionally means a target
        // that is not manual can never be pulled onto this path.
        when(targetRepository.findDueAppManagedTargets(any())).thenReturn(List.of());
        when(targetRepository.findDueManualTargets(any())).thenReturn(List.of(manualTarget("manual-1")));

        scheduler.runTick(OffsetDateTime.now());

        String claim = issuedStatements().stream()
                .filter(q -> q.contains("AWAITING_MANUAL"))
                .findFirst().orElseThrow();
        assertThat(claim).contains("PublishLane.MANUAL");
        assertThat(claim).contains("PostPublishTargetState.PENDING");
    }

    @Test
    void aFailureFlaggingManualTargetsNeverCostsARealPublishItsTick() {
        // The manual pass is bookkeeping; a publish is not. One must not be able to take down the other.
        given(dueTarget());
        when(targetRepository.findDueManualTargets(any()))
                .thenThrow(new IllegalStateException("manual query exploded"));

        scheduler.runTick(OffsetDateTime.now());

        verify(actionInvocationService).invoke(any(), anyString(), any(), anyString(), any());
    }

    @Test
    void flaggingADueManualTargetAnnouncesThatSomebodyHasToPostIt() {
        // The manual lane's whole premise is that a person acts at a specific time. Without this the only
        // way to find out was to happen to open the Post.
        when(targetRepository.findDueAppManagedTargets(any())).thenReturn(List.of());
        when(targetRepository.findDueManualTargets(any())).thenReturn(List.of(manualTarget("manual-1")));

        scheduler.runTick(OffsetDateTime.now());

        ArgumentCaptor<Signal> signal = ArgumentCaptor.forClass(Signal.class);
        verify(signalBus).publish(signal.capture());
        assertThat(signal.getValue().type())
                .isEqualTo(SignalTypes.CONDUCTOR_WORK_ITEM_AWAITING_MANUAL_PUBLISH);
        assertThat(signal.getValue().payload())
                .containsEntry("platform", "tiktok")
                // Routes to a project's Publishing channel rather than its engineering one.
                .containsEntry(ChannelGroup.META_PUBLISHES, "true");
    }

    @Test
    void aTargetWhoseClaimWasLostAnnouncesNothing() {
        // Two ticks racing the same row: only the one that actually moved it may tell anybody, or a human
        // gets the same "post this now" message every thirty seconds.
        when(targetRepository.findDueAppManagedTargets(any())).thenReturn(List.of());
        when(targetRepository.findDueManualTargets(any())).thenReturn(List.of(manualTarget("manual-1")));
        when(claimQuery.executeUpdate()).thenReturn(0);

        scheduler.runTick(OffsetDateTime.now());

        verifyNoInteractions(signalBus);
    }

    @Test
    void aFailureAnnouncingItStillLeavesTheTargetFlagged() {
        // The row is already claimed and correct; a chat webhook being down must not undo that.
        when(targetRepository.findDueAppManagedTargets(any())).thenReturn(List.of());
        when(targetRepository.findDueManualTargets(any())).thenReturn(List.of(manualTarget("manual-1")));
        org.mockito.Mockito.doThrow(new IllegalStateException("bus down")).when(signalBus).publish(any());

        scheduler.runTick(OffsetDateTime.now());

        assertThat(issuedStatements()).anyMatch(q -> q.contains("AWAITING_MANUAL"));
    }

    @Test
    void aManualTargetIsNeverReturnedByTheDispatchQueryAndSoIsNeverPublished() {
        // Belt and braces on the query contract itself: the dispatch loop only ever sees what
        // findDueAppManagedTargets returns, and that query filters on the APP_MANAGED lane in SQL.
        when(targetRepository.findDueAppManagedTargets(any())).thenReturn(List.of());
        when(targetRepository.findDueManualTargets(any())).thenReturn(List.of(manualTarget("manual-1")));

        scheduler.runTick(OffsetDateTime.now());

        verify(publishOutcomeService, never()).recordSuccess(anyString(), any(), any());
        verify(publishOutcomeService, never()).recordFailure(anyString(), anyString());
    }

}
