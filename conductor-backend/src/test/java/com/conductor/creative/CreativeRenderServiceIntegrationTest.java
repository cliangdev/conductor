package com.conductor.creative;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.ConflictException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.generated.v2.model.CreateCreativeRenderRequest;
import com.conductor.generated.v2.model.CreativeRenderResponse;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.StorageService;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CreativeRenderService} against a real database (COND-24 T3): the frame PUT/complete shape
 * (AC-P0-3.1), fail deleting every frame (AC-P0-3.2), a PUT refused once SUCCEEDED, the lazy
 * timeout-on-read sweep, and the spec's resolved placements for a story/carousel Creative.
 */
class CreativeRenderServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private CreativeRenderService renderService;
    @Autowired private CreativeRenderRepository renderRepository;
    @Autowired private CreativeRenderFrameRepository frameRepository;
    @Autowired private CreativeRepository creativeRepository;
    @Autowired private CreativePhotoRepository photoRepository;
    @Autowired private BrandKitService brandKitService;
    @Autowired private CreativeService creativeService;
    @Autowired private StorageService storageService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ObjectMapper objectMapper;

    private User admin;
    private Project project;
    private BrandKit defaultKit;
    private Creative creative;
    private int nextClipNumber = 100;

    @BeforeEach
    void setUp() {
        admin = newUser();
        project = new Project();
        project.setName("Render Test");
        project.setKey("RN" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(admin);
        project = projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(admin);
        membership.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(membership);

        defaultKit = brandKitService.resolveDefault(project.getId());
        CreativePhoto photo = newPhoto();
        creative = newCreative(photo);
    }

    // ── AC-P0-3.1: one frame per placement, with width/height ───────────────────────────────────

    @Test
    void requestingThenPuttingAndCompletingRecordsOneFramePerPlacementWithWidthAndHeight() {
        CreativeRenderService.CreateRenderResult result = renderService.requestRender(
                project.getId(), creative.getId(), new CreateCreativeRenderRequest(), admin);

        assertThat(result.render().getState()).isEqualTo(CreativeRender.STATE_RUNNING);
        assertThat(result.spec().getPlacements()).containsExactlyInAnyOrder("9x16", "4x5", "1x1");
        assertThat(result.spec().getRenderId()).isEqualTo(result.render().getId());
        assertThat(result.spec().getCreative().getHeadline()).isEqualTo(creative.getHeadline());

        String renderId = result.render().getId();
        renderService.putFrame(project.getId(), creative.getId(), renderId, "9x16", null, 1080, 1920,
                jpeg(), "image/jpeg", admin);
        renderService.putFrame(project.getId(), creative.getId(), renderId, "4x5", null, 1080, 1350,
                jpeg(), "image/jpeg", admin);
        renderService.putFrame(project.getId(), creative.getId(), renderId, "1x1", null, 1080, 1080,
                jpeg(), "image/jpeg", admin);

        CreativeRenderService.RenderView completed = renderService.completeRender(
                project.getId(), creative.getId(), renderId, null, admin);

        assertThat(completed.render().getState()).isEqualTo(CreativeRender.STATE_SUCCEEDED);
        assertThat(completed.frames()).hasSize(3);
        assertThat(completed.frames()).extracting(CreativeRenderFrame::getPlacementKey)
                .containsExactlyInAnyOrder("9x16", "4x5", "1x1");
        assertThat(completed.frames()).filteredOn(f -> "9x16".equals(f.getPlacementKey())).singleElement()
                .satisfies(f -> {
                    assertThat(f.getWidth()).isEqualTo(1080);
                    assertThat(f.getHeight()).isEqualTo(1920);
                    assertThat(f.getPlatform()).isEqualTo("tiktok");
                    assertThat(f.getContentType()).isEqualTo("image/jpeg");
                    assertThat(f.getGcsPath()).endsWith(".jpg");
                });
    }

    @Test
    void specCarriesLockupAndLayoutOverridesThroughToTheRenderJob() {
        creative.setLockup(Creative.LOCKUP_CHIP);
        creative.setLayoutOverrides(objectMapper.valueToTree(
                java.util.Map.of("band", java.util.Map.of("9x16", 1200), "padBottom", java.util.Map.of("9x16", 500))));
        creative = creativeRepository.save(creative);

        CreativeRenderService.CreateRenderResult result = renderService.requestRender(
                project.getId(), creative.getId(), new CreateCreativeRenderRequest(), admin);

        assertThat(result.spec().getCreative().getLockup().getValue()).isEqualTo("chip");
        assertThat(result.spec().getCreative().getLayoutOverrides().getBand()).containsEntry("9x16", 1200);
        assertThat(result.spec().getCreative().getLayoutOverrides().getPadBottom()).containsEntry("9x16", 500);
    }

    // ── frames are JPEG (PNG fails the Instagram feed/TikTok photo publishing gate) ──────────────

    @Test
    void aSheetContactSheetFrameStaysPngAndIsStoredWithAPngExtension() {
        String renderId = renderService.requestRender(project.getId(), creative.getId(),
                new CreateCreativeRenderRequest().previewOnly(true), admin).render().getId();

        renderService.putFrame(project.getId(), creative.getId(), renderId, "sheet", null, 1200, 1200,
                png(), "image/png", admin);

        CreativeRenderFrame frame = frameRepository.findAllByRenderId(renderId).get(0);
        assertThat(frame.getContentType()).isEqualTo("image/png");
        assertThat(frame.getGcsPath()).endsWith(".png");
    }

    @Test
    void puttingAFrameWithAnUnsupportedContentTypeIsRefusedWith422() {
        String renderId = renderService.requestRender(project.getId(), creative.getId(),
                new CreateCreativeRenderRequest(), admin).render().getId();

        assertThatThrownBy(() -> renderService.putFrame(project.getId(), creative.getId(), renderId,
                "4x5", null, 1080, 1350, jpeg(), "image/gif", admin))
                .isInstanceOf(UnprocessableEntityException.class);
        assertThat(frameRepository.findAllByRenderId(renderId)).isEmpty();
    }

    @Test
    void puttingTheSameFramePlacementTwiceUpsertsRatherThanDuplicating() {
        String renderId = renderService.requestRender(project.getId(), creative.getId(),
                new CreateCreativeRenderRequest(), admin).render().getId();

        renderService.putFrame(project.getId(), creative.getId(), renderId, "4x5", null, 1080, 1350, jpeg(), "image/jpeg", admin);
        renderService.putFrame(project.getId(), creative.getId(), renderId, "4x5", null, 1200, 1500, jpeg(), "image/jpeg", admin);

        List<CreativeRenderFrame> frames = frameRepository.findAllByRenderId(renderId);
        assertThat(frames).singleElement().satisfies(f -> {
            assertThat(f.getWidth()).isEqualTo(1200);
            assertThat(f.getHeight()).isEqualTo(1500);
        });
    }

    // ── AC-P0-3.2: fail deletes every frame already written ─────────────────────────────────────

    @Test
    void failingARenderDeletesEveryFrameAlreadyWritten() {
        String renderId = renderService.requestRender(project.getId(), creative.getId(),
                new CreateCreativeRenderRequest(), admin).render().getId();
        renderService.putFrame(project.getId(), creative.getId(), renderId, "4x5", null, 1080, 1350, jpeg(), "image/jpeg", admin);
        String gcsPath = frameRepository.findAllByRenderId(renderId).get(0).getGcsPath();

        com.conductor.generated.v2.model.FailCreativeRenderRequest failRequest =
                new com.conductor.generated.v2.model.FailCreativeRenderRequest("photo failed to load");
        CreativeRenderService.RenderView failed = renderService.failRender(
                project.getId(), creative.getId(), renderId, failRequest, admin);

        assertThat(failed.render().getState()).isEqualTo(CreativeRender.STATE_FAILED);
        assertThat(failed.render().getError()).isEqualTo("photo failed to load");
        assertThat(failed.frames()).isEmpty();
        assertThat(frameRepository.findAllByRenderId(renderId)).isEmpty();
        assertThatThrownBy(() -> storageService.download(gcsPath)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void failingARenderWhoseFramesLinkMediaLeavesTheMediaObject() {
        CreativePhoto media = newImageMedia(1080, 1350);
        String renderId = renderService.requestRender(project.getId(), creative.getId(),
                new CreateCreativeRenderRequest(), admin).render().getId();
        CreativeRenderFrame linked = linkedFrame(creative, renderId, media);

        renderService.failRender(project.getId(), creative.getId(), renderId,
                new com.conductor.generated.v2.model.FailCreativeRenderRequest("boom"), admin);

        assertThat(frameRepository.findById(linked.getId())).isEmpty();
        assertThat(storageService.download(media.getGcsPath())).isNotEmpty();
    }

    @Test
    void deletingACreativeWhoseCarouselFramesLinkMediaLeavesTheMediaObjects() {
        CreativePhoto a = newImageMedia(1080, 1350);
        CreativePhoto b = newImageMedia(1080, 1350);
        Creative carousel = newCarouselCreative(Map.of("default", List.of(a.getId(), b.getId())), List.of("4x5"));
        CreativeRenderService.CreateRenderResult result = renderService.requestRender(
                project.getId(), carousel.getId(), new CreateCreativeRenderRequest(), admin);
        assertThat(result.frames()).extracting(CreativeRenderFrame::getGcsPath)
                .containsExactly(a.getGcsPath(), b.getGcsPath());

        creativeService.deleteCreative(project.getId(), carousel.getId(), admin);

        assertThat(frameRepository.findAllByCreativeId(carousel.getId())).isEmpty();
        assertThat(storageService.download(a.getGcsPath())).isNotEmpty();
        assertThat(storageService.download(b.getGcsPath())).isNotEmpty();
    }

    @Test
    void deletingACreativeStillDeletesFrameObjectsNothingElseReferences() {
        String renderId = renderService.requestRender(project.getId(), creative.getId(),
                new CreateCreativeRenderRequest(), admin).render().getId();
        renderService.putFrame(project.getId(), creative.getId(), renderId, "4x5", null, 1080, 1350, jpeg(), "image/jpeg", admin);
        renderService.completeRender(project.getId(), creative.getId(), renderId, null, admin);
        String own = frameRepository.findAllByRenderId(renderId).get(0).getGcsPath();

        creativeService.deleteCreative(project.getId(), creative.getId(), admin);

        assertThatThrownBy(() -> storageService.download(own)).isInstanceOf(EntityNotFoundException.class);
    }

    private CreativeRenderFrame linkedFrame(Creative owner, String renderId, CreativePhoto media) {
        CreativeRenderFrame frame = new CreativeRenderFrame();
        frame.setRenderId(renderId);
        frame.setCreativeId(owner.getId());
        frame.setPlacementKey("4x5");
        frame.setGcsPath(media.getGcsPath());
        frame.setContentType(media.getContentType());
        frame.setWidth(1080);
        frame.setHeight(1350);
        frame.setSizeBytes(media.getSizeBytes());
        frame.setWarnings(objectMapper.createArrayNode());
        return frameRepository.save(frame);
    }

    // ── PUT after SUCCEEDED → 409 ────────────────────────────────────────────────────────────────

    @Test
    void puttingAFrameAfterTheRenderHasSucceededIsRefusedWith409() {
        String renderId = renderService.requestRender(project.getId(), creative.getId(),
                new CreateCreativeRenderRequest(), admin).render().getId();
        renderService.completeRender(project.getId(), creative.getId(), renderId, null, admin);

        assertThatThrownBy(() -> renderService.putFrame(project.getId(), creative.getId(), renderId,
                "4x5", null, 1080, 1350, jpeg(), "image/jpeg", admin))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void completingOrFailingATwiceSucceededRenderIsRefusedWith409() {
        String renderId = renderService.requestRender(project.getId(), creative.getId(),
                new CreateCreativeRenderRequest(), admin).render().getId();
        renderService.completeRender(project.getId(), creative.getId(), renderId, null, admin);

        assertThatThrownBy(() -> renderService.completeRender(project.getId(), creative.getId(), renderId, null, admin))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> renderService.failRender(project.getId(), creative.getId(), renderId,
                new com.conductor.generated.v2.model.FailCreativeRenderRequest("too late"), admin))
                .isInstanceOf(ConflictException.class);
    }

    // ── lazy timeout-on-read ─────────────────────────────────────────────────────────────────────

    @Test
    void aRunningRenderOlderThanThirtyMinutesIsReportedAndPersistedAsFailedOnRead() {
        String renderId = renderService.requestRender(project.getId(), creative.getId(),
                new CreateCreativeRenderRequest(), admin).render().getId();
        CreativeRender render = renderRepository.findById(renderId).orElseThrow();
        render.setRequestedAt(OffsetDateTime.now().minusMinutes(31));
        renderRepository.saveAndFlush(render);

        CreativeRenderService.RenderView read = renderService.getRender(project.getId(), creative.getId(), renderId, admin);

        assertThat(read.render().getState()).isEqualTo(CreativeRender.STATE_FAILED);
        assertThat(read.render().getError()).isEqualTo("timed out");
        // Persisted, not just reported: a fresh read must see the same FAILED state.
        assertThat(renderRepository.findById(renderId).orElseThrow().getState()).isEqualTo(CreativeRender.STATE_FAILED);
    }

    @Test
    void aRunningRenderUnderThirtyMinutesIsUntouchedOnRead() {
        String renderId = renderService.requestRender(project.getId(), creative.getId(),
                new CreateCreativeRenderRequest(), admin).render().getId();

        CreativeRenderService.RenderView read = renderService.getRender(project.getId(), creative.getId(), renderId, admin);

        assertThat(read.render().getState()).isEqualTo(CreativeRender.STATE_RUNNING);
    }

    // ── spec placements: story and carousel ─────────────────────────────────────────────────────

    @Test
    void aStoryCreativeResolvesToOnlyTheStoryPlacement() {
        Creative story = new Creative();
        story.setProjectId(project.getId());
        story.setBrandKitId(defaultKit.getId());
        story.setLayout("stacked");
        story.setTheme(Creative.THEME_DARK);
        story.setSequenceKind("story");
        story.setPlacements(objectMapper.valueToTree(List.of()));
        story.setSequence(objectMapper.valueToTree(List.of()));
        story.setTypeOverrides(objectMapper.createObjectNode());

        assertThat(renderService.resolvePlacements(defaultKit, story)).containsExactly("story");
    }

    @Test
    void aCarouselCreativeResolvesToOnlyItsCarouselRatioPlacement() {
        Creative carousel = new Creative();
        carousel.setProjectId(project.getId());
        carousel.setBrandKitId(defaultKit.getId());
        carousel.setLayout("stacked");
        carousel.setTheme(Creative.THEME_DARK);
        carousel.setSequenceKind("carousel");
        carousel.setCarouselRatio("4x5");
        carousel.setPlacements(objectMapper.valueToTree(List.of()));
        carousel.setSequence(objectMapper.valueToTree(List.of()));
        carousel.setTypeOverrides(objectMapper.createObjectNode());

        assertThat(renderService.resolvePlacements(defaultKit, carousel)).containsExactly("4x5");
    }

    @Test
    void aCarouselWithNoRatioSetRendersAt4x5() {
        Creative carousel = new Creative();
        carousel.setProjectId(project.getId());
        carousel.setBrandKitId(defaultKit.getId());
        carousel.setLayout("bleed");
        carousel.setTheme(Creative.THEME_DARK);
        carousel.setSequenceKind("carousel");
        carousel.setPlacements(objectMapper.valueToTree(List.of()));
        carousel.setSequence(objectMapper.valueToTree(List.of()));
        carousel.setTypeOverrides(objectMapper.createObjectNode());

        assertThat(renderService.resolvePlacements(defaultKit, carousel)).containsExactly("4x5");
    }

    @Test
    void aSinglePlacementCreativeResolvesToTheKitAndOptInUnionIntersectedWithTheRegistry() {
        Creative single = new Creative();
        single.setProjectId(project.getId());
        single.setBrandKitId(defaultKit.getId());
        single.setLayout("stacked");
        single.setTheme(Creative.THEME_DARK);
        // An opt-in beyond the kit's defaults ("story"), which the union must include.
        single.setPlacements(objectMapper.valueToTree(List.of("story")));
        single.setSequence(objectMapper.valueToTree(List.of()));
        single.setTypeOverrides(objectMapper.createObjectNode());

        assertThat(renderService.resolvePlacements(defaultKit, single))
                .containsExactlyInAnyOrder("9x16", "4x5", "1x1", "story");
    }

    // ── CLIP renders (COND-24 PR1) ──────────────────────────────────────────────────────────────

    @Test
    void aClipWithOnlyADefaultVideoAtSixteenByNinePicksThe16x9Placement() {
        CreativePhoto video = newVideoMedia(1920, 1080);
        Creative clip = newClipCreative(Map.of("default", video.getId()));

        CreativeRenderService.CreateRenderResult result = renderService.requestRender(
                project.getId(), clip.getId(), new CreateCreativeRenderRequest(), admin);

        assertThat(result.render().getState()).isEqualTo(CreativeRender.STATE_SUCCEEDED);
        assertThat(result.spec()).isNull();
        assertThat(result.frames()).extracting(CreativeRenderFrame::getPlacementKey).containsExactly("16x9");
    }

    @Test
    void aClipWithOnlyADefaultVideoAtNineBySixteenPicksThe9x16PlacementNotStory() {
        CreativePhoto video = newVideoMedia(1080, 1920);
        Creative clip = newClipCreative(Map.of("default", video.getId()));

        CreativeRenderService.CreateRenderResult result = renderService.requestRender(
                project.getId(), clip.getId(), new CreateCreativeRenderRequest(), admin);

        assertThat(result.frames()).extracting(CreativeRenderFrame::getPlacementKey).containsExactly("9x16");
    }

    @Test
    void anExplicitPlacementOverrideWinsOverTheDefaultsOwnNearestAspectPlacement() {
        CreativePhoto defaultVideo = newVideoMedia(1080, 1920); // nearest aspect is 9x16
        CreativePhoto override = newVideoMedia(1080, 1350);
        Creative clip = newClipCreative(Map.of("default", defaultVideo.getId(), "9x16", override.getId()));

        CreativeRenderService.CreateRenderResult result = renderService.requestRender(
                project.getId(), clip.getId(), new CreateCreativeRenderRequest(), admin);

        // Only one frame at "9x16" — the explicit override's media, not the default's (no duplicate).
        assertThat(result.frames()).hasSize(1);
        CreativeRenderFrame frame = result.frames().get(0);
        assertThat(frame.getPlacementKey()).isEqualTo("9x16");
        assertThat(frame.getWidth()).isEqualTo(1080);
        assertThat(frame.getHeight()).isEqualTo(1350);
        // The override's own bytes, not the default's.
        assertThat(storageService.download(frame.getGcsPath())).isEqualTo(storageService.download(override.getGcsPath()));
    }

    @Test
    void clipRenderLinksTheMediaObjectAndPosterWithoutCopyingAndCarriesDurationHasAudio() {
        CreativePhoto video = newVideoMedia(1080, 1920);
        video.setDurationSeconds(new BigDecimal("12.500"));
        video.setHasAudio(true);
        video.setPosterGcsPath("projects/" + project.getId() + "/marketing/photos/" + video.getId() + "-poster.jpg");
        storageService.upload(video.getPosterGcsPath(), jpeg(), "image/jpeg");
        video = photoRepository.save(video);
        Creative clip = newClipCreative(Map.of("default", video.getId()));

        CreativeRenderService.CreateRenderResult result = renderService.requestRender(
                project.getId(), clip.getId(), new CreateCreativeRenderRequest(), admin);

        CreativeRenderFrame frame = result.frames().get(0);
        assertThat(frame.getContentType()).isEqualTo("video/mp4");
        assertThat(frame.getDurationSeconds()).isEqualByComparingTo("12.500");
        assertThat(frame.getHasAudio()).isTrue();
        // Nothing is copied: the frame names the media's own object and poster.
        assertThat(frame.getPosterGcsPath()).isEqualTo(video.getPosterGcsPath());
        assertThat(frame.getGcsPath()).isEqualTo(video.getGcsPath());
        assertThat(frame.getSizeBytes()).isEqualTo(video.getSizeBytes());
        assertThat(frame.getWidth()).isEqualTo(1080);
        assertThat(frame.getHeight()).isEqualTo(1920);

        CreativeRenderResponse response = renderService.toResponse(new CreativeRenderService.RenderView(
                result.render(), result.frames()));
        assertThat(response.getFrames()).singleElement().satisfies(f -> {
            assertThat(f.getDurationSeconds()).isEqualByComparingTo("12.500");
            assertThat(f.getHasAudio()).isTrue();
            assertThat(f.getPosterUrl()).isNotNull();
            assertThat(f.getContentType()).isEqualTo("video/mp4");
        });

        // The library thumbnail is an <img>: for a video frame it is the poster, never the MP4.
        String thumbnail = renderService.thumbnailUrl(new CreativeRenderService.RenderView(result.render(), result.frames()));
        assertThat(thumbnail).contains(frame.getPosterGcsPath()).doesNotContain(".mp4");
    }

    @Test
    void previewOnlyIsRefusedForAClipRender() {
        CreativePhoto video = newVideoMedia(1080, 1920);
        Creative clip = newClipCreative(Map.of("default", video.getId()));

        assertThatThrownBy(() -> renderService.requestRender(project.getId(), clip.getId(),
                new CreateCreativeRenderRequest().previewOnly(true), admin))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void renderingAClipWithNoCaptionOrNoClipMediaIsRefused() {
        CreativePhoto video = newVideoMedia(1080, 1920);
        Creative noCaption = newClipCreative(Map.of("default", video.getId()));
        noCaption.setCaption(null);
        creativeRepository.save(noCaption);
        assertThatThrownBy(() -> renderService.requestRender(project.getId(), noCaption.getId(),
                new CreateCreativeRenderRequest(), admin))
                .isInstanceOf(CreativeValidationException.class);

        Creative noClip = newClipCreative(Map.of());
        assertThatThrownBy(() -> renderService.requestRender(project.getId(), noClip.getId(),
                new CreateCreativeRenderRequest(), admin))
                .isInstanceOf(CreativeValidationException.class);
    }

    // ── CAROUSEL renders (finished images copied, one frame per slide per placement) ───────────────

    private CreativePhoto newImageMedia(int width, int height) {
        String id = UUID.randomUUID().toString();
        CreativePhoto p = new CreativePhoto();
        p.setId(id);
        p.setProjectId(project.getId());
        String gcsPath = "projects/" + project.getId() + "/marketing/photos/" + id + ".jpg";
        p.setGcsPath(gcsPath);
        storageService.upload(gcsPath, ("slide-bytes-" + id).getBytes(), "image/jpeg");
        p.setContentType("image/jpeg");
        p.setSizeBytes(500_000L);
        p.setWidth(width);
        p.setHeight(height);
        p.setSource("own");
        p.setLicence("Own work");
        p.setChecked(true);
        p.setFocal(objectMapper.createObjectNode());
        p.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        p.setCreatedBy(admin.getId());
        return photoRepository.save(p);
    }

    private Creative newCarouselCreative(Map<String, List<String>> slides, List<String> placements) {
        Creative c = new Creative();
        c.setProjectId(project.getId());
        c.setBrandKitId(defaultKit.getId());
        c.setVariantLetter("a");
        c.setState(Creative.STATE_DRAFT);
        c.setLayout("stacked");
        c.setTheme(Creative.THEME_DARK);
        c.setKind(Creative.KIND_CAROUSEL);
        c.setCaption("Seven ways.");
        c.setSlides(objectMapper.valueToTree(slides));
        c.setPlacements(objectMapper.valueToTree(placements));
        c.setSequence(objectMapper.valueToTree(List.of()));
        c.setTypeOverrides(objectMapper.createObjectNode());
        c.setCreatedBy(admin.getId());
        c.setNumber(nextClipNumber++);
        return creativeRepository.save(c);
    }

    @Test
    void carouselRenderSucceedsAtOnceWithOneOrderedImageFramePerSlidePerPlacement() {
        List<CreativePhoto> portrait = List.of(newImageMedia(1080, 1350), newImageMedia(1080, 1350), newImageMedia(1080, 1350));
        List<CreativePhoto> tall = List.of(newImageMedia(1080, 1920), newImageMedia(1080, 1920));
        Map<String, List<String>> slides = new java.util.LinkedHashMap<>();
        slides.put("9x16", tall.stream().map(CreativePhoto::getId).toList());
        slides.put("4x5", portrait.stream().map(CreativePhoto::getId).toList());
        Creative carousel = newCarouselCreative(slides, List.of("9x16", "4x5"));

        CreativeRenderService.CreateRenderResult result = renderService.requestRender(
                project.getId(), carousel.getId(), new CreateCreativeRenderRequest(), admin);

        assertThat(result.render().getState()).isEqualTo(CreativeRender.STATE_SUCCEEDED);
        assertThat(result.spec()).isNull();
        assertThat(result.frames()).hasSize(5);
        List<CreativeRenderFrame> stored = frameRepository.findAllByRenderIdOrdered(result.render().getId());
        assertThat(stored).extracting(f -> f.getPlacementKey() + "#" + f.getSequenceIndex())
                .containsExactlyInAnyOrder("4x5#0", "4x5#1", "4x5#2", "9x16#0", "9x16#1");
        // Within one placement the frames are in slide order, and each links its slide's own object (no copy).
        List<CreativeRenderFrame> fourByFive = stored.stream().filter(f -> f.getPlacementKey().equals("4x5")).toList();
        for (int i = 0; i < 3; i++) {
            assertThat(fourByFive.get(i).getSequenceIndex()).isEqualTo(i);
            assertThat(fourByFive.get(i).getContentType()).isEqualTo("image/jpeg");
            assertThat(fourByFive.get(i).getWidth()).isEqualTo(1080);
            assertThat(fourByFive.get(i).getHeight()).isEqualTo(1350);
            assertThat(fourByFive.get(i).getGcsPath()).isEqualTo(portrait.get(i).getGcsPath());
            assertThat(fourByFive.get(i).getSizeBytes()).isEqualTo(portrait.get(i).getSizeBytes());
        }
        // Both placements of a slide share the one object: no new object was written for the render.
        assertThat(stored).extracting(CreativeRenderFrame::getGcsPath).doesNotContain(
                "projects/" + project.getId() + "/creatives/" + carousel.getId() + "/renders/" + result.render().getId() + "/4x5-01.jpg");

        // Thumbnail: slide 1 of 4x5 when present.
        CreativeRenderService.RenderView view = new CreativeRenderService.RenderView(result.render(), stored);
        assertThat(renderService.thumbnailUrl(view, List.of("9x16", "4x5"))).contains(fourByFive.get(0).getGcsPath());
    }

    @Test
    void carouselThumbnailFallsBackToSlideOneOfTheFirstPlacementWhenThereIsNoFourByFive() {
        CreativePhoto a = newImageMedia(1080, 1920);
        CreativePhoto b = newImageMedia(1080, 1080);
        Map<String, List<String>> slides = new java.util.LinkedHashMap<>();
        slides.put("9x16", List.of(a.getId()));
        slides.put("1x1", List.of(b.getId()));
        Creative carousel = newCarouselCreative(slides, List.of("9x16", "1x1"));

        CreativeRenderService.CreateRenderResult result = renderService.requestRender(
                project.getId(), carousel.getId(), new CreateCreativeRenderRequest(), admin);
        CreativeRenderFrame nineBySixteen = result.frames().stream()
                .filter(f -> f.getPlacementKey().equals("9x16")).findFirst().orElseThrow();
        List<CreativeRenderFrame> stored = frameRepository.findAllByRenderIdOrdered(result.render().getId());

        assertThat(renderService.thumbnailUrl(new CreativeRenderService.RenderView(result.render(), stored),
                List.of("9x16", "1x1"))).contains(nineBySixteen.getGcsPath());
    }

    @Test
    void aCarouselDefaultListServesEveryTargetedPlacementWithoutItsOwnList() {
        CreativePhoto a = newImageMedia(1080, 1350);
        Creative carousel = newCarouselCreative(Map.of("default", List.of(a.getId())), List.of("4x5"));

        CreativeRenderService.CreateRenderResult result = renderService.requestRender(
                project.getId(), carousel.getId(), new CreateCreativeRenderRequest(), admin);

        assertThat(result.frames()).singleElement().satisfies(f -> {
            assertThat(f.getPlacementKey()).isEqualTo("4x5");
            assertThat(f.getSequenceIndex()).isZero();
        });
    }

    @Test
    void previewOnlyIsRefusedForACarouselRender() {
        Creative carousel = newCarouselCreative(Map.of("4x5", List.of(newImageMedia(1080, 1350).getId())), List.of("4x5"));
        assertThatThrownBy(() -> renderService.requestRender(project.getId(), carousel.getId(),
                new CreateCreativeRenderRequest().previewOnly(true), admin))
                .isInstanceOf(UnprocessableEntityException.class);
    }

    @Test
    void renderingACarouselWithNoCaptionOrNoSlidesIsRefused() {
        Creative noCaption = newCarouselCreative(Map.of("4x5", List.of(newImageMedia(1080, 1350).getId())), List.of("4x5"));
        noCaption.setCaption(null);
        creativeRepository.save(noCaption);
        assertThatThrownBy(() -> renderService.requestRender(project.getId(), noCaption.getId(),
                new CreateCreativeRenderRequest(), admin)).isInstanceOf(CreativeValidationException.class);

        Creative noSlides = newCarouselCreative(Map.of(), List.of());
        assertThatThrownBy(() -> renderService.requestRender(project.getId(), noSlides.getId(),
                new CreateCreativeRenderRequest(), admin)).isInstanceOf(CreativeValidationException.class);
    }

    // ── MOTION renders (COND-24 PR2) ────────────────────────────────────────────────────────────

    @Test
    void motionRenderSpecCarriesKindMotionAndAudioWithSignedUrls() {
        CreativePhoto clip = newVideoMedia(1080, 1920);
        clip.setHasAudio(true);
        clip = photoRepository.save(clip);
        CreativePhoto track = newAudioMedia();
        Creative motion = newMotionCreative(clip.getId(), track.getId());

        CreativeRenderService.CreateRenderResult result = renderService.requestRender(
                project.getId(), motion.getId(), new CreateCreativeRenderRequest(), admin);

        assertThat(result.render().getState()).isEqualTo(CreativeRender.STATE_RUNNING);
        assertThat(result.spec().getCreative().getKind().getValue()).isEqualTo("MOTION");
        assertThat(result.spec().getCreative().getMotion().getPreset()).isEqualTo("fade-up");
        assertThat(result.spec().getCreative().getMotion().getBackground().getClipUrl()).isNotBlank();
        assertThat(result.spec().getCreative().getAudio().getSource()).isEqualTo("track");
        assertThat(result.spec().getCreative().getAudio().getTrackUrl()).isNotBlank();
        assertThat(result.spec().getCreative().getClipHasAudio()).isTrue();
        // Same placement resolution as STILL: kit-enabled union creative opt-ins, intersected with the registry.
        assertThat(result.spec().getPlacements()).containsExactlyInAnyOrder("9x16", "4x5", "1x1");
    }

    @Test
    void previewOnlyIsAllowedForAMotionRenderUnlikeClip() {
        Creative motion = newMotionCreative(null, null);

        CreativeRenderService.CreateRenderResult result = renderService.requestRender(project.getId(), motion.getId(),
                new CreateCreativeRenderRequest().previewOnly(true), admin);

        assertThat(result.render().isPreviewOnly()).isTrue();
        assertThat(result.render().getState()).isEqualTo(CreativeRender.STATE_RUNNING);
    }

    @Test
    void puttingAMotionFrameAcceptsVideoMp4WithDurationAndHasAudio() {
        Creative motion = newMotionCreative(null, null);
        String renderId = renderService.requestRender(project.getId(), motion.getId(),
                new CreateCreativeRenderRequest(), admin).render().getId();

        renderService.putFrame(project.getId(), motion.getId(), renderId, "9x16", null, 1080, 1920,
                mp4(), "video/mp4", new BigDecimal("8.000"), true, admin);

        CreativeRenderFrame frame = frameRepository.findAllByRenderId(renderId).get(0);
        assertThat(frame.getContentType()).isEqualTo("video/mp4");
        assertThat(frame.getGcsPath()).endsWith(".mp4");
        assertThat(frame.getDurationSeconds()).isEqualByComparingTo("8.000");
        assertThat(frame.getHasAudio()).isTrue();
    }

    @Test
    void puttingAMotionFramesPosterSetsItAndItIsRefusedAfterTheRenderSucceeds() {
        Creative motion = newMotionCreative(null, null);
        String renderId = renderService.requestRender(project.getId(), motion.getId(),
                new CreateCreativeRenderRequest(), admin).render().getId();
        renderService.putFrame(project.getId(), motion.getId(), renderId, "9x16", null, 1080, 1920,
                mp4(), "video/mp4", new BigDecimal("8"), true, admin);

        CreativeRenderService.RenderView withPoster = renderService.putFramePoster(
                project.getId(), motion.getId(), renderId, "9x16", null, jpeg(), "image/jpeg", admin);
        assertThat(withPoster.frames()).singleElement()
                .satisfies(f -> assertThat(f.getPosterGcsPath()).isNotNull());

        CreativeRenderResponse response = renderService.toResponse(withPoster);
        assertThat(response.getFrames()).singleElement().satisfies(f -> assertThat(f.getPosterUrl()).isNotNull());

        renderService.completeRender(project.getId(), motion.getId(), renderId, null, admin);

        assertThatThrownBy(() -> renderService.putFramePoster(project.getId(), motion.getId(), renderId, "9x16",
                null, jpeg(), "image/jpeg", admin))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void puttingAPosterWithNoFrameStoredYetIsRefused() {
        Creative motion = newMotionCreative(null, null);
        String renderId = renderService.requestRender(project.getId(), motion.getId(),
                new CreateCreativeRenderRequest(), admin).render().getId();

        assertThatThrownBy(() -> renderService.putFramePoster(project.getId(), motion.getId(), renderId, "9x16",
                null, jpeg(), "image/jpeg", admin))
                .isInstanceOf(com.conductor.exception.BusinessException.class);
    }

    // ── Music on CAROUSEL and CLIP: local renders ───────────────────────────────────────────────

    private Creative withAudio(Creative c, String source, String trackId, double volume) {
        com.fasterxml.jackson.databind.node.ObjectNode audio = objectMapper.createObjectNode();
        audio.put("source", source);
        if (trackId != null) {
            audio.put("trackId", trackId);
        }
        audio.put("volume", volume);
        audio.put("fadeOutSec", 1.0);
        c.setAudio(audio);
        return creativeRepository.save(c);
    }

    private Creative carouselWithTrack(CreativePhoto track, int slideCount) {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < slideCount; i++) {
            ids.add(newImageMedia(1080, 1920).getId());
        }
        Creative carousel = newCarouselCreative(Map.of("9x16", ids), List.of("9x16"));
        return withAudio(carousel, "track", track.getId(), 1.0);
    }

    @Test
    void aCarouselWithATrackIsARunningRenderWithTheSlideFramesLinkedAndTheSlideshowSpec() {
        CreativePhoto track = newAudioMedia();
        Creative carousel = carouselWithTrack(track, 3);

        CreativeRenderService.CreateRenderResult result = renderService.requestRender(
                project.getId(), carousel.getId(), new CreateCreativeRenderRequest(), admin);

        assertThat(result.render().getState()).isEqualTo(CreativeRender.STATE_RUNNING);
        assertThat(result.frames()).extracting(f -> f.getPlacementKey() + "#" + f.getSequenceIndex())
                .containsExactly("9x16#0", "9x16#1", "9x16#2");
        assertThat(result.spec().getCreative().getKind().getValue()).isEqualTo("CAROUSEL");
        assertThat(result.spec().getCreative().getSlides()).hasSize(3);
        assertThat(result.spec().getCreative().getSlideshow().getSlideDurations())
                .extracting(BigDecimal::doubleValue).containsExactly(3.0, 2.5, 2.5);
        assertThat(result.spec().getCreative().getAudio().getTrackUrl()).isNotBlank();
        assertThat(result.spec().getCreative().getAudio().getTargetLufs()).isEqualByComparingTo("-14");
        assertThat(result.spec().getPlacements()).hasSize(1);
    }

    @Test
    void theSlideshowVideoFrameWithNoIndexCoexistsWithTheSlidesRecordsTheTrackAndCompletes() {
        CreativePhoto track = newAudioMedia();
        Creative carousel = carouselWithTrack(track, 2);
        String renderId = renderService.requestRender(project.getId(), carousel.getId(),
                new CreateCreativeRenderRequest(), admin).render().getId();

        // Completing before the video is uploaded is refused: this carousel is rendered into a video.
        assertThatThrownBy(() -> renderService.completeRender(project.getId(), carousel.getId(), renderId, null, admin))
                .isInstanceOf(com.conductor.exception.UnprocessableEntityException.class);

        renderService.putFrame(project.getId(), carousel.getId(), renderId, "9x16", null, 1080, 1920,
                mp4(), "video/mp4", new BigDecimal("5.5"), true, admin);
        renderService.putFramePoster(project.getId(), carousel.getId(), renderId, "9x16", null, jpeg(), "image/jpeg", admin);
        CreativeRenderService.RenderView completed = renderService.completeRender(
                project.getId(), carousel.getId(), renderId, null, admin);

        assertThat(completed.render().getState()).isEqualTo(CreativeRender.STATE_SUCCEEDED);
        assertThat(completed.frames()).hasSize(3);
        CreativeRenderFrame video = completed.frames().stream()
                .filter(f -> f.getContentType().equals("video/mp4")).findFirst().orElseThrow();
        assertThat(video.getSequenceIndex()).isNull();
        assertThat(video.getAudioMediaId()).isEqualTo(track.getId());
        assertThat(video.getHasAudio()).isTrue();
        assertThat(completed.frames().stream().filter(f -> f.getContentType().equals("image/jpeg")))
                .allSatisfy(f -> assertThat(f.getAudioMediaId()).isNull());

        // renderedVideo exposes the mp4 for a CAROUSEL, and the thumbnail is a slide, never the video's poster.
        assertThat(renderService.renderedVideo(completed, Creative.KIND_CAROUSEL).getFrameId()).isEqualTo(video.getId());
        assertThat(renderService.renderedVideo(completed, Creative.KIND_STILL)).isNull();
        assertThat(renderService.thumbnailUrl(completed, List.of("9x16")))
                .contains(completed.frames().stream().filter(f -> f.getContentType().equals("image/jpeg"))
                        .findFirst().orElseThrow().getGcsPath());
    }

    @Test
    void aCarouselWithoutATrackStillRendersAtOnceAsBefore() {
        Creative carousel = newCarouselCreative(Map.of("4x5", List.of(newImageMedia(1080, 1350).getId())), List.of("4x5"));
        carousel = withAudio(carousel, "none", null, 1.0);

        CreativeRenderService.CreateRenderResult result = renderService.requestRender(
                project.getId(), carousel.getId(), new CreateCreativeRenderRequest(), admin);

        assertThat(result.render().getState()).isEqualTo(CreativeRender.STATE_SUCCEEDED);
        assertThat(result.spec()).isNull();
    }

    @Test
    void aClipWithNewSoundIsARunningRenderWithPlacementObjectsAndTheClipsOwnSoundStaysInstant() {
        CreativePhoto video = newVideoMedia(1080, 1920);
        video.setHasAudio(true);
        video = photoRepository.save(video);
        CreativePhoto track = newAudioMedia();

        Creative own = newClipCreative(Map.of("default", video.getId()));
        assertThat(renderService.requestRender(project.getId(), own.getId(), new CreateCreativeRenderRequest(), admin)
                .render().getState()).isEqualTo(CreativeRender.STATE_SUCCEEDED);

        Creative mixed = withAudio(newClipCreative(Map.of("default", video.getId())), "mix", track.getId(), 0.3);
        CreativeRenderService.CreateRenderResult result = renderService.requestRender(
                project.getId(), mixed.getId(), new CreateCreativeRenderRequest(), admin);

        assertThat(result.render().getState()).isEqualTo(CreativeRender.STATE_RUNNING);
        assertThat(result.frames()).isEmpty();
        assertThat(result.spec().getCreative().getAudio().getSource()).isEqualTo("mix");
        assertThat(result.spec().getCreative().getAudio().getTrackUrl()).isNotBlank();
        @SuppressWarnings("unchecked")
        Map<String, Object> placement = (Map<String, Object>) result.spec().getPlacements().get(0);
        assertThat(placement).containsEntry("key", "9x16").containsEntry("width", 1080).containsEntry("height", 1920)
                .containsEntry("clipHasAudio", true);
        assertThat(placement.get("clipUrl")).isNotNull();

        String renderId = result.render().getId();
        renderService.putFrame(project.getId(), mixed.getId(), renderId, "9x16", null, 1080, 1920,
                mp4(), "video/mp4", new BigDecimal("10"), true, admin);
        CreativeRenderService.RenderView done = renderService.completeRender(project.getId(), mixed.getId(), renderId, null, admin);
        assertThat(done.frames()).singleElement().satisfies(f -> assertThat(f.getAudioMediaId()).isEqualTo(track.getId()));
        assertThat(renderService.renderedVideo(done, Creative.KIND_CLIP)).isNotNull();
    }

    @Test
    void aMutedClipRendersLocallyWithNoTrackAndRecordsNoAudioMedia() {
        CreativePhoto video = newVideoMedia(1080, 1920);
        Creative muted = withAudio(newClipCreative(Map.of("default", video.getId())), "none", null, 1.0);

        CreativeRenderService.CreateRenderResult result = renderService.requestRender(
                project.getId(), muted.getId(), new CreateCreativeRenderRequest(), admin);
        assertThat(result.render().getState()).isEqualTo(CreativeRender.STATE_RUNNING);
        assertThat(result.spec().getCreative().getAudio().getTrackUrl()).isNull();

        renderService.putFrame(project.getId(), muted.getId(), result.render().getId(), "9x16", null, 1080, 1920,
                mp4(), "video/mp4", new BigDecimal("10"), false, admin);
        assertThat(frameRepository.findAllByRenderId(result.render().getId())).singleElement()
                .satisfies(f -> assertThat(f.getAudioMediaId()).isNull());
    }

    @Test
    void aMotionVideoFrameRecordsItsTrack() {
        CreativePhoto track = newAudioMedia();
        Creative motion = newMotionCreative(null, track.getId());
        String renderId = renderService.requestRender(project.getId(), motion.getId(),
                new CreateCreativeRenderRequest(), admin).render().getId();

        renderService.putFrame(project.getId(), motion.getId(), renderId, "9x16", null, 1080, 1920,
                mp4(), "video/mp4", new BigDecimal("8"), true, admin);

        assertThat(frameRepository.findAllByRenderId(renderId)).singleElement()
                .satisfies(f -> assertThat(f.getAudioMediaId()).isEqualTo(track.getId()));
    }

    // ── spec parity (draft-spec refactor): the persisted path's spec must not change ─────────────

    @Test
    void stillRenderSpecMatchesGolden() throws Exception {
        CreativePhoto hero = newPhoto();
        hero.setFocal(objectMapper.valueToTree(Map.of("x", "0.3", "y", "0.7")));
        hero = photoRepository.save(hero);
        Creative still = newCreative(hero);
        still.setCaption("Caption.");
        still.setLockup(Creative.LOCKUP_CHIP);
        still.setPlacements(objectMapper.valueToTree(List.of("story")));
        still.setFocalOverride(objectMapper.valueToTree(Map.of("1x1", "0.5 0.5")));
        still.setLayoutOverrides(objectMapper.valueToTree(Map.of("band", Map.of("9x16", 1200))));
        still = creativeRepository.save(still);

        assertSpecMatchesGolden("still", still, hero);
    }

    @Test
    void storyRenderSpecWithBeatPhotosMatchesGolden() throws Exception {
        CreativePhoto hero = newPhoto();
        hero.setFocal(objectMapper.valueToTree(Map.of("x", "0.3", "y", "0.7")));
        hero = photoRepository.save(hero);
        CreativePhoto beatPhoto = newPhoto();
        beatPhoto.setFocal(objectMapper.valueToTree(Map.of("x", "0.9", "y", "0.1")));
        beatPhoto = photoRepository.save(beatPhoto);
        Creative story = newCreative(hero);
        story.setSequenceKind("story");
        story.setSequence(objectMapper.valueToTree(List.of(
                Map.of("headline", "One *beat*", "body", "first"),
                Map.of("headline", "Two *beat*", "photoId", beatPhoto.getId()),
                Map.of("headline", "Three *beat*", "cta", true))));
        story = creativeRepository.save(story);

        assertSpecMatchesGolden("story", story, hero, beatPhoto);
    }

    @Test
    void motionRenderSpecMatchesGolden() throws Exception {
        CreativePhoto clip = newVideoMedia(1080, 1920);
        clip.setHasAudio(true);
        clip = photoRepository.save(clip);
        CreativePhoto track = newAudioMedia();
        Creative motion = newMotionCreative(clip.getId(), track.getId());

        assertSpecMatchesGolden("motion", motion, clip, track);
    }

    /** Compares the spec {@code requestRender} returns, structurally, against a checked-in golden file
     *  (ids normalised to placeholders). Run with {@code -Dgolden.update=true} to rewrite the golden. */
    private void assertSpecMatchesGolden(String name, Creative target, CreativePhoto... photos) throws Exception {
        CreativeRenderService.CreateRenderResult result = renderService.requestRender(
                project.getId(), target.getId(), new CreateCreativeRenderRequest(), admin);
        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result.spec());
        json = json.replace(result.render().getId(), "{RENDER}").replace(project.getId(), "{PROJECT}");
        for (int i = 0; i < photos.length; i++) {
            json = json.replace(photos[i].getId(), "{MEDIA" + i + "}");
        }
        java.nio.file.Path golden = java.nio.file.Path.of("src/test/resources/creative/spec-golden-" + name + ".json");
        if (Boolean.getBoolean("golden.update")) {
            java.nio.file.Files.createDirectories(golden.getParent());
            java.nio.file.Files.writeString(golden, json + "\n");
        }
        assertThat(objectMapper.readTree(json))
                .as("spec for %s must be unchanged by the spec-builder refactor", name)
                .isEqualTo(objectMapper.readTree(java.nio.file.Files.readString(golden)));
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private byte[] png() {
        // A minimal but nonempty payload; content is never interpreted server-side.
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    }

    private byte[] jpeg() {
        // A minimal but nonempty payload (the JPEG SOI marker); content is never interpreted server-side.
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
    }

    private byte[] mp4() {
        // A minimal but nonempty payload; content is never interpreted server-side.
        return ("mp4-bytes-" + UUID.randomUUID()).getBytes();
    }

    private Creative newCreative(CreativePhoto photo) {
        Creative c = new Creative();
        c.setProjectId(project.getId());
        c.setBrandKitId(defaultKit.getId());
        c.setVariantLetter("a");
        c.setState(Creative.STATE_DRAFT);
        c.setLayout("stacked");
        c.setTheme(Creative.THEME_DARK);
        c.setPhotoId(photo.getId());
        c.setHeadline("Plan the week in *one sentence*.");
        c.setBody("A calm plan.");
        c.setPlacements(objectMapper.valueToTree(List.of()));
        c.setSequence(objectMapper.valueToTree(List.of()));
        c.setTypeOverrides(objectMapper.createObjectNode());
        c.setCreatedBy(admin.getId());
        c.setNumber(nextClipNumber++);
        return creativeRepository.save(c);
    }

    private Creative newClipCreative(Map<String, String> clipMedia) {
        Creative c = new Creative();
        c.setProjectId(project.getId());
        c.setBrandKitId(defaultKit.getId());
        c.setVariantLetter("a");
        c.setState(Creative.STATE_DRAFT);
        c.setLayout("stacked");
        c.setTheme(Creative.THEME_DARK);
        c.setKind(Creative.KIND_CLIP);
        c.setCaption("Watch this.");
        c.setClipMedia(clipMedia.isEmpty() ? null : objectMapper.valueToTree(clipMedia));
        c.setPlacements(objectMapper.valueToTree(List.of()));
        c.setSequence(objectMapper.valueToTree(List.of()));
        c.setTypeOverrides(objectMapper.createObjectNode());
        c.setCreatedBy(admin.getId());
        c.setNumber(nextClipNumber++);
        return creativeRepository.save(c);
    }

    /**
     * A MOTION creative (COND-24 PR2): {@code clipMediaId}/{@code trackId} null means "photo background,
     * no audio track" (with a fresh uploaded photo so the READY-forced render validation passes); non-null
     * means "clip background"/"track audio" referencing that media id. Built directly against the entity
     * (like {@link #newClipCreative}), not through {@code CreativeService}, so defaults are set by hand
     * here exactly as {@code CreativeService#resolveMotion} would apply them.
     */
    private Creative newMotionCreative(String clipMediaId, String trackId) {
        Creative c = new Creative();
        c.setProjectId(project.getId());
        c.setBrandKitId(defaultKit.getId());
        c.setVariantLetter("a");
        c.setState(Creative.STATE_DRAFT);
        c.setLayout("stacked");
        c.setTheme(Creative.THEME_DARK);
        c.setKind(Creative.KIND_MOTION);
        c.setHeadline("Plan the week in *one sentence*.");
        c.setBody("A calm plan.");
        if (clipMediaId == null) {
            c.setPhotoId(newPhoto().getId());
        }
        c.setPlacements(objectMapper.valueToTree(List.of()));
        c.setSequence(objectMapper.valueToTree(List.of()));
        c.setTypeOverrides(objectMapper.createObjectNode());
        c.setCreatedBy(admin.getId());
        c.setNumber(nextClipNumber++);

        var background = objectMapper.createObjectNode();
        background.put("source", clipMediaId != null ? "clip" : "photo");
        background.put("motion", "zoom-in");
        if (clipMediaId != null) {
            background.put("clipMediaId", clipMediaId);
        }
        var motion = objectMapper.createObjectNode();
        motion.put("preset", "fade-up");
        motion.put("durationSec", 8);
        motion.set("background", background);
        motion.put("endCard", true);
        c.setMotion(motion);

        var audio = objectMapper.createObjectNode();
        audio.put("source", trackId != null ? "track" : "none");
        if (trackId != null) {
            audio.put("trackId", trackId);
        }
        audio.put("volume", 0.8);
        audio.put("fadeOutSec", 1.0);
        c.setAudio(audio);

        return creativeRepository.save(c);
    }

    private CreativePhoto newAudioMedia() {
        String id = UUID.randomUUID().toString();
        CreativePhoto p = new CreativePhoto();
        p.setId(id);
        p.setProjectId(project.getId());
        p.setMediaKind(CreativePhoto.MEDIA_KIND_AUDIO);
        String gcsPath = "projects/" + project.getId() + "/marketing/photos/" + id + ".mp3";
        p.setGcsPath(gcsPath);
        storageService.upload(gcsPath, ("track-bytes-" + id).getBytes(), "audio/mpeg");
        p.setContentType("audio/mpeg");
        p.setSizeBytes(500_000L);
        p.setDurationSeconds(new BigDecimal("30"));
        p.setSource("own");
        p.setLicence("Own work");
        p.setFocal(objectMapper.createObjectNode());
        p.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        p.setCreatedBy(admin.getId());
        return photoRepository.save(p);
    }

    private CreativePhoto newVideoMedia(int width, int height) {
        String id = UUID.randomUUID().toString();
        CreativePhoto p = new CreativePhoto();
        p.setId(id);
        p.setProjectId(project.getId());
        p.setMediaKind(CreativePhoto.MEDIA_KIND_VIDEO);
        String gcsPath = "projects/" + project.getId() + "/marketing/photos/" + id + ".mp4";
        p.setGcsPath(gcsPath);
        storageService.upload(gcsPath, ("clip-bytes-" + id).getBytes(), "video/mp4");
        p.setContentType("video/mp4");
        p.setSizeBytes(9_000_000L);
        p.setWidth(width);
        p.setHeight(height);
        p.setDurationSeconds(new BigDecimal("10"));
        p.setSource("own");
        p.setLicence("Own work");
        p.setFocal(objectMapper.createObjectNode());
        p.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        p.setCreatedBy(admin.getId());
        return photoRepository.save(p);
    }

    private CreativePhoto newPhoto() {
        CreativePhoto p = new CreativePhoto();
        p.setProjectId(project.getId());
        p.setGcsPath("projects/" + project.getId() + "/marketing/photos/test.jpg");
        p.setContentType("image/jpeg");
        p.setSizeBytes(1000L);
        p.setWidth(2400);
        p.setHeight(3000);
        p.setSource("own");
        p.setLicence("Own work");
        p.setChecked(true);
        p.setFocal(objectMapper.createObjectNode());
        p.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        p.setCreatedBy(admin.getId());
        return photoRepository.save(p);
    }

    private User newUser() {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Render Admin");
        return userRepository.save(user);
    }
}
