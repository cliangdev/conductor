package com.conductor.creative;

import com.conductor.entity.Asset;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.generated.v2.model.CreateCreativeRequest;
import com.conductor.generated.v2.model.CreateCreativePhotoRequest;
import com.conductor.generated.v2.model.PatchCreativePhotoRequest;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.AssetService;
import com.conductor.service.StorageService;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkflowSeeder;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one media library, end to end against a real database: a Work Item's file is a link to a Media item
 * (shared object, nothing copied) — the upload dual-write, add-from-media, the delete guard, the usage
 * lookups and counts, and the V146 backfill.
 */
class MediaLibraryRefsIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private AssetService assetService;
    @Autowired private CreativePhotoService photoService;
    @Autowired private CreativePhotoRepository photoRepository;
    @Autowired private CreativeService creativeService;
    @Autowired private CreativeRenderRepository renderRepository;
    @Autowired private CreativeRenderFrameRepository frameRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private WorkItemRepository workItemRepository;
    @Autowired private WorkItemService workItemService;
    @Autowired private WorkflowSeeder workflowSeeder;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private StorageService storageService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CreativeRepository creativeRepository;
    @Autowired private BrandKitService brandKitService;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Autowired private TransactionTemplate transactionTemplate;

    private User admin;
    private Project project;
    private WorkItem post;
    private String assetType;

    @BeforeEach
    void setUp() {
        admin = newUser();
        project = newProject(admin);
        workflowSeeder.seedMarketing(project);
        post = workItemService.createWorkItem(project.getId(), "POST", "Spring launch", "Caption", "MARKETING", admin);
        assetType = assetService.defaultAssetType(project.getId(), post);
    }

    // ── upload dual-write ────────────────────────────────────────────────────────────────────────

    @Test
    void anImageUploadCreatesBothRowsOnTheSameObjectAndConfirmMarksBothUploadedWithTheProbedShape() throws Exception {
        AssetService.FileAssetUploadTicket ticket = assetService.createFileAsset(project.getId(), post.getId(),
                new AssetService.FileAssetInput(assetType, null, "spring-card.png", "image/png", 10L), admin);
        Asset asset = ticket.asset();

        CreativePhoto media = photoRepository.findById(asset.getMediaId()).orElseThrow();
        assertThat(media.getGcsPath()).isEqualTo(asset.getGcsPath());
        assertThat(media.getProjectId()).isEqualTo(project.getId());
        assertThat(media.getUploadStatus()).isEqualTo(CreativePhoto.UPLOAD_STATUS_PENDING);
        assertThat(media.getMediaKind()).isEqualTo(CreativePhoto.MEDIA_KIND_IMAGE);
        assertThat(media.getLabel()).isEqualTo("spring-card");
        assertThat(media.getCreatedBy()).isEqualTo(admin.getId());

        byte[] png = pngBytes(40, 30);
        storageService.upload(asset.getGcsPath(), png, "image/png");
        assetService.confirmUpload(project.getId(), post.getId(), asset.getId(), (long) png.length, admin);

        Asset confirmed = assetRepository.findById(asset.getId()).orElseThrow();
        CreativePhoto confirmedMedia = photoRepository.findById(asset.getMediaId()).orElseThrow();
        assertThat(confirmed.getUploadStatus()).isEqualTo(AssetService.UPLOAD_STATUS_UPLOADED);
        assertThat(confirmedMedia.getUploadStatus()).isEqualTo(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        assertThat(confirmedMedia.getWidth()).isEqualTo(40);
        assertThat(confirmedMedia.getHeight()).isEqualTo(30);
        assertThat(confirmedMedia.getSizeBytes()).isEqualTo(png.length);
    }

    @Test
    void aVideoUploadCreatesVideoMediaAndAGifIsAllowedByThePostPolicy() {
        Asset video = assetService.createFileAsset(project.getId(), post.getId(), new AssetService.FileAssetInput(
                assetType, "Teaser", "teaser.mp4", "video/mp4", 5000L, 1080, 1920, new BigDecimal("12.5")), admin).asset();
        CreativePhoto media = photoRepository.findById(video.getMediaId()).orElseThrow();
        assertThat(media.getMediaKind()).isEqualTo(CreativePhoto.MEDIA_KIND_VIDEO);
        assertThat(media.getLabel()).isEqualTo("Teaser");
        assertThat(media.getWidth()).isEqualTo(1080);
        assertThat(media.getDurationSeconds()).isEqualByComparingTo("12.5");

        Asset gif = assetService.createFileAsset(project.getId(), post.getId(),
                new AssetService.FileAssetInput(assetType, null, "loop.gif", "image/gif", 100L), admin).asset();
        assertThat(photoRepository.findById(gif.getMediaId()).orElseThrow().getContentType()).isEqualTo("image/gif");
    }

    @Test
    void deletingAnAssetWhoseMediaIsStillPendingRemovesThatMediaButAnUploadedOneStays() {
        Asset pending = assetService.createFileAsset(project.getId(), post.getId(),
                new AssetService.FileAssetInput(assetType, null, "a.png", "image/png", 10L), admin).asset();
        String pendingMediaId = pending.getMediaId();

        assetService.deleteAsset(project.getId(), post.getId(), pending.getId(), admin);

        assertThat(assetRepository.findById(pending.getId())).isEmpty();
        assertThat(photoRepository.findById(pendingMediaId)).isEmpty();

        Asset uploaded = uploadedPostImage("b.png");
        assetService.deleteAsset(project.getId(), post.getId(), uploaded.getId(), admin);
        assertThat(photoRepository.findById(uploaded.getMediaId())).isPresent();
    }

    // ── add from media ───────────────────────────────────────────────────────────────────────────

    @Test
    void addFromMediaLinksTheItemsInRequestOrderOnTheSameObjectWithoutCopying() {
        CreativePhoto first = uploadedMedia("First");
        CreativePhoto second = uploadedMedia("Second");
        CreativePhoto third = uploadedMedia("Third");

        List<Asset> created = assetService.addFromMedia(project.getId(), post.getId(),
                List.of(third.getId(), first.getId(), second.getId()), admin);

        assertThat(created).extracting(Asset::getMediaId).containsExactly(third.getId(), first.getId(), second.getId());
        assertThat(created).extracting(Asset::getGcsPath)
                .containsExactly(third.getGcsPath(), first.getGcsPath(), second.getGcsPath());
        assertThat(created).extracting(Asset::getLabel).containsExactly("Third", "First", "Second");
        assertThat(created.get(0).getUploadStatus()).isEqualTo(AssetService.UPLOAD_STATUS_UPLOADED);
        assertThat(created.get(0).getType()).isEqualTo(assetType);
        assertThat(created.get(0).getWidth()).isEqualTo(2400);
        // The order the Post publishes in is the order asked for.
        List<Asset> persisted = assetRepository.findAllByWorkItemId(post.getId()).stream()
                .sorted(AssetService.PUBLISH_ORDER).toList();
        assertThat(persisted).extracting(Asset::getMediaId).containsExactly(third.getId(), first.getId(), second.getId());
    }

    @Test
    void addFromMediaRefusesATypeThePostUploadPolicyRefuses() {
        CreateCreativePhotoRequest webmRequest = new CreateCreativePhotoRequest();
        webmRequest.setLabel("Loop");
        webmRequest.setContentType(CreateCreativePhotoRequest.ContentTypeEnum.VIDEO_WEBM);
        webmRequest.setSizeBytes(100L);
        webmRequest.setWidth(1080);
        webmRequest.setHeight(1920);
        webmRequest.setDurationSeconds(new BigDecimal("5"));
        CreativePhoto webm = photoService.createPhoto(project.getId(), webmRequest, admin).photo();
        photoService.confirmPhoto(project.getId(), webm.getId(), null, admin);

        assertThatThrownBy(() -> assetService.addFromMedia(project.getId(), post.getId(), List.of(webm.getId()), admin))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Media \"Loop\" is video/webm, which a post can't carry");
        assertThat(assetRepository.findAllByWorkItemId(post.getId())).isEmpty();
    }

    @Test
    void confirmingAnAlreadyConfirmedAssetIsANoOpThatLeavesSharedMediaAlone() {
        CreativePhoto media = uploadedMedia("Shared");
        Asset linked = assetService.addFromMedia(project.getId(), post.getId(), List.of(media.getId()), admin).get(0);

        assetService.confirmUpload(project.getId(), post.getId(), linked.getId(), 7L, admin);

        CreativePhoto after = photoRepository.findById(media.getId()).orElseThrow();
        assertThat(after.getSizeBytes()).isEqualTo(media.getSizeBytes());
        assertThat(after.getWidth()).isEqualTo(media.getWidth());
        assertThat(assetRepository.findById(linked.getId()).orElseThrow().getSizeBytes()).isEqualTo(media.getSizeBytes());
    }

    @Test
    void addFromMediaRefusesBlockedPendingAudioOtherProjectUnknownAndRepeatedMedia() {
        CreativePhoto blocked = uploadedMedia("Blocked");
        PatchCreativePhotoRequest block = new PatchCreativePhotoRequest();
        block.setBlocked(true);
        photoService.patchPhoto(project.getId(), blocked.getId(), block, admin);

        CreateCreativePhotoRequest pendingRequest = new CreateCreativePhotoRequest();
        pendingRequest.setContentType(CreateCreativePhotoRequest.ContentTypeEnum.IMAGE_JPEG);
        pendingRequest.setSizeBytes(100L);
        pendingRequest.setWidth(100);
        pendingRequest.setHeight(100);
        CreativePhoto pending = photoService.createPhoto(project.getId(), pendingRequest, admin).photo();

        CreateCreativePhotoRequest audioRequest = new CreateCreativePhotoRequest();
        audioRequest.setContentType(CreateCreativePhotoRequest.ContentTypeEnum.AUDIO_MPEG);
        audioRequest.setSizeBytes(100L);
        audioRequest.setDurationSeconds(new BigDecimal("10"));
        CreativePhoto audio = photoService.createPhoto(project.getId(), audioRequest, admin).photo();
        photoService.confirmPhoto(project.getId(), audio.getId(), null, admin);

        User other = newUser();
        Project otherProject = newProject(other);
        CreativePhoto foreign = uploadedMedia(otherProject, other, "Foreign");
        CreativePhoto fine = uploadedMedia("Fine");

        for (String bad : List.of(blocked.getId(), pending.getId(), audio.getId(), foreign.getId(), "no-such-media")) {
            assertThatThrownBy(() -> assetService.addFromMedia(project.getId(), post.getId(), List.of(fine.getId(), bad), admin))
                    .as(bad).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> assetService.addFromMedia(project.getId(), post.getId(),
                List.of(fine.getId(), fine.getId()), admin)).isInstanceOf(BusinessException.class);
        // Nothing was added by any refused request.
        assertThat(assetRepository.findAllByWorkItemId(post.getId())).isEmpty();
    }

    @Test
    void addFromMediaOnALockedPostIsRefusedAndANonMemberGetsNotFound() {
        CreativePhoto media = uploadedMedia("Fine");
        post.setCurrentStatus("APPROVED");
        workItemRepository.save(post);

        assertThatThrownBy(() -> assetService.addFromMedia(project.getId(), post.getId(), List.of(media.getId()), admin))
                .isInstanceOf(BusinessException.class).hasMessageContaining("locked");

        User stranger = newUser();
        assertThatThrownBy(() -> assetService.addFromMedia(project.getId(), post.getId(), List.of(media.getId()), stranger))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── delete guard ─────────────────────────────────────────────────────────────────────────────

    @Test
    void deletingMediaLinkedToAPostIsRefusedNamingThePostAndWorksOnceUnlinked() {
        CreativePhoto media = uploadedMedia("Hero");
        Asset link = assetService.addFromMedia(project.getId(), post.getId(), List.of(media.getId()), admin).get(0);
        String displayId = project.getKey() + "-" + post.getSequenceNumber();

        assertThatThrownBy(() -> photoService.deletePhoto(project.getId(), media.getId(), admin))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(displayId)
                .hasMessageContaining("\"Spring launch\"")
                .hasMessageContaining("remove it from those posts first");
        assertThat(photoRepository.findById(media.getId())).isPresent();

        assetService.deleteAsset(project.getId(), post.getId(), link.getId(), admin);
        photoService.deletePhoto(project.getId(), media.getId(), admin);
        assertThat(photoRepository.findById(media.getId())).isEmpty();
    }

    @Test
    void deletingMediaRefusesWhenAPostUsesAStaleRenderFrameOnItsObjectAndCountsThatPost() {
        CreativePhoto media = uploadedMedia("Hero");
        CreativeRenderFrame frame = frameOnObjectOf(media);
        linkFrameToPost(frame);
        String displayId = project.getKey() + "-" + post.getSequenceNumber();

        assertThatThrownBy(() -> photoService.deletePhoto(project.getId(), media.getId(), admin))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("In use on " + displayId)
                .hasMessageContaining("\"Spring launch\"");
        assertThat(photoRepository.findById(media.getId())).isPresent();
        assertThat(photoService.getUsage(project.getId(), media.getId(), admin).posts())
                .extracting(CreativePhotoService.UsagePost::displayId).containsExactly(displayId);
        assertThat(photoService.getPhoto(project.getId(), media.getId(), admin).postCount()).isEqualTo(1);
    }

    @Test
    void deletingMediaWhoseObjectAStaleFrameStillNamesKeepsTheObject() {
        CreativePhoto media = uploadedMedia("Hero");
        storageService.upload(media.getGcsPath(), new byte[]{1, 2, 3}, "image/jpeg");
        CreativeRenderFrame frame = frameOnObjectOf(media);

        photoService.deletePhoto(project.getId(), media.getId(), admin);

        assertThat(photoRepository.findById(media.getId())).isEmpty();
        assertThat(frameRepository.findById(frame.getId())).isPresent();
        assertThat(storageService.download(media.getGcsPath())).containsExactly(1, 2, 3);
    }

    @Test
    void deletingUnreferencedMediaStillDeletesItsObject() {
        CreativePhoto media = uploadedMedia("Hero");
        storageService.upload(media.getGcsPath(), new byte[]{1, 2, 3}, "image/jpeg");

        photoService.deletePhoto(project.getId(), media.getId(), admin);

        assertThatThrownBy(() -> storageService.download(media.getGcsPath())).isInstanceOf(EntityNotFoundException.class);
    }

    private CreativeRenderFrame frameOnObjectOf(CreativePhoto media) {
        Creative c = new Creative();
        c.setProjectId(project.getId());
        c.setBrandKitId(brandKitService.resolveDefault(project.getId()).getId());
        c.setVariantLetter("a");
        c.setState(Creative.STATE_DRAFT);
        c.setLayout("stacked");
        c.setTheme(Creative.THEME_DARK);
        c.setKind(Creative.KIND_CAROUSEL);
        c.setPlacements(objectMapper.valueToTree(List.of("4x5")));
        c.setSequence(objectMapper.valueToTree(List.of()));
        c.setTypeOverrides(objectMapper.createObjectNode());
        c.setCreatedBy(admin.getId());
        c.setNumber(900 + (int) (System.nanoTime() % 1000));
        c = creativeRepository.save(c);
        CreativeRender render = new CreativeRender();
        render.setProjectId(project.getId());
        render.setCreativeId(c.getId());
        render.setCreativeVersion(c.getVersion());
        render.setState(CreativeRender.STATE_SUCCEEDED);
        render.setPreviewOnly(false);
        render.setRequestedBy(admin.getId());
        render = renderRepository.save(render);
        CreativeRenderFrame frame = new CreativeRenderFrame();
        frame.setRenderId(render.getId());
        frame.setCreativeId(c.getId());
        frame.setPlacementKey("4x5");
        frame.setGcsPath(media.getGcsPath());
        frame.setContentType("image/jpeg");
        frame.setWidth(1080);
        frame.setHeight(1350);
        frame.setSizeBytes(10L);
        frame.setWarnings(objectMapper.createArrayNode());
        return frameRepository.save(frame);
    }

    private void linkFrameToPost(CreativeRenderFrame frame) {
        Asset asset = new Asset();
        asset.setWorkItem(post);
        asset.setType(assetType);
        asset.setKind(AssetService.KIND_FILE);
        asset.setRef(frame.getGcsPath());
        asset.setGcsPath(frame.getGcsPath());
        asset.setContentType("image/jpeg");
        asset.setSizeBytes(10L);
        asset.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        asset.setCreativeFrameId(frame.getId());
        assetRepository.save(asset);
    }

    // ── a track used by CAROUSEL, CLIP and MOTION Creatives ─────────────────────────────────────────

    private CreativePhoto uploadedTrack() {
        CreateCreativePhotoRequest request = new CreateCreativePhotoRequest();
        request.setLabel("Track");
        request.setContentType(CreateCreativePhotoRequest.ContentTypeEnum.AUDIO_MPEG);
        request.setSizeBytes(100L);
        request.setDurationSeconds(new BigDecimal("30"));
        CreativePhoto audio = photoService.createPhoto(project.getId(), request, admin).photo();
        return photoService.confirmPhoto(project.getId(), audio.getId(), null, admin).photo();
    }

    private Creative creativeUsingTrack(String kind, String trackId, int number, String state) {
        Creative c = new Creative();
        c.setProjectId(project.getId());
        c.setBrandKitId(brandKitService.resolveDefault(project.getId()).getId());
        c.setVariantLetter("a");
        c.setState(state);
        c.setLayout("stacked");
        c.setTheme(Creative.THEME_DARK);
        c.setKind(kind);
        c.setPlacements(objectMapper.valueToTree(List.of()));
        c.setSequence(objectMapper.valueToTree(List.of()));
        c.setTypeOverrides(objectMapper.createObjectNode());
        c.setAudio(objectMapper.createObjectNode().put("source", "track").put("trackId", trackId));
        c.setCreatedBy(admin.getId());
        c.setNumber(number);
        return creativeRepository.save(c);
    }

    @Test
    void aTrackIsCountedAndProtectedForEveryKindOfCreativeThatPlaysIt() {
        CreativePhoto track = uploadedTrack();
        CreativePhoto unusedTrack = uploadedTrack();
        Creative carousel = creativeUsingTrack(Creative.KIND_CAROUSEL, track.getId(), 801, Creative.STATE_DRAFT);
        Creative clip = creativeUsingTrack(Creative.KIND_CLIP, track.getId(), 802, Creative.STATE_DRAFT);
        Creative motion = creativeUsingTrack(Creative.KIND_MOTION, track.getId(), 803, Creative.STATE_DRAFT);

        CreativePhotoService.PhotoView listed = view(photoService.listPhotos(project.getId(), false, null, admin), track);
        assertThat(listed.creativeCount()).isEqualTo(3);
        assertThat(listed.postCount()).isZero();
        assertThat(photoService.getPhoto(project.getId(), track.getId(), admin).creativeCount()).isEqualTo(3);
        assertThat(photoService.getUsage(project.getId(), track.getId(), admin).creatives())
                .extracting(CreativePhotoService.UsageCreative::id)
                .containsExactlyInAnyOrder(carousel.getId(), clip.getId(), motion.getId());
        assertThat(view(photoService.listPhotos(project.getId(), false, null, admin), unusedTrack).creativeCount()).isZero();

        assertThatThrownBy(() -> photoService.deletePhoto(project.getId(), track.getId(), admin))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(carousel.displayId()).hasMessageContaining(clip.displayId())
                .hasMessageContaining(motion.displayId());
        assertThat(photoRepository.findById(track.getId())).isPresent();
        photoService.deletePhoto(project.getId(), unusedTrack.getId(), admin);
        assertThat(photoRepository.findById(unusedTrack.getId())).isEmpty();
    }

    @Test
    void aTrackCannotBeBlockedWhileAReadyCreativePlaysIt() {
        CreativePhoto track = uploadedTrack();
        creativeUsingTrack(Creative.KIND_CAROUSEL, track.getId(), 804, Creative.STATE_READY);

        PatchCreativePhotoRequest block = new PatchCreativePhotoRequest();
        block.setBlocked(true);
        assertThatThrownBy(() -> photoService.patchPhoto(project.getId(), track.getId(), block, admin))
                .isInstanceOf(ConflictException.class).hasMessageContaining("READY");
    }

    @Test
    void aMotionsBackgroundClipIsNowCountedAndProtectedToo() {
        CreativePhoto clip = uploadedMedia("Background");
        Creative motion = creativeUsingTrack(Creative.KIND_MOTION, uploadedTrack().getId(), 805, Creative.STATE_DRAFT);
        motion.setMotion(objectMapper.createObjectNode().set("background",
                objectMapper.createObjectNode().put("source", "clip").put("clipMediaId", clip.getId())));
        creativeRepository.save(motion);

        assertThat(view(photoService.listPhotos(project.getId(), false, null, admin), clip).creativeCount()).isEqualTo(1);
        assertThatThrownBy(() -> photoService.deletePhoto(project.getId(), clip.getId(), admin))
                .isInstanceOf(ConflictException.class).hasMessageContaining(motion.displayId());
    }

    @Test
    void aTracksPostCountComesFromTheVideoFramesItIsMixedInto() {
        CreativePhoto track = uploadedTrack();
        Creative carousel = creativeUsingTrack(Creative.KIND_CAROUSEL, track.getId(), 806, Creative.STATE_DRAFT);
        CreativeRender render = new CreativeRender();
        render.setProjectId(project.getId());
        render.setCreativeId(carousel.getId());
        render.setCreativeVersion(carousel.getVersion());
        render.setState(CreativeRender.STATE_SUCCEEDED);
        render.setPreviewOnly(false);
        render.setRequestedBy(admin.getId());
        render = renderRepository.save(render);
        CreativeRenderFrame slide = new CreativeRenderFrame();
        slide.setRenderId(render.getId());
        slide.setCreativeId(carousel.getId());
        slide.setPlacementKey("9x16");
        slide.setSequenceIndex(0);
        slide.setGcsPath("projects/" + project.getId() + "/slide-0.jpg");
        slide.setContentType("image/jpeg");
        slide.setWidth(1080);
        slide.setHeight(1920);
        slide.setSizeBytes(10L);
        slide.setWarnings(objectMapper.createArrayNode());
        slide = frameRepository.save(slide);
        CreativeRenderFrame video = new CreativeRenderFrame();
        video.setRenderId(render.getId());
        video.setCreativeId(carousel.getId());
        video.setPlacementKey("9x16");
        video.setGcsPath("projects/" + project.getId() + "/video.mp4");
        video.setContentType("video/mp4");
        video.setWidth(1080);
        video.setHeight(1920);
        video.setSizeBytes(10L);
        video.setAudioMediaId(track.getId());
        video.setWarnings(objectMapper.createArrayNode());
        video = frameRepository.save(video);

        // A Post that only carries a slide does not play the track...
        linkFrameToPost(slide);
        assertThat(photoService.getPhoto(project.getId(), track.getId(), admin).postCount()).isZero();
        assertThat(view(photoService.listPhotos(project.getId(), false, null, admin), track).postCount()).isZero();

        // ...a Post that carries the video does.
        linkFrameToPost(video);
        assertThat(photoService.getPhoto(project.getId(), track.getId(), admin).postCount()).isEqualTo(1);
        assertThat(view(photoService.listPhotos(project.getId(), false, null, admin), track).postCount()).isEqualTo(1);
        assertThat(photoService.getUsage(project.getId(), track.getId(), admin).posts())
                .extracting(CreativePhotoService.UsagePost::displayId)
                .containsExactly(project.getKey() + "-" + post.getSequenceNumber());
    }

    // ── usage and counts ─────────────────────────────────────────────────────────────────────────

    @Test
    void usageAndCountsCountAPostOnceHoweverManyOfItsAssetsLinkTheMedia() {
        CreativePhoto used = uploadedMedia("Used");
        CreativePhoto unused = uploadedMedia("Unused");
        WorkItem second = workItemService.createWorkItem(project.getId(), "POST", "Second post", "c", "MARKETING", admin);
        assetService.addFromMedia(project.getId(), post.getId(), List.of(used.getId()), admin);
        assetService.addFromMedia(project.getId(), second.getId(), List.of(used.getId()), admin);
        // A second Asset on the same Post for the same media still counts that Post once.
        assetRepository.save(linkAsset(post, used));

        CreativePhotoService.MediaUsage usage = photoService.getUsage(project.getId(), used.getId(), admin);
        assertThat(usage.creatives()).isEmpty();
        assertThat(usage.posts()).extracting(CreativePhotoService.UsagePost::workItemId)
                .containsExactlyInAnyOrder(post.getId(), second.getId());
        CreativePhotoService.UsagePost row = usage.posts().stream()
                .filter(p -> p.workItemId().equals(post.getId())).findFirst().orElseThrow();
        assertThat(row.displayId()).isEqualTo(project.getKey() + "-" + post.getSequenceNumber());
        assertThat(row.title()).isEqualTo("Spring launch");
        assertThat(row.workflow()).isEqualTo("MARKETING");

        List<CreativePhotoService.PhotoView> listed = photoService.listPhotos(project.getId(), false, null, admin);
        assertThat(view(listed, used).postCount()).isEqualTo(2);
        assertThat(view(listed, used).creativeCount()).isZero();
        assertThat(view(listed, unused).postCount()).isZero();
        assertThat(view(listed, unused).creativeCount()).isZero();
        assertThat(photoService.getPhoto(project.getId(), used.getId(), admin).postCount()).isEqualTo(2);
    }

    @Test
    void creativeCountAndCreativesInUsageComeFromEveryReferencePath() {
        CreativePhoto photo = uploadedMedia("Photo");
        CreateCreativeRequest request = new CreateCreativeRequest();
        request.setHeadline("A hook.");
        request.setLayout("stacked");
        request.setPhotoId(photo.getId());
        CreativeService.CreativeView creative = creativeService.createCreative(project.getId(), request, admin);

        CreativePhotoService.MediaUsage usage = photoService.getUsage(project.getId(), photo.getId(), admin);
        assertThat(usage.creatives()).extracting(CreativePhotoService.UsageCreative::id)
                .containsExactly(creative.creative().getId());
        assertThat(usage.creatives().get(0).displayId()).isEqualTo(creative.creative().displayId());
        assertThat(usage.creatives().get(0).state()).isEqualTo(Creative.STATE_DRAFT);
        assertThat(view(photoService.listPhotos(project.getId(), false, null, admin), photo).creativeCount()).isEqualTo(1);
    }

    @Test
    void usageOfMediaInAnotherProjectIsNotFound() {
        User other = newUser();
        Project otherProject = newProject(other);
        CreativePhoto foreign = uploadedMedia(otherProject, other, "Foreign");

        assertThatThrownBy(() -> photoService.getUsage(project.getId(), foreign.getId(), admin))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> photoService.getUsage(otherProject.getId(), foreign.getId(), admin))
                .isInstanceOf(EntityNotFoundException.class);
    }

    // ── V146 backfill ────────────────────────────────────────────────────────────────────────────

    @Test
    void theBackfillGivesAnUploadedImageAssetAMediaRowAndSkipsFrameAndLinkAssets() throws Exception {
        Asset uploadedImage = rawAsset("file", "UPLOADED", "image/jpeg", "marketing-assets/p/w/backfill-a.jpg", null);
        Asset uploadedVideo = rawAsset("file", "UPLOADED", "video/mp4", "marketing-assets/p/w/backfill-b.mp4", null);
        Asset frameCopy = rawAsset("file", "UPLOADED", "image/jpeg", "marketing-assets/p/w/backfill-c.jpg", newFrameId());
        Asset pendingUpload = rawAsset("file", "PENDING", "image/jpeg", "marketing-assets/p/w/backfill-d.jpg", null);
        Asset link = rawAsset("link", null, null, null, null);

        runBackfillScopedTo(post.getId());

        Asset image = assetRepository.findById(uploadedImage.getId()).orElseThrow();
        CreativePhoto media = photoRepository.findById(image.getMediaId()).orElseThrow();
        assertThat(media.getGcsPath()).isEqualTo(image.getGcsPath());
        assertThat(media.getProjectId()).isEqualTo(project.getId());
        assertThat(media.getUploadStatus()).isEqualTo(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        assertThat(media.getMediaKind()).isEqualTo(CreativePhoto.MEDIA_KIND_IMAGE);
        assertThat(media.getWidth()).isEqualTo(1080);
        assertThat(media.getLabel()).isEqualTo("Label backfill-a.jpg");

        Asset video = assetRepository.findById(uploadedVideo.getId()).orElseThrow();
        assertThat(photoRepository.findById(video.getMediaId()).orElseThrow().getMediaKind())
                .isEqualTo(CreativePhoto.MEDIA_KIND_VIDEO);

        for (Asset skipped : List.of(frameCopy, pendingUpload, link)) {
            assertThat(assetRepository.findById(skipped.getId()).orElseThrow().getMediaId()).isNull();
        }
        assertThat(photoRepository.findAllByProjectIdOrderByCreatedAtDesc(project.getId()))
                .extracting(CreativePhoto::getLabel).contains("Label backfill-a.jpg", "Label backfill-b.mp4")
                .doesNotContain("Label backfill-c.jpg", "Label backfill-d.jpg");
    }

    /** Runs V146's backfill statements (the part after the DDL) over just this Work Item's Assets. */
    private void runBackfillScopedTo(String workItemId) throws Exception {
        String sql = new String(new ClassPathResource("db/migration/V146__asset_media_ref.sql").getInputStream()
                .readAllBytes(), StandardCharsets.UTF_8);
        String backfill = sql.substring(sql.indexOf("CREATE TEMP TABLE"));
        String scoped = backfill.replace("WHERE a.kind = 'file'",
                "WHERE a.work_item_id = '" + workItemId + "' AND a.kind = 'file'");
        assertThat(scoped).isNotEqualTo(backfill);
        transactionTemplate.executeWithoutResult(status -> {
            for (String statement : scoped.split(";")) {
                if (!statement.isBlank()) {
                    jdbcTemplate.execute(statement);
                }
            }
        });
    }

    private String newFrameId() {
        CreativePhoto photo = uploadedMedia("Frame source");
        CreateCreativeRequest request = new CreateCreativeRequest();
        request.setHeadline("A hook.");
        request.setLayout("stacked");
        request.setPhotoId(photo.getId());
        Creative creative = creativeService.createCreative(project.getId(), request, admin).creative();
        CreativeRender render = new CreativeRender();
        render.setProjectId(project.getId());
        render.setCreativeId(creative.getId());
        render.setCreativeVersion(creative.getVersion());
        render.setState(CreativeRender.STATE_SUCCEEDED);
        render.setPreviewOnly(false);
        render.setRequestedBy(admin.getId());
        render = renderRepository.save(render);
        CreativeRenderFrame frame = new CreativeRenderFrame();
        frame.setRenderId(render.getId());
        frame.setCreativeId(creative.getId());
        frame.setPlacementKey("4x5");
        frame.setGcsPath("projects/" + project.getId() + "/creatives/" + creative.getId() + "/f.jpg");
        frame.setContentType("image/jpeg");
        frame.setWidth(1080);
        frame.setHeight(1350);
        frame.setSizeBytes(10L);
        frame.setWarnings(new com.fasterxml.jackson.databind.ObjectMapper().createArrayNode());
        return frameRepository.save(frame).getId();
    }

    private Asset rawAsset(String kind, String uploadStatus, String contentType, String gcsPath, String frameId) {
        Asset asset = new Asset();
        asset.setWorkItem(post);
        asset.setType(assetType);
        asset.setKind(kind);
        asset.setLabel("Label " + (gcsPath != null ? gcsPath.substring(gcsPath.lastIndexOf('/') + 1) : "link"));
        asset.setRef(gcsPath != null ? gcsPath : "https://example.com/p");
        asset.setGcsPath(gcsPath);
        asset.setContentType(contentType);
        asset.setSizeBytes(contentType != null ? 500L : null);
        asset.setWidth(contentType != null ? 1080 : null);
        asset.setHeight(contentType != null ? 1350 : null);
        asset.setUploadStatus(uploadStatus);
        asset.setCreativeFrameId(frameId);
        asset.setDone(true);
        return assetRepository.save(asset);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private Asset linkAsset(WorkItem item, CreativePhoto media) {
        Asset asset = new Asset();
        asset.setWorkItem(item);
        asset.setType(assetType);
        asset.setKind(AssetService.KIND_FILE);
        asset.setLabel(media.getLabel());
        asset.setRef(media.getGcsPath());
        asset.setGcsPath(media.getGcsPath());
        asset.setContentType(media.getContentType());
        asset.setSizeBytes(media.getSizeBytes());
        asset.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        asset.setMediaId(media.getId());
        asset.setDone(true);
        return asset;
    }

    private CreativePhotoService.PhotoView view(List<CreativePhotoService.PhotoView> views, CreativePhoto photo) {
        return views.stream().filter(v -> v.photo().getId().equals(photo.getId())).findFirst().orElseThrow();
    }

    private Asset uploadedPostImage(String filename) {
        Asset asset = assetService.createFileAsset(project.getId(), post.getId(),
                new AssetService.FileAssetInput(assetType, null, filename, "image/png", 10L), admin).asset();
        assetService.confirmUpload(project.getId(), post.getId(), asset.getId(), 10L, admin);
        return assetRepository.findById(asset.getId()).orElseThrow();
    }

    private CreativePhoto uploadedMedia(String label) {
        return uploadedMedia(project, admin, label);
    }

    private CreativePhoto uploadedMedia(Project inProject, User by, String label) {
        CreateCreativePhotoRequest request = new CreateCreativePhotoRequest();
        request.setLabel(label);
        request.setContentType(CreateCreativePhotoRequest.ContentTypeEnum.IMAGE_JPEG);
        request.setSizeBytes(1000L);
        request.setWidth(2400);
        request.setHeight(3000);
        CreativePhotoService.PhotoView created = photoService.createPhoto(inProject.getId(), request, by);
        return photoService.confirmPhoto(inProject.getId(), created.photo().getId(), null, by).photo();
    }

    private static byte[] pngBytes(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private Project newProject(User owner) {
        Project p = new Project();
        p.setName("Media Refs");
        p.setKey("MR" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        p.setCreatedBy(owner);
        p = projectRepository.save(p);
        ProjectMember membership = new ProjectMember();
        membership.setProject(p);
        membership.setUser(owner);
        membership.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(membership);
        return p;
    }

    private User newUser() {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Media Refs User");
        return userRepository.save(user);
    }
}
