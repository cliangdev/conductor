package com.conductor.creative;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.ConflictException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.generated.v2.model.CreateCreativeRenderRequest;
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

import java.time.OffsetDateTime;
import java.util.List;
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
    @Autowired private StorageService storageService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private ObjectMapper objectMapper;

    private User admin;
    private Project project;
    private BrandKit defaultKit;
    private Creative creative;

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

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────

    private byte[] png() {
        // A minimal but nonempty payload; content is never interpreted server-side.
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    }

    private byte[] jpeg() {
        // A minimal but nonempty payload (the JPEG SOI marker); content is never interpreted server-side.
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
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
        c.setNumber(1);
        return creativeRepository.save(c);
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
