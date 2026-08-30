package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.Project;
import com.conductor.entity.WorkItem;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.AssetRepository;
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
    private PostScheduleValidator validator;

    private Statechart marketing;
    private Statechart engineering;

    @BeforeEach
    void setUp() {
        assetRepository = Mockito.mock(AssetRepository.class);
        postPublishTargetRepository = Mockito.mock(PostPublishTargetRepository.class);
        validator = new PostScheduleValidator(assetRepository, postPublishTargetRepository,
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

    @Test
    void appliesTheSameFloorRegardlessOfPlatform() {
        WorkItem post = postInReview();
        post.setScheduledFor(NOW.plusMinutes(9));
        givenUploadedMedia();
        when(postPublishTargetRepository.findAllByWorkItemId(WORK_ITEM_ID))
                .thenReturn(List.of(target("youtube"), target("tiktok")));

        assertThatThrownBy(() -> approve(post))
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("less than 10 minutes in the future");
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
        assertThatCode(() -> validator.validateForTransition(
                workItem("MARKETING", "APPROVED"), marketing, "SCHEDULED"))
                .doesNotThrowAnyException();
        verifyNoInteractions(assetRepository, postPublishTargetRepository);
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
