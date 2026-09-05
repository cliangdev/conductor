package com.conductor.service;

import com.conductor.service.publish.PublishFinding;
import com.conductor.entity.PublishLane;
import com.conductor.service.publish.PublishPlatformRegistry;
import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetAssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.workflow.lifecycle.Statechart;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.InputStream;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the COND-23 approval gate (T4.3) against the REAL seeded statecharts: a Post may only reach the
 * MARKETING approval edge with a fire time, a timezone, at least one publish target, at least one uploaded
 * media file, and the fire time at least ten minutes out — while ENGINEERING's own review-gated edge, which
 * declares no publish platforms, is untouched.
 */
class PostScheduleValidatorTest {

    private static final String WORK_ITEM_ID = "post-1";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-30T12:00:00Z");

    private AssetRepository assetRepository;
    private PostPublishTargetRepository postPublishTargetRepository;
    private PostPublishTargetAssetRepository targetAssetRepository;
    private PostScheduleValidator validator;

    private Statechart marketing;
    private Statechart engineering;

    @BeforeEach
    void setUp() {
        assetRepository = Mockito.mock(AssetRepository.class);
        postPublishTargetRepository = Mockito.mock(PostPublishTargetRepository.class);
        targetAssetRepository = Mockito.mock(PostPublishTargetAssetRepository.class);
        validator = new PostScheduleValidator(new PublishPlatformRegistry(), assetRepository, postPublishTargetRepository,
                new PublishTargetMediaResolver(assetRepository, targetAssetRepository),
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
        marketing = statechart("/schema/examples/marketing.workflow.json");
        engineering = statechart("/schema/examples/engineering.workflow.json");
    }

    // --- blocked approvals name the specific missing field ---

    @Test
    void blocksApprovalWhenFireTimeIsMissing() {
        WorkItem post = postInReview();
        post.setScheduledFor(null);
        givenTargets(1);
        givenUploadedMedia();

        assertThatThrownBy(() -> approve(post))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("no fire time is set")
                .hasMessageContaining("scheduledFor");
    }

    @Test
    void blocksApprovalWhenScheduleTimezoneIsMissing() {
        WorkItem post = postInReview();
        post.setScheduleTimezone(null);
        givenTargets(1);
        givenUploadedMedia();

        assertThatThrownBy(() -> approve(post))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("no schedule timezone is set")
                .hasMessageContaining("scheduleTimezone");
    }

    @Test
    void blocksApprovalWhenScheduleTimezoneIsBlank() {
        WorkItem post = postInReview();
        post.setScheduleTimezone("   ");
        givenTargets(1);
        givenUploadedMedia();

        assertThatThrownBy(() -> approve(post))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("no schedule timezone is set");
    }

    @Test
    void blocksApprovalWhenScheduleTimezoneIsNotAnIanaZone() {
        WorkItem post = postInReview();
        post.setScheduleTimezone("Mars/Olympus_Mons");
        givenTargets(1);
        givenUploadedMedia();

        assertThatThrownBy(() -> approve(post))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Mars/Olympus_Mons")
                .hasMessageContaining("not a known IANA timezone");
    }

    @Test
    void blocksApprovalWhenNoPublishTargetIsSelected() {
        WorkItem post = postInReview();
        givenNoTargets();
        givenUploadedMedia();

        assertThatThrownBy(() -> approve(post))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("no publish target is selected");
    }

    @Test
    void blocksApprovalWhenNoMediaIsUploaded() {
        WorkItem post = postInReview();
        givenTargets(1);
        givenAssets();

        assertThatThrownBy(() -> approve(post))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("no uploaded media file is attached");
    }

    @Test
    void blocksApprovalWhenTheOnlyMediaUploadIsStillPending() {
        WorkItem post = postInReview();
        givenTargets(1);
        givenAssets(asset("file", "PENDING"));

        assertThatThrownBy(() -> approve(post))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("no uploaded media file is attached");
    }

    @Test
    void blocksApprovalWhenOnlyLinkAssetsAreAttached() {
        WorkItem post = postInReview();
        givenTargets(1);
        givenAssets(asset("link", null));

        assertThatThrownBy(() -> approve(post))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("no uploaded media file is attached");
    }

    @Test
    void reportsEveryProblemTogetherSoOnePassFixesThemAll() {
        WorkItem post = postInReview();
        post.setScheduledFor(null);
        post.setScheduleTimezone(null);
        givenNoTargets();
        givenAssets();

        assertThatThrownBy(() -> approve(post))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("no fire time is set")
                .hasMessageContaining("no schedule timezone is set")
                .hasMessageContaining("no publish target is selected")
                .hasMessageContaining("no uploaded media file is attached");
    }

    @Test
    void namesTheWorkflowNounAndTargetStatusInTheRejection() {
        WorkItem post = postInReview();
        givenNoTargets();
        givenUploadedMedia();

        assertThatThrownBy(() -> approve(post))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Post")
                .hasMessageContaining("APPROVED");
    }

    // --- the ten-minute floor ---

    @Test
    void rejectsAFireTimeFiveMinutesOut() {
        WorkItem post = postInReview();
        post.setScheduledFor(NOW.plusMinutes(5));
        givenTargets(1);
        givenUploadedMedia();

        assertThatThrownBy(() -> approve(post))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("less than 10 minutes in the future");
    }

    @Test
    void rejectsAFireTimeInThePast() {
        WorkItem post = postInReview();
        post.setScheduledFor(NOW.minusHours(2));
        givenTargets(1);
        givenUploadedMedia();

        assertThatThrownBy(() -> approve(post))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("less than 10 minutes in the future");
    }

    @Test
    void acceptsAFireTimeFifteenMinutesOut() {
        WorkItem post = postInReview();
        post.setScheduledFor(NOW.plusMinutes(15));
        givenTargets(1);
        givenUploadedMedia();

        assertThatCode(() -> approve(post)).doesNotThrowAnyException();
    }

    @Test
    void acceptsAFireTimeExactlyAtTheTenMinuteFloor() {
        WorkItem post = postInReview();
        post.setScheduledFor(NOW.plusMinutes(10));
        givenTargets(1);
        givenUploadedMedia();

        assertThatCode(() -> approve(post)).doesNotThrowAnyException();
    }

    // --- the floor is the longest lead any selected destination needs ---

    @Test
    void theFloorIsTheLongestLeadAmongTheSelectedDestinations() {
        WorkItem post = postInReview();
        post.setScheduledFor(NOW.plusMinutes(9));
        givenUploadedMedia();
        when(postPublishTargetRepository.findAllByWorkItemId(WORK_ITEM_ID))
                .thenReturn(List.of(target("youtube"), target("tiktok")));

        // YouTube needs no notice and TikTok a minute, so nine minutes is plenty.
        assertThatCode(() -> approve(post)).doesNotThrowAnyException();

        PostPublishTarget facebook = target("facebook");
        facebook.setPlatformAccountLabel("Acme Page");
        when(postPublishTargetRepository.findAllByWorkItemId(WORK_ITEM_ID))
                .thenReturn(List.of(target("tiktok"), facebook));

        // Add a Facebook Page and its native scheduler's ten minutes become the Post's floor, by name.
        assertThatThrownBy(() -> approve(post))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("less than 10 minutes in the future")
                .hasMessageContaining("facebook (Acme Page) needs at least 10 minutes' notice");
    }

    @Test
    void anAppManagedDestinationNeedsOnlyTheNextPollerTick() {
        WorkItem post = postInReview();
        givenUploadedMedia();
        when(postPublishTargetRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of(target("instagram")));

        post.setScheduledFor(NOW.plusSeconds(30));
        assertThatThrownBy(() -> approve(post))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("less than 1 minute in the future");

        post.setScheduledFor(NOW.plusMinutes(2));
        assertThatCode(() -> approve(post)).doesNotThrowAnyException();
    }

    @Test
    void aManualDestinationNeedsOnlyAFutureFireTime() {
        WorkItem post = postInReview();
        givenUploadedMedia();
        PostPublishTarget manual = target("facebook");
        manual.setLane(PublishLane.MANUAL);
        when(postPublishTargetRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of(manual));

        post.setScheduledFor(NOW.plusSeconds(5));
        assertThatCode(() -> approve(post)).doesNotThrowAnyException();

        post.setScheduledFor(NOW.minusSeconds(5));
        assertThatThrownBy(() -> approve(post))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("is not in the future");
    }

    @Test
    void anUnknownPlatformKeepsTheDefaultFloor() {
        WorkItem post = postInReview();
        post.setScheduledFor(NOW.plusMinutes(9));
        givenUploadedMedia();
        when(postPublishTargetRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of(target("mastodon")));

        assertThatThrownBy(() -> approve(post))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("less than 10 minutes in the future");
    }

    @Test
    void earliestFireTimeIsNowPlusTheFloor() {
        assertThat(validator.earliestFireTime(List.of())).isEqualTo(NOW.plusMinutes(10).plusSeconds(1));
        assertThat(validator.earliestFireTime(List.of(target("instagram")))).isEqualTo(NOW.plusMinutes(1).plusSeconds(1));
        assertThat(validator.earliestFireTime(List.of(target("youtube")))).isEqualTo(NOW.plusSeconds(1));
        assertThat(validator.earliestFireTime(List.of(target("instagram"), target("facebook"))))
                .isEqualTo(NOW.plusMinutes(10).plusSeconds(1));
    }

    // --- the happy path ---

    @Test
    void allowsAFullySpecifiedPostToBeApproved() {
        WorkItem post = postInReview();
        givenTargets(2);
        givenUploadedMedia();

        assertThatCode(() -> approve(post)).doesNotThrowAnyException();
        assertThat(post.getCurrentStatus()).isEqualTo("IN_REVIEW");
    }

    // --- nothing is written, ever ---

    @Test
    void persistsNothingWhenValidationFails() {
        WorkItem post = postInReview();
        post.setScheduledFor(null);
        givenNoTargets();
        givenAssets();

        assertThatThrownBy(() -> approve(post)).isInstanceOf(UnprocessableEntityException.class);

        assertThat(post.getCurrentStatus()).isEqualTo("IN_REVIEW");
        verify(assetRepository, never()).save(any());
        verify(postPublishTargetRepository, never()).save(any());
        verify(postPublishTargetRepository, never()).saveAll(any());
    }

    // --- scope: only the publishing workflow's own approval gate ---

    @Test
    void leavesTheEngineeringReviewGatedTransitionCompletelyUnaffected() {
        WorkItem issue = workItem("ENGINEERING", "CODE_REVIEW");

        assertThatCode(() -> validator.validateForTransition(issue, engineering, "DONE"))
                .doesNotThrowAnyException();
        verifyNoInteractions(assetRepository, postPublishTargetRepository);
    }

    @Test
    void leavesEveryOtherEngineeringTransitionUnaffected() {
        assertThatCode(() -> validator.validateForTransition(
                workItem("ENGINEERING", "DRAFT"), engineering, "IN_REVIEW"))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateForTransition(
                workItem("ENGINEERING", "IN_PROGRESS"), engineering, "CODE_REVIEW"))
                .doesNotThrowAnyException();
        verifyNoInteractions(assetRepository, postPublishTargetRepository);
    }

