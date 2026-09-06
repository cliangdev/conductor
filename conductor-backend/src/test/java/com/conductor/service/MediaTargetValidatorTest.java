package com.conductor.service;

import com.conductor.service.publish.PublishPlatformRegistry;
import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetAsset;
import com.conductor.entity.PublishLane;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkflowDefinitionVersion;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ConnectionRepository;
import com.conductor.repository.PostPublishTargetAssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import com.conductor.signal.SignalBus;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the COND-23 per-target media gate (T5.1) against the REAL seeded statecharts: every per-platform
 * format, aspect, size and duration rule is enforced at the MARKETING approval edge, never at fire time,
 * while ENGINEERING's own review-gated edge is untouched.
 */
class MediaTargetValidatorTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-01T10:00:00Z");
    private static final String WORK_ITEM_ID = "post-1";
    private static final long GIB = 1024L * 1024 * 1024;

    private AssetRepository assetRepository;
    private PostPublishTargetRepository postPublishTargetRepository;
    private PostPublishTargetAssetRepository targetAssetRepository;
    private ConnectionRepository connectionRepository;
    private MediaTargetValidator validator;

    private Statechart marketing;
    private Statechart engineering;
    /** The Post under test, shared so a test can set the copy that will actually go out. */
    private WorkItem post;
    private final Map<String, List<PostPublishTargetAsset>> selectionsByTarget = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        assetRepository = Mockito.mock(AssetRepository.class);
        postPublishTargetRepository = Mockito.mock(PostPublishTargetRepository.class);
        connectionRepository = Mockito.mock(ConnectionRepository.class);
        targetAssetRepository = Mockito.mock(PostPublishTargetAssetRepository.class);
        // A real resolver over the same mocked repositories: every existing test stubs the Work Item's
        // assets, and with no per-target selection stored those are exactly what each target inherits.
        validator = new MediaTargetValidator(new PublishPlatformRegistry(), assetRepository, postPublishTargetRepository,
                connectionRepository, new ObjectMapper(),
                new PublishTargetMediaResolver(assetRepository, targetAssetRepository));
        marketing = statechart("/schema/examples/marketing.workflow.json");
        engineering = statechart("/schema/examples/engineering.workflow.json");
        selectionsByTarget.clear();
        post = postInReview();
    }

    // --- [auto] Media violating a target's format rules blocks approval with a per-target message ---

    @Test
    void blocksAPngTargetingInstagramNamingInstagramAndTheJpegRule() {
        givenTargets(target("instagram", "conn-meta", "@acme"));
        givenAssets(image("card.png", "image/png", 1080, 1080));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Instagram")
                .hasMessageContaining("@acme")
                .hasMessageContaining("card.png")
                .hasMessageContaining("image/png")
                .hasMessageContaining("JPEG");
    }

    @Test
    void blocksAGifTargetingInstagram() {
        givenTargets(target("instagram", "conn-meta", "@acme"));
        givenAssets(image("loop.gif", "image/gif", 1080, 1080));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Instagram")
                .hasMessageContaining("JPEG");
    }

    @Test
    void allowsAJpegTargetingInstagram() {
        givenTargets(target("instagram", "conn-meta", "@acme"));
        givenAssets(image("card.jpg", "image/jpeg", 1080, 1080));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void leavesAPngAloneWhenNoInstagramTargetIsSelected() {
        givenTargets(target("facebook", "conn-meta", "Acme Page"));
        givenAssets(image("card.png", "image/png", 1080, 1080));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    // --- [auto] Instagram aspect ratio: 4:5 .. 1.91:1 ---

    @Test
    void blocksAThreeToOneJpegTargetingInstagram() {
        givenTargets(target("instagram", "conn-meta", "@acme"));
        givenAssets(image("wide.jpg", "image/jpeg", 3000, 1000));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Instagram")
                .hasMessageContaining("wide.jpg")
                .hasMessageContaining("aspect ratio")
                .hasMessageContaining("4:5")
                .hasMessageContaining("1.91:1");
    }

    @Test
    void blocksATallerThanFourByFiveJpegTargetingInstagram() {
        givenTargets(target("instagram", "conn-meta", "@acme"));
        givenAssets(image("tall.jpg", "image/jpeg", 1000, 1600));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("aspect ratio");
    }

    @Test
    void allowsAJpegExactlyAtTheFourByFiveFloor() {
        givenTargets(target("instagram", "conn-meta", "@acme"));
        givenAssets(image("portrait.jpg", "image/jpeg", 1080, 1350));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void allowsAJpegExactlyAtTheOnePointNineOneCeiling() {
        givenTargets(target("instagram", "conn-meta", "@acme"));
        givenAssets(image("landscape.jpg", "image/jpeg", 1910, 1000));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void blocksAnInstagramJpegWhoseDimensionsAreUnknown() {
        givenTargets(target("instagram", "conn-meta", "@acme"));
        givenAssets(image("mystery.jpg", "image/jpeg", null, null));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Instagram")
                .hasMessageContaining("mystery.jpg")
                .hasMessageContaining("has unknown dimensions");
    }

    @Test
    void ignoresVideoForTheInstagramFeedImageRule() {
        givenTargets(target("instagram", "conn-meta", "@acme"));
        givenAssets(video("reel.mp4", 100L * 1024 * 1024, 1080, 1920, "30"));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    // --- [auto] Facebook video size ceiling ---

    @Test
    void blocksAFacebookVideoOverOneAndAHalfGigabytes() {
        givenTargets(target("facebook", "conn-meta", "Acme Page"));
        givenAssets(video("launch.mp4", (long) (1.8 * GIB), 1920, 1080, "45"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Facebook")
                .hasMessageContaining("Acme Page")
                .hasMessageContaining("launch.mp4")
                .hasMessageContaining("1.5 GB");
    }

    @Test
    void allowsAFacebookVideoUnderTheCeiling() {
        givenTargets(target("facebook", "conn-meta", "Acme Page"));
        givenAssets(video("launch.mp4", GIB, 1920, 1080, "45"));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void ignoresAnImageForTheFacebookVideoSizeRule() {
        givenTargets(target("facebook", "conn-meta", "Acme Page"));
        givenAssets(image("card.png", "image/png", 1200, 630));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    // --- [auto] TikTok duration against that connection's cached per-creator cap ---

    @Test
    void blocksATikTokVideoLongerThanThatConnectionsCachedMaximum() {
        givenTargets(target("tiktok", "conn-tiktok", "@creator"));
        givenConnection("conn-tiktok", "{\"maxVideoPostDurationSec\":60}");
        givenAssets(video("dance.mp4", 50L * 1024 * 1024, 1080, 1920, "120"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("TikTok")
                .hasMessageContaining("@creator")
                .hasMessageContaining("dance.mp4")
                .hasMessageContaining("60");
    }

    @Test
    void allowsATikTokVideoWithinThatConnectionsCachedMaximum() {
        givenTargets(target("tiktok", "conn-tiktok", "@creator"));
        givenConnection("conn-tiktok", "{\"maxVideoPostDurationSec\":300}");
        givenAssets(video("dance.mp4", 50L * 1024 * 1024, 1080, 1920, "120"));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void allowsATikTokVideoExactlyAtThatConnectionsCachedMaximum() {
        givenTargets(target("tiktok", "conn-tiktok", "@creator"));
        givenConnection("conn-tiktok", "{\"maxVideoPostDurationSec\":120}");
        givenAssets(video("dance.mp4", 50L * 1024 * 1024, 1080, 1920, "120"));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void readsTheCapFromEachTargetsOwnConnection() {
        givenTargets(target("tiktok", "conn-short", "@shorty"), target("tiktok", "conn-long", "@lengthy"));
        givenConnection("conn-short", "{\"maxVideoPostDurationSec\":60}");
        givenConnection("conn-long", "{\"maxVideoPostDurationSec\":600}");
        givenAssets(video("dance.mp4", 50L * 1024 * 1024, 1080, 1920, "120"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("@shorty")
                .hasMessageContaining("60");
        assertThatThrownBy(this::approve)
                .hasMessageNotContaining("@lengthy");
    }

    @Test
    void blocksATikTokVideoWhoseDurationIsUnknown() {
        givenTargets(target("tiktok", "conn-tiktok", "@creator"));
        givenConnection("conn-tiktok", "{\"maxVideoPostDurationSec\":60}");
        givenAssets(video("dance.mp4", 50L * 1024 * 1024, 1080, 1920, null));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("TikTok")
                .hasMessageContaining("has an unknown duration");
    }

    @Test
    void blocksATikTokTargetWhoseConnectionHasNoCachedMaximum() {
        givenTargets(target("tiktok", "conn-tiktok", "@creator"));
        givenConnection("conn-tiktok", "{}");
        givenAssets(video("dance.mp4", 50L * 1024 * 1024, 1080, 1920, "30"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("TikTok")
                .hasMessageContaining("no cached maximum video duration");
    }

    @Test
    void blocksATikTokVideoOverFourGigabytes() {
        givenTargets(target("tiktok", "conn-tiktok", "@creator"));
        givenConnection("conn-tiktok", "{\"maxVideoPostDurationSec\":600}");
        givenAssets(video("huge.mp4", 5L * GIB, 1080, 1920, "30"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("TikTok")
                .hasMessageContaining("4 GB");
    }

    // --- [auto] The YouTube Shorts case yields a warning and still approves ---

    @Test
    void approvesATwoMinuteVerticalVideoTargetingYouTubeWithAShortsWarning() {
        givenTargets(target("youtube", "conn-yt", "Acme Channel"));
        givenAssets(video("teaser.mp4", 200L * 1024 * 1024, 1080, 1920, "120"));

        MediaTargetValidator.Result result = validator.validateForTransition(postInReview(), marketing, "APPROVED");

        assertThat(result.hasWarnings()).isTrue();
        assertThat(result.warnings()).singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("YouTube")
                .contains("Acme Channel")
                .contains("teaser.mp4")
                .contains("Short");
    }

    @Test
    void treatsASquareVideoAtThreeMinutesAsAShort() {
        givenTargets(target("youtube", "conn-yt", "Acme Channel"));
        givenAssets(video("square.mp4", 200L * 1024 * 1024, 1080, 1080, "180"));

        assertThat(approve().hasWarnings()).isTrue();
    }

    @Test
    void doesNotWarnForALandscapeYouTubeVideo() {
        givenTargets(target("youtube", "conn-yt", "Acme Channel"));
        givenAssets(video("wide.mp4", 200L * 1024 * 1024, 1920, 1080, "120"));

        assertThat(approve().hasWarnings()).isFalse();
    }

    @Test
    void doesNotWarnForAVerticalYouTubeVideoOverThreeMinutes() {
        givenTargets(target("youtube", "conn-yt", "Acme Channel"));
        givenAssets(video("long.mp4", 200L * 1024 * 1024, 1080, 1920, "240"));

        assertThat(approve().hasWarnings()).isFalse();
    }

    @Test
    void aShortsWarningNeverBlocksTheTransition() {
        WorkItem post = postInReview();
        givenTargets(target("youtube", "conn-yt", "Acme Channel"));
        givenAssets(video("teaser.mp4", 200L * 1024 * 1024, 1080, 1920, "120"));

        assertThatCode(() -> validator.validateForTransition(post, marketing, "APPROVED"))
                .doesNotThrowAnyException();
    }

    // --- [auto] Multiple violations across two targets are reported together ---

    @Test
    void reportsEveryViolationAcrossEveryTargetInOneMessage() {
        givenTargets(target("instagram", "conn-meta", "@acme"), target("tiktok", "conn-tiktok", "@creator"));
        givenConnection("conn-tiktok", "{\"maxVideoPostDurationSec\":60}");
        givenAssets(image("card.png", "image/png", 1080, 1080),
                video("dance.mp4", 50L * 1024 * 1024, 1080, 1920, "120"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Instagram")
                .hasMessageContaining("card.png")
                .hasMessageContaining("JPEG")
                .hasMessageContaining("TikTok")
                .hasMessageContaining("dance.mp4")
                .hasMessageContaining("60");
    }

    @Test
    void namesTheWorkflowNounAndTargetStatusInTheRejection() {
        givenTargets(target("instagram", "conn-meta", "@acme"));
        givenAssets(image("card.png", "image/png", 1080, 1080));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Post")
                .hasMessageContaining("APPROVED");
    }

    // --- scope ---

    @Test
    void doesNotEvaluateAPostWithNoPublishTargets() {
        givenTargets();

        assertThatCode(this::approve).doesNotThrowAnyException();
        verifyNoInteractions(assetRepository, connectionRepository);
    }

    @Test
    void ignoresMediaThatIsNotAConfirmedFileUpload() {
        givenTargets(target("instagram", "conn-meta", "@acme"));
        Asset pending = image("card.png", "image/png", 1080, 1080);
        pending.setUploadStatus(AssetService.UPLOAD_STATUS_PENDING);
        Asset link = image("card.png", "image/png", 1080, 1080);
        link.setKind(AssetService.KIND_LINK);
        link.setUploadStatus(null);
        givenAssets(pending, link);

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void leavesTheEngineeringReviewGatedTransitionCompletelyUnaffected() {
        WorkItem issue = workItem("ENGINEERING", "CODE_REVIEW");

        assertThatCode(() -> validator.validateForTransition(issue, engineering, "DONE"))
                .doesNotThrowAnyException();
        verifyNoInteractions(assetRepository, postPublishTargetRepository, connectionRepository);
    }

    @Test
    void ignoresMarketingTransitionsThatAreNotTheApprovalGate() {
        assertThatCode(() -> validator.validateForTransition(
                workItem("MARKETING", "DRAFT"), marketing, "IN_REVIEW")).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateForTransition(
                workItem("MARKETING", "IN_REVIEW"), marketing, "CHANGES_REQUESTED")).doesNotThrowAnyException();
        verifyNoInteractions(assetRepository, postPublishTargetRepository, connectionRepository);
    }

    @Test
    void schedulingIsAGateEdgeToo() {
        WorkItem post = workItem("MARKETING", "APPROVED");
        selectionsByTarget.clear();
        org.mockito.Mockito.when(postPublishTargetRepository.findAllByWorkItemId(post.getId())).thenReturn(List.of());

        assertThatCode(() -> validator.validateForTransition(post, marketing, "SCHEDULED")).doesNotThrowAnyException();
        org.mockito.Mockito.verify(postPublishTargetRepository).findAllByWorkItemId(post.getId());
    }

    @Test
    void letsAnUnscheduledPostReturnToApprovedWithoutRevalidatingMedia() {
        assertThatCode(() -> validator.validateForTransition(
                workItem("MARKETING", "SCHEDULED"), marketing, "APPROVED")).doesNotThrowAnyException();
        verifyNoInteractions(assetRepository, postPublishTargetRepository, connectionRepository);
    }

    @Test
    void persistsNothingWhenValidationFails() {
        givenTargets(target("instagram", "conn-meta", "@acme"));
        givenAssets(image("card.png", "image/png", 1080, 1080));

        assertThatThrownBy(this::approve).isInstanceOf(UnprocessableEntityException.class);

        verify(assetRepository, never()).save(any());
        verify(postPublishTargetRepository, never()).save(any());
    }

    // --- helpers ---

    private MediaTargetValidator.Result approve() {
        return validator.validateForTransition(post, marketing, "APPROVED");
    }

    private WorkItem postInReview() {
        return workItem("MARKETING", "IN_REVIEW");
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

    private void givenTargets(PostPublishTarget... targets) {
        when(postPublishTargetRepository.findAllByWorkItemId(WORK_ITEM_ID))
                .thenReturn(new ArrayList<>(List.of(targets)));
    }

    // --- Per-target media: a rule is checked against what its own destination will actually send ---

    @Test
    void aPngIsFineOnInstagramWhenInstagramWasNotTheDestinationThatSelectedIt() {
        PostPublishTarget instagram = target("instagram", "conn-meta", "@acme");
        PostPublishTarget facebook = target("facebook", "conn-meta", "Acme Page");
        givenTargets(instagram, facebook);
        Asset jpeg = image("card.jpg", "image/jpeg", 1080, 1080);
        Asset png = image("card.png", "image/png", 1080, 1080);
        givenAssets(jpeg, png);
        // Before per-target media this was unapprovable: every asset was checked against every target, so
        // a PNG uploaded for Facebook blocked the whole Post on Instagram's JPEG rule.
        givenSelection(instagram, jpeg);
        givenSelection(facebook, png);

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    // --- Composition: what the selected files add up to as one post ---

    @Test
    void blocksACarouselOverTenItemsOnInstagram() {
        PostPublishTarget instagram = target("instagram", "conn-meta", "@acme");
        givenTargets(instagram);
        Asset[] eleven = new Asset[11];
        for (int i = 0; i < eleven.length; i++) {
            eleven[i] = image("card-" + i + ".jpg", "image/jpeg", 1080, 1080);
        }
        givenAssets(eleven);
        givenSelection(instagram, eleven);

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Instagram")
                .hasMessageContaining("at most 10");
    }

    @Test
    void allowsATenItemCarouselOnInstagram() {
        PostPublishTarget instagram = target("instagram", "conn-meta", "@acme");
        givenTargets(instagram);
        Asset[] ten = new Asset[10];
        for (int i = 0; i < ten.length; i++) {
            ten[i] = image("card-" + i + ".jpg", "image/jpeg", 1080, 1080);
        }
        givenAssets(ten);
        givenSelection(instagram, ten);

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void blocksAVideoMixedWithPhotosOnFacebook() {
        PostPublishTarget facebook = target("facebook", "conn-meta", "Acme Page");
        givenTargets(facebook);
        Asset clip = video("clip.mp4", 10L * 1024 * 1024, 1080, 1080, "20");
        Asset photo = image("card.jpg", "image/jpeg", 1080, 1080);
        givenAssets(clip, photo);
        givenSelection(facebook, clip, photo);

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Facebook")
                .hasMessageContaining("either one video or a set of photos");
    }

    @Test
    void allowsSeveralPhotosOnFacebook() {
        PostPublishTarget facebook = target("facebook", "conn-meta", "Acme Page");
        givenTargets(facebook);
        Asset one = image("a.jpg", "image/jpeg", 1080, 1080);
        Asset two = image("b.png", "image/png", 1080, 1080);
        givenAssets(one, two);
        givenSelection(facebook, one, two);

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void blocksVideoAndImagesTogetherOnTikTok() {
        PostPublishTarget tiktok = target("tiktok", "conn-tiktok", "@creator");
        givenTargets(tiktok);
        givenConnection("conn-tiktok", "{\"maxVideoPostDurationSec\":600}");
        Asset clip = video("clip.mp4", 10L * 1024 * 1024, 1080, 1920, "20");
        Asset photo = image("a.jpg", "image/jpeg", 1080, 1080);
        givenAssets(clip, photo);
        givenSelection(tiktok, clip, photo);

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("TikTok")
                .hasMessageContaining("never both");
    }

    @Test
    void blocksAPngInATikTokPhotoPostEvenThoughEveryOtherPlatformTakesIt() {
        PostPublishTarget tiktok = target("tiktok", "conn-tiktok", "@creator");
        givenTargets(tiktok);
        givenConnection("conn-tiktok", "{\"maxVideoPostDurationSec\":600}");
        Asset png = image("a.png", "image/png", 1080, 1080);
        givenAssets(png);
        givenSelection(tiktok, png);

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("TikTok")
                .hasMessageContaining("JPEG or WEBP");
    }

    @Test
    void blocksTwoVideosOnYouTube() {
        PostPublishTarget youtube = target("youtube", "conn-yt", "Acme Channel");
        givenTargets(youtube);
        Asset first = video("a.mp4", 10L * 1024 * 1024, 1920, 1080, "300");
        Asset second = video("b.mp4", 10L * 1024 * 1024, 1920, 1080, "300");
        givenAssets(first, second);
        givenSelection(youtube, first, second);

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("YouTube")
                .hasMessageContaining("one video");
    }

    @Test
    void blocksAnImageOnYouTube() {
        PostPublishTarget youtube = target("youtube", "conn-yt", "Acme Channel");
        givenTargets(youtube);
        Asset photo = image("a.jpg", "image/jpeg", 1920, 1080);
        givenAssets(photo);
        givenSelection(youtube, photo);

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("YouTube")
                .hasMessageContaining("video only");
    }

    // --- Copy: the text that will actually go out, per destination ---

    @Test
    void blocksACaptionOverInstagramsLimit() {
        PostPublishTarget instagram = target("instagram", "conn-meta", "@acme");
        givenTargets(instagram);
        givenAssets(image("card.jpg", "image/jpeg", 1080, 1080));
        post.setDescription("x".repeat(2201));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Instagram")
                .hasMessageContaining("2200");
    }

    @Test
    void measuresYouTubesDescriptionInBytesNotCharacters() {
        PostPublishTarget youtube = target("youtube", "conn-yt", "Acme Channel");
        givenTargets(youtube);
        givenAssets(video("a.mp4", 10L * 1024 * 1024, 1920, 1080, "300"));
        // 2000 four-byte emoji is 8000 bytes but only 4000 chars — a character count would pass this.
        post.setDescription("😀".repeat(2000));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("YouTube")
                .hasMessageContaining("byte");
    }

    @Test
    void anOverLongCaptionOnOneDestinationDoesNotBlockAnotherThatOverridesIt() {
        PostPublishTarget instagram = target("instagram", "conn-meta", "@acme");
        PostPublishTarget facebook = target("facebook", "conn-meta", "Acme Page");
        givenTargets(instagram, facebook);
        givenAssets(image("card.jpg", "image/jpeg", 1080, 1080));
        // Facebook's message is effectively uncapped, so the long copy is fine there; Instagram carries a
        // shorter override and so is fine too.
        post.setDescription("x".repeat(3000));
        instagram.setCaptionOverride("Short and sweet");

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    private PostPublishTarget target(String platform, String connectionId, String accountLabel) {
        PostPublishTarget target = new PostPublishTarget();
        target.setId("target-" + platform);
        target.setPlatform(platform);
        target.setConnectionId(connectionId);
        target.setPlatformAccountLabel(accountLabel);
        return target;
    }

    private PostPublishTarget target(String platform, String connectionId, String accountLabel, String format) {
        PostPublishTarget target = target(platform, connectionId, accountLabel);
        target.setFormat(format);
        return target;
    }

    private void givenConnection(String connectionId, String configJson) {
        Connection connection = new Connection();
        connection.setId(connectionId);
        connection.setConfigJson(configJson);
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
    }

    private void givenAssets(Asset... assets) {
        int index = 0;
        for (Asset asset : assets) {
            // Ids and an increasing createdAt, so AssetService.PUBLISH_ORDER is total and a per-target
            // selection has something stable to name.
            if (asset.getId() == null) {
                asset.setId("asset-" + (++index));
                asset.setCreatedAt(NOW.plusSeconds(index));
            }
        }
        when(assetRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of(assets));
    }

    /**
     * Makes {@code target} publish exactly {@code assets}, in that order, instead of the Post's set.
     *
     * <p>Selections accumulate into one map and the repository is stubbed once to answer from it, because
     * the resolver asks for every custom target's rows in a single call.
     */
    private void givenSelection(PostPublishTarget target, Asset... assets) {
        target.setCustomMedia(true);
        List<PostPublishTargetAsset> rows = new ArrayList<>();
        for (int position = 0; position < assets.length; position++) {
            rows.add(new PostPublishTargetAsset(target.getId(), assets[position].getId(), position));
        }
        selectionsByTarget.put(target.getId(), rows);
        when(targetAssetRepository.findAllByTargetIdIn(any())).thenAnswer(invocation -> {
            Collection<String> requested = invocation.getArgument(0);
            return requested == null ? List.<PostPublishTargetAsset>of() : requested.stream()
                    .flatMap(id -> selectionsByTarget.getOrDefault(id, List.of()).stream())
                    .toList();
        });
    }

    private Asset image(String label, String contentType, Integer width, Integer height) {
        Asset asset = uploadedFile(label, contentType, 1024L);
        asset.setWidth(width);
        asset.setHeight(height);
        return asset;
    }

    private Asset video(String label, Long sizeBytes, Integer width, Integer height, String durationSeconds) {
        Asset asset = uploadedFile(label, "video/mp4", sizeBytes);
        asset.setWidth(width);
        asset.setHeight(height);
        asset.setDurationSeconds(durationSeconds == null ? null : new BigDecimal(durationSeconds));
        return asset;
    }

    private Asset uploadedFile(String label, String contentType, Long sizeBytes) {
        Asset asset = new Asset();
        asset.setLabel(label);
        asset.setKind(AssetService.KIND_FILE);
        asset.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        asset.setContentType(contentType);
        asset.setSizeBytes(sizeBytes);
        return asset;
    }

    private Statechart statechart(String resource) {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            return Statechart.parse(new ObjectMapper().readTree(in));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * [auto] Media metadata needed for validation is captured at upload confirm. Every rule above reads its
     * inputs off the Asset row; this is where those inputs get there.
     */

    // --- [auto] The per-creator TikTok cap has no answer on the MANUAL lane (MKT-2) --------------

    /** A TikTok destination a human posts by hand: no account, and so no cached creator info. */
    private PostPublishTarget manualTikTokTarget() {
        PostPublishTarget target = target("tiktok", null, "TikTok (manual)");
        target.setLane(PublishLane.MANUAL);
        return target;
    }

    @Test
    void aManualTikTokDestinationIsNotBlockedByTheMissingPerCreatorDurationCap() {
        // This validator's rule is that an unchecked rule blocks — "we don't know" must never become "it's
        // fine". The manual lane is a different fact: the cap is read off a connected account's cached
        // creator info and there is no account, so there is nothing to check against rather than a missing
        // reading of something that exists. Blocking would make a manual TikTok post unapprovable, which is
        // the one thing the lane has to allow. TikTok's own composer enforces the cap on the human anyway.
        givenTargets(manualTikTokTarget());
        givenAssets(video("teaser.mp4", 10_000L, 1080, 1920, "240"));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void aManualTikTokDestinationStillHasToRespectTheFourGigabyteFileCeiling() {
        // Only the per-creator cap is exempt. The 4 GB ceiling is TikTok's for everyone, knowable without
        // an account, and a human uploading an oversized file will be refused by TikTok exactly as we are.
        givenTargets(manualTikTokTarget());
        givenAssets(video("huge.mp4", MediaTargetValidator.TIKTOK_MAX_VIDEO_BYTES + 1, 1080, 1920, "30"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("4 GB");
    }

    @Test
    void aConnectedTikTokTargetIsStillBlockedWhenItsCreatorCapIsMissing() {
        // The exemption is keyed on the lane, not on the absence of a cap. A connected account with no
        // cached creator info is still the case this validator refuses to guess at.
        givenTargets(target("tiktok", "conn-tt", "@acme"));
        givenAssets(video("teaser.mp4", 10_000L, 1080, 1920, "240"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("maximum video duration");
    }

    @Test
    void aManualInstagramDestinationStillHasToObeyInstagramsFormatRules() {
        // Nothing about posting by hand makes Instagram accept a PNG. Every rule a platform enforces on a
        // human is still worth catching at review time rather than at the moment they try to upload it.
        PostPublishTarget manual = target("instagram", null, "Instagram (manual)");
        manual.setLane(PublishLane.MANUAL);
        givenTargets(manual);
        givenAssets(image("teaser.png", "image/png", 1080, 1080));

        assertThatThrownBy(this::approve).isInstanceOf(UnprocessableEntityException.class);
    }

    // --- [auto] Post formats: story (COND-post-formats) -------------------------------------------

    @Test
    void blocksAStoryTargetWithTwoItems() {
        PostPublishTarget story = target("instagram", "conn-meta", "@acme", "STORY");
        givenTargets(story);
        Asset a = image("a.jpg", "image/jpeg", 1080, 1920);
        Asset b = image("b.jpg", "image/jpeg", 1080, 1920);
        givenAssets(a, b);
        givenSelection(story, a, b);

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Instagram")
                .hasMessageContaining("exactly one item");
    }

    @Test
    void allowsAStoryTargetWithExactlyOneItem() {
        PostPublishTarget story = target("instagram", "conn-meta", "@acme", "STORY");
        givenTargets(story);
        givenAssets(image("a.jpg", "image/jpeg", 1080, 1920));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void blocksAnInstagramStoryImageThatIsNotJpeg() {
        PostPublishTarget story = target("instagram", "conn-meta", "@acme", "STORY");
        givenTargets(story);
        givenAssets(image("a.png", "image/png", 1080, 1920));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Instagram story image")
                .hasMessageContaining("JPEG");
    }

    @Test
    void blocksAnInstagramStoryImageOverEightMegabytes() {
        PostPublishTarget story = target("instagram", "conn-meta", "@acme", "STORY");
        givenTargets(story);
        Asset asset = image("a.jpg", "image/jpeg", 1080, 1920);
        asset.setSizeBytes(9L * 1024 * 1024);
        givenAssets(asset);

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("8 MB");
    }

    @Test
    void blocksAnInstagramStoryVideoShorterThanThreeSeconds() {
        PostPublishTarget story = target("instagram", "conn-meta", "@acme", "STORY");
        givenTargets(story);
        givenAssets(video("a.mp4", 10L * 1024 * 1024, 1080, 1920, "2"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Instagram story video")
                .hasMessageContaining("3 to 60 seconds");
    }

    @Test
    void blocksAnInstagramStoryVideoLongerThanSixtySeconds() {
        PostPublishTarget story = target("instagram", "conn-meta", "@acme", "STORY");
        givenTargets(story);
        givenAssets(video("a.mp4", 10L * 1024 * 1024, 1080, 1920, "61"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("3 to 60 seconds");
    }

    @Test
    void allowsAnInstagramStoryVideoWithinRange() {
        PostPublishTarget story = target("instagram", "conn-meta", "@acme", "STORY");
        givenTargets(story);
        givenAssets(video("a.mp4", 10L * 1024 * 1024, 1080, 1920, "30"));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void blocksAFacebookStoryVideoOutsideDuration() {
        PostPublishTarget story = target("facebook", "conn-meta", "Acme Page", "STORY");
        givenTargets(story);
        givenAssets(video("a.mp4", 10L * 1024 * 1024, 1080, 1920, "1"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Facebook story video");
    }

    @Test
    void blocksAFacebookStoryPhotoOverTenMegabytes() {
        PostPublishTarget story = target("facebook", "conn-meta", "Acme Page", "STORY");
        givenTargets(story);
        Asset asset = image("a.jpg", "image/jpeg", 1080, 1920);
        asset.setSizeBytes(11L * 1024 * 1024);
        givenAssets(asset);

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("10 MB");
    }

    @Test
    void warnsWhenAStorysAspectIsFarFromNineBySixteen() {
        PostPublishTarget story = target("instagram", "conn-meta", "@acme", "STORY");
        givenTargets(story);
        givenAssets(image("a.jpg", "image/jpeg", 1080, 1080));

        MediaTargetValidator.Result result = approve();

        assertThat(result.warnings()).anyMatch(w -> w.contains("aspect ratio"));
    }

    @Test
    void doesNotWarnWhenAStorysAspectIsNearNineBySixteen() {
        PostPublishTarget story = target("instagram", "conn-meta", "@acme", "STORY");
        givenTargets(story);
        givenAssets(image("a.jpg", "image/jpeg", 1080, 1920));

        MediaTargetValidator.Result result = approve();

        assertThat(result.warnings()).noneMatch(w -> w.contains("aspect ratio"));
    }

    @Test
    void warnsWhenACaptionIsSetOnAStory() {
        PostPublishTarget story = target("instagram", "conn-meta", "@acme", "STORY");
        givenTargets(story);
        givenAssets(image("a.jpg", "image/jpeg", 1080, 1920));
        post.setDescription("Check out our launch!");

        MediaTargetValidator.Result result = approve();

        assertThat(result.warnings()).anyMatch(w -> w.contains("no caption"));
    }

    @Test
    void doesNotWarnWhenAStoryHasNoCaption() {
        PostPublishTarget story = target("instagram", "conn-meta", "@acme", "STORY");
        givenTargets(story);
        givenAssets(image("a.jpg", "image/jpeg", 1080, 1920));

        MediaTargetValidator.Result result = approve();

        assertThat(result.warnings()).noneMatch(w -> w.contains("no caption"));
    }

    // --- [auto] Post formats: reel -------------------------------------------------------------

    @Test
    void blocksAReelTargetWithAnImageInsteadOfAVideo() {
        PostPublishTarget reel = target("instagram", "conn-meta", "@acme", "REEL");
        givenTargets(reel);
        givenAssets(image("a.jpg", "image/jpeg", 1080, 1920));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("exactly one video");
    }

    @Test
    void blocksAReelTargetWithTwoVideos() {
        PostPublishTarget reel = target("instagram", "conn-meta", "@acme", "REEL");
        givenTargets(reel);
        Asset a = video("a.mp4", 10L * 1024 * 1024, 1080, 1920, "20");
        Asset b = video("b.mp4", 10L * 1024 * 1024, 1080, 1920, "20");
        givenAssets(a, b);
        givenSelection(reel, a, b);

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("exactly one video");
    }

    @Test
    void allowsAReelTargetWithExactlyOneVideo() {
        PostPublishTarget reel = target("instagram", "conn-meta", "@acme", "REEL");
        givenTargets(reel);
        givenAssets(video("a.mp4", 10L * 1024 * 1024, 1080, 1920, "20"));

        assertThatCode(this::approve).doesNotThrowAnyException();
    }

    @Test
    void blocksAFacebookReelOutsideItsDurationRange() {
        PostPublishTarget reel = target("facebook", "conn-meta", "Acme Page", "REEL");
        givenTargets(reel);
        givenAssets(video("a.mp4", 10L * 1024 * 1024, 1080, 1920, "91"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Facebook reel")
                .hasMessageContaining("3 to 90 seconds");
    }

    @Test
    void blocksAFacebookReelOverOneAndAHalfGigabytes() {
        PostPublishTarget reel = target("facebook", "conn-meta", "Acme Page", "REEL");
        givenTargets(reel);
        givenAssets(video("a.mp4", (long) (1.8 * GIB), 1080, 1920, "20"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("1.5 GB");
    }

    @Test
    void blocksAnInstagramReelOverFifteenMinutes() {
        PostPublishTarget reel = target("instagram", "conn-meta", "@acme", "REEL");
        givenTargets(reel);
        givenAssets(video("a.mp4", 10L * 1024 * 1024, 1080, 1920, "901"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("Instagram reel")
                .hasMessageContaining("3 to 900 seconds");
    }

    @Test
    void blocksAnInstagramReelOverThreeHundredMegabytes() {
        PostPublishTarget reel = target("instagram", "conn-meta", "@acme", "REEL");
        givenTargets(reel);
        givenAssets(video("a.mp4", 301L * 1024 * 1024, 1080, 1920, "20"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("300 MB");
    }

    @Test
    void warnsWhenAReelsAspectIsFarFromNineBySixteen() {
        PostPublishTarget reel = target("instagram", "conn-meta", "@acme", "REEL");
        givenTargets(reel);
        givenAssets(video("a.mp4", 10L * 1024 * 1024, 1920, 1080, "20"));

        MediaTargetValidator.Result result = approve();

        assertThat(result.warnings()).anyMatch(w -> w.contains("aspect ratio"));
    }

    // --- [auto] A Facebook feed post whose one item is a video is now a Reel on Facebook's side ---

    @Test
    void warnsWhenAFacebookFeedPostIsASingleVideo() {
        PostPublishTarget feed = target("facebook", "conn-meta", "Acme Page");
        givenTargets(feed);
        givenAssets(video("a.mp4", 10L * 1024 * 1024, 1920, 1080, "20"));

        MediaTargetValidator.Result result = approve();

        assertThat(result.warnings()).anyMatch(w -> w.contains("Reels"));
    }

    @Test
    void doesNotWarnAboutReelsWhenAFacebookFeedPostHasAPhoto() {
        PostPublishTarget feed = target("facebook", "conn-meta", "Acme Page");
        givenTargets(feed);
        givenAssets(image("a.jpg", "image/jpeg", 1200, 630));

        MediaTargetValidator.Result result = approve();

        assertThat(result.warnings()).noneMatch(w -> w.contains("Reels"));
    }

    // --- [auto] A format the platform does not offer is refused defensively ------------------------

    @Test
    void blocksAFormatThePlatformDoesNotOffer() {
        PostPublishTarget target = target("youtube", "conn-yt", "Acme Channel", "REEL");
        givenTargets(target);
        givenAssets(video("a.mp4", 10L * 1024 * 1024, 1080, 1920, "20"));

        assertThatThrownBy(this::approve)
                .isInstanceOf(UnprocessableEntityException.class)
                .hasMessageContaining("YouTube")
                .hasMessageContaining("does not publish");
    }

    @Nested
    class MetadataCaptureAtUploadConfirm {

        private static final String PROJECT_ID = "proj-1";
        private static final String ITEM_ID = "item-1";
        private static final String USER_ID = "user-1";

        private AssetRepository assets;
        private WorkItemRepository workItems;
        private StorageService storageService;
        private AssetService service;

        @BeforeEach
        void setUp() throws Exception {
            assets = Mockito.mock(AssetRepository.class);
            workItems = Mockito.mock(WorkItemRepository.class);
            storageService = Mockito.mock(StorageService.class);
            ProjectSecurityService security = Mockito.mock(ProjectSecurityService.class);
            WorkflowDefinitionVersionRepository versions =
                    Mockito.mock(WorkflowDefinitionVersionRepository.class);

            WorkflowDefinitionVersion snapshot = new WorkflowDefinitionVersion();
            snapshot.setVersion(1);
            try (InputStream in = getClass().getResourceAsStream("/schema/examples/marketing.workflow.json")) {
                snapshot.setDefinition(new ObjectMapper().readTree(in));
            }
            Mockito.lenient().when(versions.findLatestPublished(any(), Mockito.eq("MARKETING")))
                    .thenReturn(Optional.of(snapshot));
            Mockito.lenient().when(security.isProjectMember(PROJECT_ID, USER_ID)).thenReturn(true);
            Mockito.lenient().when(assets.save(any())).thenAnswer(inv -> inv.getArgument(0));
            Mockito.lenient().when(workItems.findById(ITEM_ID)).thenReturn(Optional.of(draftPost()));

            service = new AssetService(assets, workItems, security,
                    new WorkflowDefinitionResolver(versions), Mockito.mock(SignalBus.class),
                    storageService, "http://localhost:8080");
        }

        @Test
        void capturesRealPixelDimensionsOfAConfirmedJpeg() {
            Asset pending = pendingUpload("image/jpeg");
            givenStoredBytes(pending, imageBytes("jpg", 1910, 1000));

            Asset confirmed = service.confirmUpload(PROJECT_ID, ITEM_ID, "asset-1", 4096L, caller());

            assertThat(confirmed.getWidth()).isEqualTo(1910);
            assertThat(confirmed.getHeight()).isEqualTo(1000);
        }

        @Test
        void capturesRealPixelDimensionsOfAConfirmedPng() {
            Asset pending = pendingUpload("image/png");
            givenStoredBytes(pending, imageBytes("png", 640, 480));

            Asset confirmed = service.confirmUpload(PROJECT_ID, ITEM_ID, "asset-1", 4096L, caller());

            assertThat(confirmed.getWidth()).isEqualTo(640);
            assertThat(confirmed.getHeight()).isEqualTo(480);
        }

        @Test
        void theBytesInStorageOverrideWhateverTheClientDeclared() {
            Asset pending = pendingUpload("image/jpeg");
            pending.setWidth(9999);
            pending.setHeight(1);
            givenStoredBytes(pending, imageBytes("jpg", 800, 600));

            Asset confirmed = service.confirmUpload(PROJECT_ID, ITEM_ID, "asset-1", 4096L, caller());

            assertThat(confirmed.getWidth()).isEqualTo(800);
            assertThat(confirmed.getHeight()).isEqualTo(600);
        }

        @Test
        void leavesDimensionsUnknownWhenTheBytesCannotBeRead() {
            Asset pending = pendingUpload("image/webp");
            givenStoredBytes(pending, new byte[] {1, 2, 3, 4});

            Asset confirmed = service.confirmUpload(PROJECT_ID, ITEM_ID, "asset-1", 4096L, caller());

            assertThat(confirmed.getUploadStatus()).isEqualTo(AssetService.UPLOAD_STATUS_UPLOADED);
            assertThat(confirmed.getWidth()).isNull();
            assertThat(confirmed.getHeight()).isNull();
        }

        @Test
        void aStorageFailureNeverFailsTheConfirm() {
            pendingUpload("image/jpeg");
            when(storageService.download(any())).thenThrow(new IllegalStateException("bucket down"));

            Asset confirmed = service.confirmUpload(PROJECT_ID, ITEM_ID, "asset-1", 4096L, caller());

            assertThat(confirmed.getUploadStatus()).isEqualTo(AssetService.UPLOAD_STATUS_UPLOADED);
            assertThat(confirmed.getWidth()).isNull();
        }

        @Test
        void neverPullsVideoBytesBackOutOfStorage() {
            Asset pending = pendingUpload("video/mp4");
            pending.setDurationSeconds(new BigDecimal("120"));
            pending.setWidth(1080);
            pending.setHeight(1920);

            Asset confirmed = service.confirmUpload(PROJECT_ID, ITEM_ID, "asset-1", 4096L, caller());

            assertThat(confirmed.getDurationSeconds()).isEqualByComparingTo("120");
            assertThat(confirmed.getWidth()).isEqualTo(1080);
            verify(storageService, never()).download(any());
        }

        @Test
        void doesNotProbeAnImageLargerThanTheProbeCeiling() {
            pendingUpload("image/jpeg");

            service.confirmUpload(PROJECT_ID, ITEM_ID, "asset-1", MediaMetadata.MAX_PROBE_BYTES + 1, caller());

            verify(storageService, never()).download(any());
        }

        @Test
        void carriesClientDeclaredVideoMetadataOntoTheMintedRow() {
            Asset minted = service.createFileAsset(PROJECT_ID, ITEM_ID,
                    new AssetService.FileAssetInput("youtube_video", "Teaser", "teaser.mp4", "video/mp4",
                            5_000_000L, 1080, 1920, new BigDecimal("119.500")),
                    caller()).asset();

            assertThat(minted.getWidth()).isEqualTo(1080);
            assertThat(minted.getHeight()).isEqualTo(1920);
            assertThat(minted.getDurationSeconds()).isEqualByComparingTo("119.5");
        }

        @Test
        void theFiveArgumentMintStillCompilesAndLeavesTheShapeUnknown() {
            Asset minted = service.createFileAsset(PROJECT_ID, ITEM_ID,
                    new AssetService.FileAssetInput("youtube_video", "Teaser", "teaser.mp4", "video/mp4",
                            5_000_000L),
                    caller()).asset();

            assertThat(minted.getWidth()).isNull();
            assertThat(minted.getHeight()).isNull();
            assertThat(minted.getDurationSeconds()).isNull();
        }

        @Test
        void discardsNonsenseClientDeclaredValues() {
            Asset minted = service.createFileAsset(PROJECT_ID, ITEM_ID,
                    new AssetService.FileAssetInput("youtube_video", "Teaser", "teaser.mp4", "video/mp4",
                            5_000_000L, 0, -4, BigDecimal.ZERO),
                    caller()).asset();

            assertThat(minted.getWidth()).isNull();
            assertThat(minted.getHeight()).isNull();
            assertThat(minted.getDurationSeconds()).isNull();
        }

        private Asset pendingUpload(String contentType) {
            Asset asset = new Asset();
            asset.setId("asset-1");
            asset.setType("instagram_post");
            asset.setKind(AssetService.KIND_FILE);
            asset.setContentType(contentType);
            asset.setSizeBytes(4096L);
            asset.setGcsPath("marketing-assets/" + PROJECT_ID + "/" + ITEM_ID + "/asset-1-card");
            asset.setRef(asset.getGcsPath());
            asset.setUploadStatus(AssetService.UPLOAD_STATUS_PENDING);
            when(assets.findByIdAndWorkItemId("asset-1", ITEM_ID)).thenReturn(Optional.of(asset));
            return asset;
        }

        private void givenStoredBytes(Asset asset, byte[] bytes) {
            when(storageService.download(asset.getGcsPath())).thenReturn(bytes);
        }

        private byte[] imageBytes(String format, int width, int height) {
            try {
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                ImageIO.write(image, format, out);
                return out.toByteArray();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private WorkItem draftPost() {
            WorkItem item = new WorkItem();
            item.setId(ITEM_ID);
            item.setTitle("Launch teaser");
            Project project = new Project();
            project.setId(PROJECT_ID);
            item.setProject(project);
            item.setWorkflow("MARKETING");
            item.setCurrentStatus("DRAFT");
            return item;
        }

        private User caller() {
            User user = new User();
            user.setId(USER_ID);
            return user;
        }
    }

}
