package com.conductor.creative;

import com.conductor.exception.ConflictException;
import com.conductor.entity.Asset;
import com.conductor.entity.Connection;
import com.conductor.entity.MemberRole;
import com.conductor.entity.PostPublishTarget;
import com.conductor.entity.PostPublishTargetAsset;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.PostPublishTargetAssetRepository;
import com.conductor.repository.PostPublishTargetRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.AssetService;
import com.conductor.service.MediaTargetValidator;
import com.conductor.service.PublishTargetService;
import com.conductor.service.StorageService;
import com.conductor.service.publish.PublishFinding;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkflowSeeder;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CreativeAttachService} against a real database (COND-24 T3, AC-P0-3.3): a Post whose Instagram
 * (feed), TikTok and Facebook (feed) targets all inherit media gets each destination's frame attached by
 * placement; a target with {@code custom_media = true} is left untouched and reported skipped; and a
 * placement rendered as a sequence attaches its frames in index order, never re-sorted.
 *
 * <p>Renders and frames are inserted directly through their repositories rather than through {@link
 * CreativeRenderService} — this suite is about the attach mapping and the target-selection rewrite, not
 * about the render lifecycle, which {@code CreativeRenderServiceIntegrationTest} already covers.
 */
class CreativeAttachServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private CreativeAttachService attachService;
    @Autowired private CreativePhotoService photoService;
    @Autowired private CreativePhotoRepository photoRepository;
    @Autowired private CreativeRenderRepository renderRepository;
    @Autowired private CreativeRenderFrameRepository frameRepository;
    @Autowired private CreativeRepository creativeRepository;
    @Autowired private BrandKitService brandKitService;
    @Autowired private PublishTargetService publishTargetService;
    @Autowired private MediaTargetValidator mediaTargetValidator;
    @Autowired private com.conductor.service.publish.PublishPreflightService publishPreflightService;
    @Autowired private PostPublishTargetRepository targetRepository;
    @Autowired private PostPublishTargetAssetRepository targetAssetRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private StorageService storageService;
    @Autowired private WorkItemService workItemService;
    @Autowired private WorkflowSeeder workflowSeeder;
    @Autowired private com.conductor.repository.ConnectionRepository connectionRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ObjectMapper objectMapper;

    private User admin;
    private Project project;
    private WorkItem post;
    private Creative creative;
    private String metaConnectionId;
    private String tiktokConnectionId;

    @BeforeEach
    void setUp() {
        admin = newUser();
        project = new Project();
        project.setName("Attach Test");
        project.setKey("AT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(admin);
        project = projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(admin);
        membership.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(membership);

        workflowSeeder.seedMarketing(project);
        post = workItemService.createWorkItem(project.getId(), "POST", "Launch teaser", "Original caption",
                "MARKETING", admin);

        BrandKit kit = brandKitService.resolveDefault(project.getId());
        creative = newCreative(kit);

        Connection meta = connection("meta", "{\"pageId\":\"p1\",\"pageName\":\"Acme Page\","
                + "\"instagramBusinessAccountId\":\"ig1\",\"instagramUsername\":\"acme\"}");
        metaConnectionId = connectionRepository.saveAndFlush(meta).getId();
        Connection tiktok = connection("tiktok", "{\"creatorNickname\":\"Acme Creator\",\"creatorUsername\":\"acme\"}");
        tiktokConnectionId = connectionRepository.saveAndFlush(tiktok).getId();

        // AC-P0-3.3: Instagram, TikTok and Facebook targets, all inheriting the Post's media (feed format).
        publishTargetService.replaceSelection(project.getId(), post.getId(), List.of(
                new PublishTargetService.TargetSelection("facebook", metaConnectionId),
                new PublishTargetService.TargetSelection("instagram", metaConnectionId),
                new PublishTargetService.TargetSelection("tiktok", tiktokConnectionId)), admin);
    }

    // ── AC-P0-3.3 ────────────────────────────────────────────────────────────────────────────────

    @Test
    void attachingASucceededRenderSetsEachInheritingTargetsMediaByPlacement() {
        CreativeRender render = newRender();
        newFrame(render, "9x16", null, "tiktok", 1080, 1920);
        newFrame(render, "4x5", null, "instagram", 1080, 1350);
        newFrame(render, "1x1", null, "facebook", 1080, 1080);
        newFrame(render, CreativeRenderFrame.PLACEMENT_SHEET, null, null, 2000, 2000, "image/png");

        CreativeAttachService.AttachResult result = attachService.attach(project.getId(), creative.getId(),
                render.getId(), post.getId(), admin);

        // The contact sheet is a preview artifact, never attached.
        assertThat(result.assets()).hasSize(3);
        assertThat(result.assets()).extracting(CreativeAttachService.AttachedAsset::placementKey)
                .containsExactlyInAnyOrder("9x16", "4x5", "1x1");

        assertThat(result.targetsUpdated()).hasSize(3);
        assertThat(result.targetsSkipped()).isEmpty();

        Asset tiktokAsset = assetRepository.findById(
                assetIdFor(result, "9x16")).orElseThrow();
        assertThat(tiktokAsset.getWorkItem().getId()).isEqualTo(post.getId());
        assertThat(tiktokAsset.getKind()).isEqualTo(AssetService.KIND_FILE);
        assertThat(tiktokAsset.getUploadStatus()).isEqualTo(AssetService.UPLOAD_STATUS_UPLOADED);
        assertThat(tiktokAsset.getCreativeFrameId()).isNotNull();
        // Frames are JPEG (see CreativeRenderService). Nothing is copied: the Asset links the frame's own
        // stored object, so its path IS the frame's path (not a new marketing-assets/... copy).
        assertThat(tiktokAsset.getContentType()).isEqualTo("image/jpeg");
        CreativeRenderFrame tiktokFrame = frameRepository.findById(tiktokAsset.getCreativeFrameId()).orElseThrow();
        assertThat(tiktokAsset.getGcsPath()).isEqualTo(tiktokFrame.getGcsPath());
        assertThat(tiktokAsset.getRef()).isEqualTo(tiktokFrame.getGcsPath());
        assertThat(tiktokAsset.getGcsPath()).doesNotStartWith("marketing-assets/");
        assertThat(tiktokAsset.getMediaId()).isNull();

        PostPublishTarget tiktokTarget = targetRepository.findAllByWorkItemId(post.getId()).stream()
                .filter(t -> "tiktok".equals(t.getPlatform())).findFirst().orElseThrow();
        assertThat(tiktokTarget.isCustomMedia()).isTrue();
        assertThat(storedAssetIds(tiktokTarget.getId())).containsExactly(assetIdFor(result, "9x16"));

        PostPublishTarget instagramTarget = targetRepository.findAllByWorkItemId(post.getId()).stream()
                .filter(t -> "instagram".equals(t.getPlatform())).findFirst().orElseThrow();
        assertThat(storedAssetIds(instagramTarget.getId())).containsExactly(assetIdFor(result, "4x5"));

        PostPublishTarget facebookTarget = targetRepository.findAllByWorkItemId(post.getId()).stream()
                .filter(t -> "facebook".equals(t.getPlatform())).findFirst().orElseThrow();
        assertThat(storedAssetIds(facebookTarget.getId())).containsExactly(assetIdFor(result, "1x1"));

        // The Post's own media is filled purely as a side effect of the assets now existing on it.
        assertThat(assetRepository.findAllByWorkItemId(post.getId())).hasSize(3);
    }

    @Test
    void aPostLinkingAFramePopulatesMediaUsageForTheMediaItsCreativeUses() {
        CreativePhoto photo = new CreativePhoto();
        photo.setId(UUID.randomUUID().toString());
        photo.setProjectId(project.getId());
        photo.setLabel("Source photo");
        photo.setGcsPath("projects/" + project.getId() + "/marketing/photos/" + photo.getId() + ".jpg");
        photo.setContentType("image/jpeg");
        photo.setSizeBytes(100L);
        photo.setMediaKind(CreativePhoto.MEDIA_KIND_IMAGE);
        photo.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        photo.setAiGenerated(false);
        photo.setFocal(objectMapper.createObjectNode());
        photoRepository.save(photo);
        creative.setPhotoId(photo.getId());
        creativeRepository.save(creative);
        CreativeRender render = newRender();
        newFrame(render, "4x5", null, "instagram", 1080, 1350);

        // Before the attach: used by one Creative, on no Post.
        assertThat(photoService.getPhoto(project.getId(), photo.getId(), admin).postCount()).isZero();

        attachService.attach(project.getId(), creative.getId(), render.getId(), post.getId(), admin);

        CreativePhotoService.MediaUsage usage = photoService.getUsage(project.getId(), photo.getId(), admin);
        assertThat(usage.creatives()).extracting(CreativePhotoService.UsageCreative::id).containsExactly(creative.getId());
        assertThat(usage.posts()).extracting(CreativePhotoService.UsagePost::workItemId).containsExactly(post.getId());
        CreativePhotoService.PhotoView view = photoService.getPhoto(project.getId(), photo.getId(), admin);
        assertThat(view.postCount()).isEqualTo(1);
        assertThat(view.creativeCount()).isEqualTo(1);
    }

    @Test
    void attachingTheSameRenderAgainReusesTheFramesAlreadyOnThePost() {
        CreativeRender render = newRender();
        newFrame(render, "9x16", null, "tiktok", 1080, 1920);
        newFrame(render, "4x5", null, "instagram", 1080, 1350);
        newFrame(render, "1x1", null, "facebook", 1080, 1080);

        CreativeAttachService.AttachResult first = attachService.attach(project.getId(), creative.getId(),
                render.getId(), post.getId(), admin);
        CreativeAttachService.AttachResult second = attachService.attach(project.getId(), creative.getId(),
                render.getId(), post.getId(), admin);

        assertThat(assetRepository.findAllByWorkItemId(post.getId())).hasSize(3);
        assertThat(second.assets()).extracting(CreativeAttachService.AttachedAsset::assetId)
                .containsExactlyInAnyOrderElementsOf(
                        first.assets().stream().map(CreativeAttachService.AttachedAsset::assetId).toList());
    }

    @Test
    void aPreviewRenderIsRefusedBecauseItHasNoFramesToAttach() {
        CreativeRender preview = newRender();
        preview.setPreviewOnly(true);
        renderRepository.save(preview);
        newFrame(preview, CreativeRenderFrame.PLACEMENT_SHEET, null, null, 1200, 700, "image/jpeg");

        assertThatThrownBy(() -> attachService.attach(project.getId(), creative.getId(), preview.getId(), post.getId(), admin))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("preview");
        assertThat(assetRepository.findAllByWorkItemId(post.getId())).isEmpty();
    }

    // ── frames are JPEG, so an attached Instagram feed target clears the media-format gate ────────

    @Test
    void aJpegFrameAttachedToTheInstagramFeedTargetPassesTheMediaFormatGate() {
        CreativeRender render = newRender();
        newFrame(render, "4x5", null, "instagram", 1080, 1350);

        attachService.attach(project.getId(), creative.getId(), render.getId(), post.getId(), admin);

        PostPublishTarget instagramTarget = targetRepository.findAllByWorkItemId(post.getId()).stream()
                .filter(t -> "instagram".equals(t.getPlatform())).findFirst().orElseThrow();

        // MediaTargetValidator.inspect is the same gate check a MARKETING approval transition runs
        // (AC-P0-3.3): a PNG frame would report a MEDIA_FORMAT blocker naming this target; a JPEG frame
        // (what CreativeRenderService now stores every placement as) must not.
        List<PublishFinding> findings = mediaTargetValidator.inspect(post);
        assertThat(findings)
                .filteredOn(f -> instagramTarget.getId().equals(f.targetId()))
                .noneMatch(PublishFinding::blocks);
    }

    // ── Instagram alt text from the Creative ────────────────────────────────────────────────────

    private String altTextOption(String platform) {
        String json = targetOf(platform).getPublishOptions();
        if (json == null) return null;
        try {
            var node = objectMapper.readTree(json).get("altText");
            return node == null ? null : node.asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aStillAttachedToInstagramCarriesTheCreativesAltTextAndFacebookGetsNone() {
        creative.setAltText("A bowl of chicken and rice");
        creativeRepository.save(creative);
        CreativeRender render = newRender();
        newFrame(render, "4x5", null, "instagram", 1080, 1350);
        newFrame(render, "1x1", null, "facebook", 1080, 1080);

        CreativeAttachService.AttachResult result = attachService.attach(project.getId(), creative.getId(),
                render.getId(), post.getId(), admin);

        assertThat(altTextOption("instagram")).isEqualTo("A bowl of chicken and rice");
        assertThat(altTextOption("facebook")).isNull();
        assertThat(result.targetsUpdated()).filteredOn(u -> "instagram".equals(u.platform()))
                .singleElement().satisfies(u -> assertThat(u.altText()).isEqualTo("A bowl of chicken and rice"));
        assertThat(result.targetsUpdated()).filteredOn(u -> "facebook".equals(u.platform()))
                .singleElement().satisfies(u -> assertThat(u.altText()).isNull());
    }

    @Test
    void aBlankAltTextFallsBackToTheFirstSlideAltText() {
        creative.setSlideAltText(objectMapper.valueToTree(List.of("Slide one description")));
        creativeRepository.save(creative);
        CreativeRender render = newRender();
        newFrame(render, "4x5", null, "instagram", 1080, 1350);

        attachService.attach(project.getId(), creative.getId(), render.getId(), post.getId(), admin);

        assertThat(altTextOption("instagram")).isEqualTo("Slide one description");
    }

    @Test
    void anAltTextTheAuthorSetIsNeverOverwritten() {
        PostPublishTarget ig = targetOf("instagram");
        ig.setPublishOptions("{\"altText\":\"Mine\"}");
        targetRepository.save(ig);
        creative.setAltText("From the Creative");
        creativeRepository.save(creative);
        CreativeRender render = newRender();
        newFrame(render, "4x5", null, "instagram", 1080, 1350);

        CreativeAttachService.AttachResult result = attachService.attach(project.getId(), creative.getId(),
                render.getId(), post.getId(), admin);

        assertThat(altTextOption("instagram")).isEqualTo("Mine");
        assertThat(result.targetsUpdated()).filteredOn(u -> "instagram".equals(u.platform()))
                .singleElement().satisfies(u -> assertThat(u.altText()).isNull());
    }

    @Test
    void aCarouselGetsNoInstagramAltText() {
        creative.setAltText("Described");
        creative.setKind(Creative.KIND_CAROUSEL);
        creativeRepository.save(creative);
        CreativeRender render = newRender();
        newFrame(render, "4x5", 0, "instagram", 1080, 1350);
        newFrame(render, "4x5", 1, "instagram", 1080, 1350);

        attachService.attach(project.getId(), creative.getId(), render.getId(), post.getId(), admin);

        assertThat(altTextOption("instagram")).isNull();
    }

    @Test
    void aVideoFrameGetsNoInstagramAltText() {
        creative.setAltText("Described");
        creative.setKind(Creative.KIND_CLIP);
        creativeRepository.save(creative);
        CreativeRender render = newRender();
        newFrame(render, "9x16", null, "instagram", 1080, 1920, "video/mp4");

        attachService.attach(project.getId(), creative.getId(), render.getId(), post.getId(), admin);

        assertThat(altTextOption("instagram")).isNull();
    }

    @Test
    void anInstagramDestinationAddedAfterTheAttachGetsTheAltTextToo() {
        creative.setAltText("A bowl of chicken and rice");
        creativeRepository.save(creative);
        CreativeRender render = newRender();
        newFrame(render, "4x5", null, "instagram", 1080, 1350);
        newFrame(render, "1x1", null, "facebook", 1080, 1080);
        publishTargetService.replaceSelection(project.getId(), post.getId(), List.of(
                new PublishTargetService.TargetSelection("facebook", metaConnectionId)), admin);
        attachService.attach(project.getId(), creative.getId(), render.getId(), post.getId(), admin);

        publishTargetService.replaceSelection(project.getId(), post.getId(), List.of(
                new PublishTargetService.TargetSelection("facebook", metaConnectionId),
                new PublishTargetService.TargetSelection("instagram", metaConnectionId)), admin);

        assertThat(altTextOption("instagram")).isEqualTo("A bowl of chicken and rice");
        assertThat(altTextOption("facebook")).isNull();
    }

    // ── custom_media = true is skipped and left untouched ───────────────────────────────────────

    @Test
    void aTargetWithItsOwnChosenMediaIsSkippedAndLeftUntouched() {
        PostPublishTarget facebookTarget = targetRepository.findAllByWorkItemId(post.getId()).stream()
                .filter(t -> "facebook".equals(t.getPlatform())).findFirst().orElseThrow();
        Asset existingAsset = newExistingAsset();
        facebookTarget.setCustomMedia(true);
        targetRepository.save(facebookTarget);
        targetAssetRepository.save(new PostPublishTargetAsset(facebookTarget.getId(), existingAsset.getId(), 0));

        CreativeRender render = newRender();
        newFrame(render, "1x1", null, "facebook", 1080, 1080);
        newFrame(render, "9x16", null, "tiktok", 1080, 1920);
        newFrame(render, "4x5", null, "instagram", 1080, 1350);

        CreativeAttachService.AttachResult result = attachService.attach(project.getId(), creative.getId(),
                render.getId(), post.getId(), admin);

        assertThat(result.targetsSkipped()).hasSize(1);
        assertThat(result.targetsSkipped().get(0).targetId()).isEqualTo(facebookTarget.getId());
        assertThat(result.targetsUpdated()).extracting(CreativeAttachService.TargetUpdate::platform)
                .containsExactlyInAnyOrder("tiktok", "instagram");

        PostPublishTarget reloaded = targetRepository.findById(facebookTarget.getId()).orElseThrow();
        assertThat(reloaded.isCustomMedia()).isTrue();
        assertThat(storedAssetIds(facebookTarget.getId())).containsExactly(existingAsset.getId());
    }

    // ── video attach end to end (COND-24 PR1) ───────────────────────────────────────────────────

    @Test
    void aClipRendersVideoFramesThatAttachToInstagramReelTikTokYouTubeAndFacebookFeedAndPassTheMediaGate() {
        Connection youtube = connection("youtube", "{\"channelId\":\"UC123\",\"channelTitle\":\"Acme Channel\"}");
        String youtubeConnectionId = connectionRepository.saveAndFlush(youtube).getId();
        // TikTok's per-creator video-length cap must be cached for MediaTargetValidator to clear a video
        // (see MediaTargetValidator#cachedMaxVideoDurationSec) — set generously above this test's clip.
        Connection tiktok = connectionRepository.findById(tiktokConnectionId).orElseThrow();
        tiktok.setConfigJson("{\"creatorNickname\":\"Acme Creator\",\"creatorUsername\":\"acme\",\"maxVideoPostDurationSec\":600}");
        connectionRepository.saveAndFlush(tiktok);

        publishTargetService.replaceSelection(project.getId(), post.getId(), List.of(
                new PublishTargetService.TargetSelection("instagram", metaConnectionId, null, null, null, "reel"),
                new PublishTargetService.TargetSelection("tiktok", tiktokConnectionId),
                new PublishTargetService.TargetSelection("youtube", youtubeConnectionId),
                new PublishTargetService.TargetSelection("facebook", metaConnectionId)), admin);

        CreativeRender render = newRender();
        // 16x9 sorts before 9x16 (frames are read sequenceIndex-first, then placementKey ascending), so it
        // is checked first for every target: YouTube and Facebook feed (no 1x1 frame here) claim it; TikTok
        // and the Instagram reel fall through to 9x16.
        newVideoFrame(render, "16x9", "youtube", 1920, 1080);
        newVideoFrame(render, "9x16", "tiktok", 1080, 1920);

        CreativeAttachService.AttachResult result = attachService.attach(project.getId(), creative.getId(),
                render.getId(), post.getId(), admin);

        assertThat(result.targetsSkipped()).isEmpty();
        assertThat(result.targetsUpdated()).extracting(CreativeAttachService.TargetUpdate::platform)
                .containsExactlyInAnyOrder("instagram", "tiktok", "youtube", "facebook");
        // Every target gets exactly one video, never two.
        assertThat(result.targetsUpdated()).allSatisfy(u -> assertThat(u.assetIds()).hasSize(1));

        for (PostPublishTarget target : targetRepository.findAllByWorkItemId(post.getId())) {
            Asset asset = assetRepository.findById(storedAssetIds(target.getId()).get(0)).orElseThrow();
            assertThat(asset.getContentType()).startsWith("video/");
            assertThat(asset.getDurationSeconds()).isNotNull();
            assertThat(asset.getWidth()).isNotNull();
            assertThat(asset.getHeight()).isNotNull();
        }

        List<PublishFinding> findings = mediaTargetValidator.inspect(post);
        assertThat(findings).noneMatch(PublishFinding::blocks);
    }

    // ── a MOTION render's video frames attach exactly like a CLIP's (COND-24 PR2) ─────────────────

    @Test
    void aMotionRendersVideoFramesAttachToTikTokAndInstagramReelJustLikeAClips() {
        creative.setKind(Creative.KIND_MOTION);
        creativeRepository.save(creative);

        publishTargetService.replaceSelection(project.getId(), post.getId(), List.of(
                new PublishTargetService.TargetSelection("instagram", metaConnectionId, null, null, null, "reel"),
                new PublishTargetService.TargetSelection("tiktok", tiktokConnectionId)), admin);

        CreativeRender render = newRender();
        newVideoFrame(render, "9x16", "tiktok", 1080, 1920);

        CreativeAttachService.AttachResult result = attachService.attach(project.getId(), creative.getId(),
                render.getId(), post.getId(), admin);

        assertThat(result.targetsSkipped()).isEmpty();
        assertThat(result.targetsUpdated()).extracting(CreativeAttachService.TargetUpdate::platform)
                .containsExactlyInAnyOrder("instagram", "tiktok");
        for (PostPublishTarget target : targetRepository.findAllByWorkItemId(post.getId())) {
            Asset asset = assetRepository.findById(storedAssetIds(target.getId()).get(0)).orElseThrow();
            assertThat(asset.getContentType()).startsWith("video/");
        }
    }

    // ── sequence frames attach in index order, never sorted ─────────────────────────────────────

    @Test
    void sequenceFramesForOnePlacementAttachInSequenceIndexOrderRegardlessOfWriteOrder() {
        CreativeRender render = newRender();
        // Written out of order (2, 0, 1) — the attach order must follow sequenceIndex, not insertion order.
        newFrame(render, "4x5", 2, "instagram", 1080, 1350);
        newFrame(render, "4x5", 0, "instagram", 1080, 1350);
        newFrame(render, "4x5", 1, "instagram", 1080, 1350);

        CreativeAttachService.AttachResult result = attachService.attach(project.getId(), creative.getId(),
                render.getId(), post.getId(), admin);

        List<CreativeAttachService.AttachedAsset> ordered = result.assets().stream()
                .sorted(java.util.Comparator.comparing(CreativeAttachService.AttachedAsset::sequenceIndex))
                .toList();
        assertThat(ordered).extracting(CreativeAttachService.AttachedAsset::sequenceIndex)
                .containsExactly(0, 1, 2);

        PostPublishTarget instagramTarget = targetRepository.findAllByWorkItemId(post.getId()).stream()
                .filter(t -> "instagram".equals(t.getPlatform())).findFirst().orElseThrow();
        List<String> assetIdsInIndexOrder = ordered.stream().map(CreativeAttachService.AttachedAsset::assetId).toList();
        assertThat(storedAssetIds(instagramTarget.getId())).containsExactlyElementsOf(assetIdsInIndexOrder);
    }

    // ── CAROUSEL attach: ordered explicit selections, never inherit ──────────────────────────────

    private PostPublishTarget targetOf(String platform) {
        return targetRepository.findAllByWorkItemId(post.getId()).stream()
                .filter(t -> platform.equals(t.getPlatform())).findFirst().orElseThrow();
    }

    private List<String> carouselAssetIds(CreativeAttachService.AttachResult result, String placementKey) {
        return result.assets().stream().filter(a -> placementKey.equals(a.placementKey()))
                .sorted(java.util.Comparator.comparing(CreativeAttachService.AttachedAsset::sequenceIndex))
                .map(CreativeAttachService.AttachedAsset::assetId).toList();
    }

    @Test
    void aCarouselGivesEachFeedTargetItsOwnPlacementsSlidesInSlideOrder() {
        creative.setKind(Creative.KIND_CAROUSEL);
        creativeRepository.save(creative);
        CreativeRender render = newRender();
        // Written out of order within each placement: the selection must follow sequenceIndex.
        for (int i : new int[] {2, 0, 1}) {
            newFrame(render, "4x5", i, "instagram", 1080, 1350);
            newFrame(render, "1x1", i, "facebook", 1080, 1080);
            newFrame(render, "9x16", i, "tiktok", 1080, 1920);
        }

        CreativeAttachService.AttachResult result = attachService.attach(project.getId(), creative.getId(),
                render.getId(), post.getId(), admin);

        assertThat(result.targetsSkipped()).isEmpty();
        assertThat(result.targetsUpdated()).extracting(CreativeAttachService.TargetUpdate::platform)
                .containsExactlyInAnyOrder("instagram", "facebook", "tiktok");
        assertThat(storedAssetIds(targetOf("instagram").getId())).containsExactlyElementsOf(carouselAssetIds(result, "4x5"));
        assertThat(storedAssetIds(targetOf("facebook").getId())).containsExactlyElementsOf(carouselAssetIds(result, "1x1"));
        assertThat(storedAssetIds(targetOf("tiktok").getId())).containsExactlyElementsOf(carouselAssetIds(result, "9x16"));
        assertThat(storedAssetIds(targetOf("instagram").getId())).hasSize(3);
    }

    @Test
    void aFourByFiveOnlyCarouselFallsBackToFourByFiveForFacebookAndTikTok() {
        creative.setKind(Creative.KIND_CAROUSEL);
        creativeRepository.save(creative);
        CreativeRender render = newRender();
        for (int i = 0; i < 3; i++) {
            newFrame(render, "4x5", i, "instagram", 1080, 1350);
        }

        CreativeAttachService.AttachResult result = attachService.attach(project.getId(), creative.getId(),
                render.getId(), post.getId(), admin);

        List<String> ordered = carouselAssetIds(result, "4x5");
        for (String platform : List.of("instagram", "facebook", "tiktok")) {
            assertThat(storedAssetIds(targetOf(platform).getId())).containsExactlyElementsOf(ordered);
        }
    }

    @Test
    void aCarouselLeavesAReelStoryAndYouTubeExplicitlyEmptyAndPreflightBlocksThem() {
        Connection youtube = connection("youtube", "{\"channelId\":\"UC123\",\"channelTitle\":\"Acme Channel\"}");
        String youtubeConnectionId = connectionRepository.saveAndFlush(youtube).getId();
        publishTargetService.replaceSelection(project.getId(), post.getId(), List.of(
                new PublishTargetService.TargetSelection("instagram", metaConnectionId, null, null, null, "reel"),
                new PublishTargetService.TargetSelection("facebook", metaConnectionId, null, null, null, "story"),
                new PublishTargetService.TargetSelection("tiktok", tiktokConnectionId),
                new PublishTargetService.TargetSelection("youtube", youtubeConnectionId)), admin);
        creative.setKind(Creative.KIND_CAROUSEL);
        creativeRepository.save(creative);
        CreativeRender render = newRender();
        for (int i = 0; i < 3; i++) {
            newFrame(render, "4x5", i, "instagram", 1080, 1350);
        }

        CreativeAttachService.AttachResult result = attachService.attach(project.getId(), creative.getId(),
                render.getId(), post.getId(), admin);

        List<PostPublishTarget> targets = targetRepository.findAllByWorkItemId(post.getId());
        assertThat(targets).hasSize(4);
        List<PostPublishTarget> unsupported = targets.stream()
                .filter(t -> "reel".equalsIgnoreCase(t.getFormat()) || "story".equalsIgnoreCase(t.getFormat())
                        || "youtube".equals(t.getPlatform())).toList();
        assertThat(unsupported).hasSize(3);
        for (PostPublishTarget target : unsupported) {
            // custom_media with no rows: never inherits the Post's three slides.
            assertThat(target.isCustomMedia()).isTrue();
            assertThat(storedAssetIds(target.getId())).isEmpty();
        }
        assertThat(result.targetsSkipped()).extracting(CreativeAttachService.TargetSkip::targetId)
                .containsExactlyInAnyOrderElementsOf(unsupported.stream().map(PostPublishTarget::getId).toList());
        assertThat(result.targetsUpdated()).extracting(CreativeAttachService.TargetUpdate::platform)
                .containsExactly("tiktok");
        assertThat(storedAssetIds(targetOf("tiktok").getId())).hasSize(3);

        List<PublishFinding> multiImage = mediaTargetValidator.inspect(post).stream()
                .filter(f -> MediaTargetValidator.MULTI_IMAGE_UNSUPPORTED.equals(f.code())).toList();
        assertThat(multiImage).extracting(PublishFinding::targetId)
                .containsExactlyInAnyOrderElementsOf(unsupported.stream().map(PostPublishTarget::getId).toList());
        assertThat(multiImage).allSatisfy(f -> assertThat(f.blocks()).isTrue());
        assertThat(multiImage).extracting(PublishFinding::message).anyMatch(m -> m.startsWith("Instagram Reel ("));

        // Re-attaching leaves them explicitly empty (and does not flip them to inherit).
        attachService.attach(project.getId(), creative.getId(), render.getId(), post.getId(), admin);
        for (PostPublishTarget target : unsupported) {
            assertThat(targetRepository.findById(target.getId()).orElseThrow().isCustomMedia()).isTrue();
        }
    }

    // ── a story sequence publishes as N stories: the STORY target takes all beats, in order ─────

    @Test
    void aThreeBeatStorySequenceAttachesInOrderToAnInstagramStoryTargetAndRaisesNoStoryBlocker() {
        publishTargetService.replaceSelection(project.getId(), post.getId(), List.of(
                new PublishTargetService.TargetSelection("instagram", metaConnectionId, null, null, null, "story")),
                admin);
        CreativeRender render = newRender();
        // Written out of order; the sequence index decides.
        newFrame(render, "story", 2, "instagram", 1080, 1920);
        newFrame(render, "story", 0, "instagram", 1080, 1920);
        newFrame(render, "story", 1, "instagram", 1080, 1920);

        CreativeAttachService.AttachResult result = attachService.attach(project.getId(), creative.getId(),
                render.getId(), post.getId(), admin);

        assertThat(result.targetsUpdated()).hasSize(1);
        PostPublishTarget story = targetRepository.findAllByWorkItemId(post.getId()).stream()
                .filter(t -> "STORY".equals(t.getFormat())).findFirst().orElseThrow();
        List<String> inIndexOrder = result.assets().stream()
                .sorted(java.util.Comparator.comparing(CreativeAttachService.AttachedAsset::sequenceIndex))
                .map(CreativeAttachService.AttachedAsset::assetId).toList();
        assertThat(inIndexOrder).hasSize(3);
        assertThat(storedAssetIds(story.getId())).containsExactlyElementsOf(inIndexOrder);

        // The publish gate: no story-count blocker (it used to refuse anything but one item), and nothing
        // blocks the target's media.
        assertThat(mediaTargetValidator.inspect(post))
                .filteredOn(f -> story.getId().equals(f.targetId()))
                .noneMatch(PublishFinding::blocks);
        assertThat(publishPreflightService.preflight(project.getId(), post.getId(), admin).blockers())
                .extracting(PublishFinding::code)
                .doesNotContain(MediaTargetValidator.STORY_SINGLE_ITEM, MediaTargetValidator.STORY_ITEM_COUNT);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private String assetIdFor(CreativeAttachService.AttachResult result, String placementKey) {
        return result.assets().stream().filter(a -> placementKey.equals(a.placementKey()))
                .findFirst().orElseThrow().assetId();
    }

    private List<String> storedAssetIds(String targetId) {
        return targetAssetRepository.findAllByTargetId(targetId).stream()
                .map(PostPublishTargetAsset::getAssetId)
                .toList();
    }

    private CreativeRender newRender() {
        CreativeRender render = new CreativeRender();
        render.setProjectId(project.getId());
        render.setCreativeId(creative.getId());
        render.setCreativeVersion(creative.getVersion());
        render.setState(CreativeRender.STATE_SUCCEEDED);
        render.setPreviewOnly(false);
        render.setRequestedBy(admin.getId());
        return renderRepository.save(render);
    }

    /** A placement frame — real renders are JPEG (see {@code CreativeRenderService}); only the
     * `sheet` contact sheet is PNG, which the one sheet fixture below passes explicitly. */
    private CreativeRenderFrame newFrame(CreativeRender render, String placementKey, Integer index, String platform,
                                         int width, int height) {
        return newFrame(render, placementKey, index, platform, width, height, "image/jpeg");
    }

    private CreativeRenderFrame newFrame(CreativeRender render, String placementKey, Integer index, String platform,
                                         int width, int height, String contentType) {
        String extension = "image/png".equals(contentType) ? "png" : "jpg";
        String gcsPath = "projects/" + project.getId() + "/creatives/" + creative.getId() + "/renders/"
                + render.getId() + "/" + placementKey + (index != null ? "-" + index : "") + "." + extension;
        byte[] bytes = "image/png".equals(contentType)
                ? new byte[]{(byte) 0x89, 'P', 'N', 'G'}
                : new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
        storageService.upload(gcsPath, bytes, contentType);

        CreativeRenderFrame frame = new CreativeRenderFrame();
        frame.setRenderId(render.getId());
        frame.setCreativeId(creative.getId());
        frame.setPlacementKey(placementKey);
        frame.setPlatform(platform);
        frame.setSequenceIndex(index);
        frame.setGcsPath(gcsPath);
        frame.setContentType(contentType);
        frame.setWidth(width);
        frame.setHeight(height);
        frame.setSizeBytes((long) bytes.length);
        frame.setWarnings(objectMapper.createArrayNode());
        return frameRepository.save(frame);
    }

    /** A video render frame (COND-24 PR1) — real CLIP frames are always video, with a duration set. */
    private CreativeRenderFrame newVideoFrame(CreativeRender render, String placementKey, String platform,
                                              int width, int height) {
        String gcsPath = "projects/" + project.getId() + "/creatives/" + creative.getId() + "/renders/"
                + render.getId() + "/" + placementKey + ".mp4";
        byte[] bytes = ("video-bytes-" + placementKey).getBytes();
        storageService.upload(gcsPath, bytes, "video/mp4");

        CreativeRenderFrame frame = new CreativeRenderFrame();
        frame.setRenderId(render.getId());
        frame.setCreativeId(creative.getId());
        frame.setPlacementKey(placementKey);
        frame.setPlatform(platform);
        frame.setGcsPath(gcsPath);
        frame.setContentType("video/mp4");
        frame.setWidth(width);
        frame.setHeight(height);
        frame.setSizeBytes((long) bytes.length);
        frame.setDurationSeconds(new java.math.BigDecimal("15"));
        frame.setHasAudio(true);
        frame.setWarnings(objectMapper.createArrayNode());
        return frameRepository.save(frame);
    }

    private Asset newExistingAsset() {
        Asset asset = new Asset();
        asset.setWorkItem(post);
        asset.setType("facebook_post");
        asset.setLabel("Existing custom media");
        asset.setKind(AssetService.KIND_FILE);
        asset.setRef("marketing-assets/" + project.getId() + "/" + post.getId() + "/existing.jpg");
        asset.setGcsPath("marketing-assets/" + project.getId() + "/" + post.getId() + "/existing.jpg");
        asset.setContentType("image/jpeg");
        asset.setSizeBytes(500L);
        asset.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        asset.setDone(true);
        return assetRepository.save(asset);
    }

    private Creative newCreative(BrandKit kit) {
        Creative c = new Creative();
        c.setProjectId(project.getId());
        c.setBrandKitId(kit.getId());
        c.setVariantLetter("a");
        c.setState(Creative.STATE_DRAFT);
        c.setLayout("stacked");
        c.setTheme(Creative.THEME_DARK);
        c.setHeadline("Plan the week in *one sentence*.");
        c.setPlacements(objectMapper.valueToTree(List.of()));
        c.setSequence(objectMapper.valueToTree(List.of()));
        c.setTypeOverrides(objectMapper.createObjectNode());
        c.setCreatedBy(admin.getId());
        c.setNumber(1);
        return creativeRepository.save(c);
    }

    private Connection connection(String connectorId, String configJson) {
        Connection connection = new Connection();
        connection.setProjectId(project.getId());
        connection.setConnectorId(connectorId);
        connection.setAuthType("OAUTH2");
        connection.setStatus("ACTIVE");
        connection.setConfigJson(configJson);
        connection.setVisibilityPolicy("{\"minRole\":\"REVIEWER\"}");
        return connection;
    }

    private User newUser() {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Attach Admin");
        return userRepository.save(user);
    }
}