    @Test
    void ignoresMarketingTransitionsThatAreNotTheApprovalGate() {
        assertThatCode(() -> validator.validateForTransition(
                workItem("MARKETING", "DRAFT"), marketing, "IN_REVIEW"))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateForTransition(
                workItem("MARKETING", "IN_REVIEW"), marketing, "CHANGES_REQUESTED"))
                .doesNotThrowAnyException();
        verifyNoInteractions(assetRepository, postPublishTargetRepository);
    }

    // --- entering the scheduled status is a gate too ---

    @Test
    void schedulingAnApprovedPostRevalidatesItsFireTime() {
        // Approved with time to spare, then scheduled once the fire time had crept inside the floor: the
        // native hand-off would refuse it and the row would sit PENDING forever. Refuse the move instead.
        WorkItem post = workItem("MARKETING", "APPROVED");
        post.setScheduledFor(NOW.plusMinutes(3));
        post.setScheduleTimezone("Europe/London");
        givenTargets(1);
        givenUploadedMedia();

        assertThatThrownBy(() -> validator.validateForTransition(post, marketing, "SCHEDULED"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Cannot move Post to SCHEDULED")
                .hasMessageContaining("less than 10 minutes in the future");
    }

    @Test
    void aGatelessLifecycleIsValidatedOnItsScheduleEdge() {
        Statechart autopilot = statechart("/schema/examples/marketing-autopilot.workflow.json");
        WorkItem post = workItem("MARKETING_AUTOPILOT", "DRAFT");
        givenNoTargets();
        givenAssets();

        assertThatThrownBy(() -> validator.validateForTransition(post, autopilot, "SCHEDULED"))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("no fire time is set")
                .hasMessageContaining("no publish target is selected")
                .hasMessageContaining("no uploaded media file is attached");
        assertThatCode(() -> validator.validateForTransition(
                workItem("MARKETING_AUTOPILOT", "SCHEDULED"), autopilot, "DRAFT")).doesNotThrowAnyException();
    }

    // --- inspect: the same findings, with codes, at any status ---

    @Test
    void inspectReportsEveryProblemWithAStableCode() {
        WorkItem post = workItem("MARKETING", "DRAFT");
        post.setScheduleTimezone("Mars/Olympus");
        givenNoTargets();
        givenAssets();

        assertThat(validator.inspect(post)).extracting(PublishFinding::code)
                .containsExactly(PostScheduleValidator.NO_FIRE_TIME, PostScheduleValidator.UNKNOWN_TIMEZONE,
                        PostScheduleValidator.NO_TARGETS, PostScheduleValidator.NO_MEDIA);
        assertThat(validator.inspect(post)).allMatch(PublishFinding::blocks);
    }

    @Test
    void letsAnUnscheduledPostReturnToApprovedEvenPastItsFireTime() {
        WorkItem post = workItem("MARKETING", "SCHEDULED");
        post.setScheduledFor(NOW.minusMinutes(1));

        assertThatCode(() -> validator.validateForTransition(post, marketing, "APPROVED"))
                .doesNotThrowAnyException();
        verifyNoInteractions(assetRepository, postPublishTargetRepository);
    }

    @Test
    void ignoresATransitionTheStatechartDoesNotDeclare() {
        assertThatCode(() -> validator.validateForTransition(
                workItem("MARKETING", "DRAFT"), marketing, "PUBLISHED"))
                .doesNotThrowAnyException();
        verifyNoInteractions(assetRepository, postPublishTargetRepository);
    }

    // --- helpers ---

    private void approve(WorkItem post) {
        validator.validateForTransition(post, marketing, "APPROVED");
    }

    private WorkItem postInReview() {
        WorkItem post = workItem("MARKETING", "IN_REVIEW");
        post.setScheduledFor(NOW.plusHours(3));
        post.setScheduleTimezone("America/Los_Angeles");
        return post;
    }

    private WorkItem workItem(String workflow, String status) {
        WorkItem item = new WorkItem();
        item.setId(WORK_ITEM_ID);
        Project project = new Project();
        project.setId("proj-1");
        item.setProject(project);
        item.setWorkflow(workflow);
        item.setWorkflowVersion(1);
        item.setCurrentStatus(status);
        return item;
    }

    private void givenTargets(int count) {
        List<PostPublishTarget> targets = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            targets.add(target("facebook"));
        }
        when(postPublishTargetRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(targets);
    }

    private void givenNoTargets() {
        when(postPublishTargetRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of());
    }

    private PostPublishTarget target(String platform) {
        PostPublishTarget target = new PostPublishTarget();
        target.setPlatform(platform);
        return target;
    }

    private void givenUploadedMedia() {
        givenAssets(asset("file", "UPLOADED"));
    }

    private void givenAssets(Asset... assets) {
        when(assetRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of(assets));
    }

    private Asset asset(String kind, String uploadStatus) {
        Asset asset = new Asset();
        asset.setKind(kind);
        asset.setUploadStatus(uploadStatus);
        return asset;
    }

    private Statechart statechart(String resource) {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            return Statechart.parse(new ObjectMapper().readTree(in));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
