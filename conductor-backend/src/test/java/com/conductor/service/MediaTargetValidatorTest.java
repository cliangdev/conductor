package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkflowDefinitionVersion;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ConnectionRepository;
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
import java.util.ArrayList;
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

    private static final String WORK_ITEM_ID = "post-1";
    private static final long GIB = 1024L * 1024 * 1024;

    private AssetRepository assetRepository;
    private PostPublishTargetRepository postPublishTargetRepository;
    private ConnectionRepository connectionRepository;
    private MediaTargetValidator validator;

    private Statechart marketing;
    private Statechart engineering;

    @BeforeEach
    void setUp() {
        assetRepository = Mockito.mock(AssetRepository.class);
        postPublishTargetRepository = Mockito.mock(PostPublishTargetRepository.class);
        connectionRepository = Mockito.mock(ConnectionRepository.class);
        validator = new MediaTargetValidator(assetRepository, postPublishTargetRepository,
                connectionRepository, new ObjectMapper());
        marketing = statechart("/schema/examples/marketing.workflow.json");
        engineering = statechart("/schema/examples/engineering.workflow.json");
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
                workItem("MARKETING", "APPROVED"), marketing, "SCHEDULED")).doesNotThrowAnyException();
        verifyNoInteractions(assetRepository, postPublishTargetRepository, connectionRepository);
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
        return validator.validateForTransition(postInReview(), marketing, "APPROVED");
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

    private PostPublishTarget target(String platform, String connectionId, String accountLabel) {
        PostPublishTarget target = new PostPublishTarget();
        target.setPlatform(platform);
        target.setConnectionId(connectionId);
        target.setPlatformAccountLabel(accountLabel);
        return target;
    }

    private void givenConnection(String connectionId, String configJson) {
        Connection connection = new Connection();
        connection.setId(connectionId);
        connection.setConfigJson(configJson);
        when(connectionRepository.findById(connectionId)).thenReturn(Optional.of(connection));
    }

    private void givenAssets(Asset... assets) {
        when(assetRepository.findAllByWorkItemId(WORK_ITEM_ID)).thenReturn(List.of(assets));
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
