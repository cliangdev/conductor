package com.conductor.creative;

import com.conductor.entity.Asset;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.ConflictException;
import com.conductor.generated.v2.model.CopyRule;
import com.conductor.generated.v2.model.CopyRuleField;
import com.conductor.generated.v2.model.CreateBrandKitRequest;
import com.conductor.generated.v2.model.CreateCreativeRequest;
import com.conductor.generated.v2.model.SlideshowStyle;
import com.conductor.generated.v2.model.LocalMediaInfo;
import com.conductor.generated.v2.model.MediaKind;
import com.conductor.generated.v2.model.CreateCreativePhotoRequest;
import com.conductor.generated.v2.model.CreateCreativeVariantRequest;
import com.conductor.generated.v2.model.CreativeDraftSpecRequest;
import com.conductor.generated.v2.model.CreativeKind;
import com.conductor.generated.v2.model.LocalMediaInfo;
import com.conductor.generated.v2.model.MediaKind;
import com.conductor.generated.v2.model.PatchCreativeRequest;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.AssetService;
import com.conductor.service.StorageService;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkflowSeeder;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CreativeService} against a real database (COND-24 T2): number/letter assignment, the 409 on a
 * stale {@code version}, cutting a variant (AC-P0-2.3), and a Brand Kit copy rule's own message
 * surfacing on a 422 (AC-P0-2.1).
 */
class CreativeServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private CreativeService creativeService;
    @Autowired private CreativeRepository creativeRepository;
    @Autowired private CreativePhotoRepository photoRepository;
    @Autowired private CreativeRenderRepository renderRepository;
    @Autowired private CreativeRenderFrameRepository frameRepository;
    @Autowired private CreativeExperimentRepository experimentRepository;
    @Autowired private BrandKitService brandKitService;
    @Autowired private BrandKitRepository brandKitRepository;
    @Autowired private CreativeRenderService renderService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private AssetRepository assetRepository;
    @Autowired private StorageService storageService;
    @Autowired private WorkItemService workItemService;
    @Autowired private WorkflowSeeder workflowSeeder;
    @Autowired private ObjectMapper objectMapper;

    private User admin;
    private Project project;
    private BrandKit defaultKit;
    private CreativePhoto photo;

    @BeforeEach
    void setUp() {
        admin = newUser();
        project = new Project();
        project.setName("Creative Test");
        project.setKey("CR" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(admin);
        project = projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(admin);
        membership.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(membership);

        defaultKit = brandKitService.resolveDefault(project.getId());
        photo = newPhoto();
    }

    @Test
    void createAssignsNumberOneAndLetterAToTheFirstCreative() {
        CreativeService.CreativeView view = creativeService.createCreative(project.getId(),
                concept("Plan the week in *one sentence*.", "A calm plan."), admin);

        assertThat(view.creative().getNumber()).isEqualTo(1);
        assertThat(view.creative().getVariantLetter()).isEqualTo("a");
        assertThat(view.creative().displayId()).isEqualTo("1a");
        assertThat(view.creative().getState()).isEqualTo(Creative.STATE_DRAFT);
        assertThat(view.creative().getBrandKitId()).isEqualTo(defaultKit.getId());
    }

    @Test
    void patchingWithAStaleVersionIsRefusedWith409() {
        CreativeService.CreativeView created = creativeService.createCreative(project.getId(),
                concept("Plan the week in *one sentence*.", "A calm plan."), admin);
        String id = created.creative().getId();
        int originalVersion = created.creative().getVersion();

        PatchCreativeRequest firstPatch = new PatchCreativeRequest(originalVersion);
        firstPatch.setCaption("A caption.");
        creativeService.patchCreative(project.getId(), id, firstPatch, admin);

        // Reapplying a patch carrying the now-stale original version must 409.
        PatchCreativeRequest stalePatch = new PatchCreativeRequest(originalVersion);
        stalePatch.setCaption("Another caption.");
        assertThatThrownBy(() -> creativeService.patchCreative(project.getId(), id, stalePatch, admin))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void cuttingAVariantKeepsThePhotoLayoutAndBodyWithItsOwnHeadline() {
        CreativeService.CreativeView root = creativeService.createCreative(project.getId(),
                concept("The original *hook*.", "A calm plan for busy nights."), admin);

        CreateCreativeVariantRequest variantRequest = new CreateCreativeVariantRequest();
        variantRequest.setHeadline("A brand new *hook*.");
        CreativeService.CreativeView variant = creativeService.createVariant(project.getId(),
                root.creative().getId(), variantRequest, admin);

        assertThat(variant.creative().getNumber()).isEqualTo(root.creative().getNumber());
        assertThat(variant.creative().getVariantLetter()).isEqualTo("b");
        assertThat(variant.creative().displayId()).isEqualTo(root.creative().getNumber() + "b");
        assertThat(variant.creative().getParentCreativeId()).isEqualTo(root.creative().getId());
        assertThat(variant.creative().getPhotoId()).isEqualTo(root.creative().getPhotoId());
        assertThat(variant.creative().getLayout()).isEqualTo(root.creative().getLayout());
        assertThat(variant.creative().getBody()).isEqualTo(root.creative().getBody());
        assertThat(variant.creative().getHeadline()).isEqualTo("A brand new *hook*.");
        assertThat(variant.creative().getHeadline()).isNotEqualTo(root.creative().getHeadline());

        // A second variant on the same family gets the next free letter.
        CreativeService.CreativeView secondVariant = creativeService.createVariant(project.getId(),
                variant.creative().getId(), new CreateCreativeVariantRequest(), admin);
        assertThat(secondVariant.creative().getVariantLetter()).isEqualTo("c");
        assertThat(secondVariant.creative().getParentCreativeId()).isEqualTo(root.creative().getId());
    }

    @Test
    void cuttingAVariantWithNoNameCopiesTheSourcesName() {
        CreateCreativeRequest request = concept("The original *hook*.", "A calm plan for busy nights.");
        request.setName("Launch week hero");
        CreativeService.CreativeView root = creativeService.createCreative(project.getId(), request, admin);

        CreativeService.CreativeView variant = creativeService.createVariant(project.getId(),
                root.creative().getId(), new CreateCreativeVariantRequest(), admin);
        assertThat(variant.creative().getName()).isEqualTo("Launch week hero");

        // An explicit name on the request still wins over copying the source's.
        CreateCreativeVariantRequest namedVariantRequest = new CreateCreativeVariantRequest();
        namedVariantRequest.setName("Alt hero");
        CreativeService.CreativeView namedVariant = creativeService.createVariant(project.getId(),
                root.creative().getId(), namedVariantRequest, admin);
        assertThat(namedVariant.creative().getName()).isEqualTo("Alt hero");
    }

    @Test
    void readinessListsNoPhotoChosenOnceWhenThereIsNoPhoto() {
        CreateCreativeRequest request = concept("Plan the week in *one sentence*.", "A calm plan.");
        request.setPhotoId(null);
        CreativeService.CreativeView created = creativeService.createCreative(project.getId(), request, admin);

        CreativeService.Readiness readiness = creativeService.readiness(project.getId(), created.creative().getId(), admin);

        assertThat(readiness.items()).filteredOn(i -> i.message().equals("no photo chosen")).hasSize(1);
        assertThat(readiness.items()).extracting(CreativeService.ReadinessItem::key).doesNotContain("photoProvenance");
    }

    @Test
    void readinessChecksSourceAndLicenceOnceThereIsAPhoto() {
        CreativeService.CreativeView created = creativeService.createCreative(project.getId(),
                concept("Plan the week in *one sentence*.", "A calm plan."), admin);

        CreativeService.Readiness readiness = creativeService.readiness(project.getId(), created.creative().getId(), admin);

        assertThat(readiness.items()).extracting(CreativeService.ReadinessItem::key).contains("photoProvenance");
        assertThat(readiness.items().stream().filter(i -> i.key().equals("photoProvenance")).findFirst().orElseThrow().ok())
                .isTrue();
    }

    // ── CLIP (COND-24 PR1) ──────────────────────────────────────────────────────────────────────

    @Test
    void creatingAClipNeedsNoLayoutThemeHeadlineOrPhoto() {
        CreativePhoto video = newVideoPhoto();
        CreateCreativeRequest request = new CreateCreativeRequest();
        request.setKind(CreativeKind.CLIP);
        request.setCaption("Watch this.");
        request.setClipMedia(java.util.Map.of("default", video.getId()));

        CreativeService.CreativeView created = creativeService.createCreative(project.getId(), request, admin);

        assertThat(created.creative().getKind()).isEqualTo(Creative.KIND_CLIP);
        assertThat(created.creative().getPhotoId()).isNull();
        assertThat(created.creative().getHeadline()).isNull();
    }

    @Test
    void clipMediaReferencingNonVideoOrBlockedMediaIsRefused() {
        CreateCreativeRequest notVideo = new CreateCreativeRequest();
        notVideo.setKind(CreativeKind.CLIP);
        notVideo.setClipMedia(java.util.Map.of("default", photo.getId()));
        assertThatThrownBy(() -> creativeService.createCreative(project.getId(), notVideo, admin))
                .isInstanceOf(CreativeValidationException.class);

        CreativePhoto blockedVideo = newVideoPhoto();
        blockedVideo.setBlocked(true);
        photoRepository.save(blockedVideo);
        CreateCreativeRequest blocked = new CreateCreativeRequest();
        blocked.setKind(CreativeKind.CLIP);
        blocked.setClipMedia(java.util.Map.of("default", blockedVideo.getId()));
        assertThatThrownBy(() -> creativeService.createCreative(project.getId(), blocked, admin))
                .isInstanceOf(CreativeValidationException.class);
    }

    @Test
    void clipGoingReadyNeedsACaptionAndAtLeastOneClip() {
        CreativePhoto video = newVideoPhoto();
        CreateCreativeRequest noCaption = new CreateCreativeRequest();
        noCaption.setKind(CreativeKind.CLIP);
        noCaption.setState(com.conductor.generated.v2.model.CreativeState.READY);
        noCaption.setClipMedia(java.util.Map.of("default", video.getId()));
        assertThatThrownBy(() -> creativeService.createCreative(project.getId(), noCaption, admin))
                .isInstanceOf(CreativeValidationException.class);

        CreateCreativeRequest ready = new CreateCreativeRequest();
        ready.setKind(CreativeKind.CLIP);
        ready.setState(com.conductor.generated.v2.model.CreativeState.READY);
        ready.setCaption("Watch this.");
        ready.setClipMedia(java.util.Map.of("default", video.getId()));
        CreativeService.CreativeView created = creativeService.createCreative(project.getId(), ready, admin);
        assertThat(created.creative().getState()).isEqualTo(Creative.STATE_READY);
    }

    @Test
    void readinessForAClipChecksTheClipInsteadOfAPhoto() {
        CreativePhoto video = newVideoPhoto();
        CreateCreativeRequest request = new CreateCreativeRequest();
        request.setKind(CreativeKind.CLIP);
        request.setCaption("Watch this.");
        request.setClipMedia(java.util.Map.of("default", video.getId()));
        CreativeService.CreativeView created = creativeService.createCreative(project.getId(), request, admin);

        CreativeService.Readiness readiness = creativeService.readiness(project.getId(), created.creative().getId(), admin);

        assertThat(readiness.items()).extracting(CreativeService.ReadinessItem::key)
                .contains("clip", "mediaProvenance")
                .doesNotContain("photoChecked", "photoProvenance");
        // Alt text is advisory (non-blocking) for a video, unlike a STILL photo.
        assertThat(readiness.items().stream().filter(i -> i.key().equals("altText")).findFirst().orElseThrow().blocking())
                .isFalse();
    }

    // ── MOTION (COND-24 PR2) ─────────────────────────────────────────────────────────────────────

    @Test
    void creatingAMotionCreativeAppliesAllWriteTimeDefaults() {
        CreateCreativeRequest request = concept("Plan the week in *one sentence*.", "A calm plan.");
        request.setKind(CreativeKind.MOTION);

        CreativeService.CreativeView created = creativeService.createCreative(project.getId(), request, admin);

        assertThat(created.creative().getKind()).isEqualTo(Creative.KIND_MOTION);
        JsonNode motion = created.creative().getMotion();
        assertThat(motion.get("preset").asText()).isEqualTo("fade-up");
        assertThat(motion.get("durationSec").asDouble()).isEqualTo(8.0);
        assertThat(motion.get("background").get("source").asText()).isEqualTo("photo");
        assertThat(motion.get("background").get("motion").asText()).isEqualTo("zoom-in");
        assertThat(motion.get("endCard").asBoolean()).isTrue();

        JsonNode audio = created.creative().getAudio();
        assertThat(audio.get("source").asText()).isEqualTo("none");
        assertThat(audio.get("volume").asDouble()).isEqualTo(0.8);
        assertThat(audio.get("fadeOutSec").asDouble()).isEqualTo(1.0);
    }

    @Test
    void motionAudioDefaultsToClipWhenTheBackgroundIsAClipWithSound() {
        CreativePhoto video = newVideoPhoto();
        video.setHasAudio(true);
        photoRepository.save(video);

        CreateCreativeRequest request = concept("Plan the week in *one sentence*.", "A calm plan.");
        request.setKind(CreativeKind.MOTION);
        com.conductor.generated.v2.model.CreativeMotion motion = new com.conductor.generated.v2.model.CreativeMotion();
        com.conductor.generated.v2.model.CreativeMotionBackground background = new com.conductor.generated.v2.model.CreativeMotionBackground();
        background.setSource("clip");
        background.setClipMediaId(video.getId());
        motion.setBackground(background);
        request.setMotion(motion);

        CreativeService.CreativeView created = creativeService.createCreative(project.getId(), request, admin);

        assertThat(created.creative().getAudio().get("source").asText()).isEqualTo("clip");

        // A silent clip background defaults audio to "none" instead.
        CreativePhoto silentVideo = newVideoPhoto();
        CreateCreativeRequest silentRequest = concept("Plan the week in *one sentence*.", "A calm plan.");
        silentRequest.setKind(CreativeKind.MOTION);
        com.conductor.generated.v2.model.CreativeMotion silentMotion = new com.conductor.generated.v2.model.CreativeMotion();
        com.conductor.generated.v2.model.CreativeMotionBackground silentBackground = new com.conductor.generated.v2.model.CreativeMotionBackground();
        silentBackground.setSource("clip");
        silentBackground.setClipMediaId(silentVideo.getId());
        silentMotion.setBackground(silentBackground);
        silentRequest.setMotion(silentMotion);
        CreativeService.CreativeView silentCreated = creativeService.createCreative(project.getId(), silentRequest, admin);
        assertThat(silentCreated.creative().getAudio().get("source").asText()).isEqualTo("none");
    }

    @Test
    void motionWithAClipBackgroundNeedsNoPhotoGoingReadyButStillNeedsAHeadline() {
        CreativePhoto video = newVideoPhoto();
        CreateCreativeRequest request = new CreateCreativeRequest();
        request.setKind(CreativeKind.MOTION);
        request.setLayout("stacked");
        request.setHeadline("A calm *plan*.");
        request.setState(com.conductor.generated.v2.model.CreativeState.READY);
        com.conductor.generated.v2.model.CreativeMotion motion = new com.conductor.generated.v2.model.CreativeMotion();
        com.conductor.generated.v2.model.CreativeMotionBackground background = new com.conductor.generated.v2.model.CreativeMotionBackground();
        background.setSource("clip");
        background.setClipMediaId(video.getId());
        motion.setBackground(background);
        request.setMotion(motion);

        CreativeService.CreativeView created = creativeService.createCreative(project.getId(), request, admin);
        assertThat(created.creative().getState()).isEqualTo(Creative.STATE_READY);
        assertThat(created.creative().getPhotoId()).isNull();
    }

    @Test
    void motionBackgroundClipMustBeAnUploadedUnblockedVideoInTheProject() {
        CreateCreativeRequest notVideo = concept("Plan the week in *one sentence*.", "A calm plan.");
        notVideo.setKind(CreativeKind.MOTION);
        com.conductor.generated.v2.model.CreativeMotion motion = new com.conductor.generated.v2.model.CreativeMotion();
        com.conductor.generated.v2.model.CreativeMotionBackground background = new com.conductor.generated.v2.model.CreativeMotionBackground();
        background.setSource("clip");
        background.setClipMediaId(photo.getId());
        motion.setBackground(background);
        notVideo.setMotion(motion);

        assertThatThrownBy(() -> creativeService.createCreative(project.getId(), notVideo, admin))
                .isInstanceOf(CreativeValidationException.class);
    }

    @Test
    void readinessForMotionChecksBackgroundClipOrAudioTrackProvenance() {
        CreativePhoto video = newVideoPhoto();
        CreateCreativeRequest request = concept("Plan the week in *one sentence*.", "A calm plan.");
        request.setKind(CreativeKind.MOTION);
        com.conductor.generated.v2.model.CreativeMotion motion = new com.conductor.generated.v2.model.CreativeMotion();
        com.conductor.generated.v2.model.CreativeMotionBackground background = new com.conductor.generated.v2.model.CreativeMotionBackground();
        background.setSource("clip");
        background.setClipMediaId(video.getId());
        motion.setBackground(background);
        request.setMotion(motion);
        CreativeService.CreativeView created = creativeService.createCreative(project.getId(), request, admin);

        CreativeService.Readiness readiness = creativeService.readiness(project.getId(), created.creative().getId(), admin);

        assertThat(readiness.items()).extracting(CreativeService.ReadinessItem::key)
                .contains("clipProvenance")
                .doesNotContain("photoChecked", "photoProvenance");
        assertThat(readiness.items().stream().filter(i -> i.key().equals("clipProvenance")).findFirst().orElseThrow().ok())
                .isTrue();
        // Alt text is advisory (non-blocking) for MOTION, same as CLIP.
        assertThat(readiness.items().stream().filter(i -> i.key().equals("altText")).findFirst().orElseThrow().blocking())
                .isFalse();
    }

    // ── STILL is unchanged ───────────────────────────────────────────────────────────────────────

    @Test
    void aStillCreativesKindDefaultsToStillAndItsClipMediaStaysNull() {
        CreativeService.CreativeView created = creativeService.createCreative(project.getId(),
                concept("Plan the week in *one sentence*.", "A calm plan."), admin);

        assertThat(created.creative().getKind()).isEqualTo(Creative.KIND_STILL);
        assertThat(created.creative().getClipMedia()).isNull();
    }

    @Test
    void layoutOverridesAndLockupPersistAndCanBePatched() {
        CreateCreativeRequest request = concept("Plan the week in *one sentence*.", "A calm plan.");
        request.setLockup(com.conductor.generated.v2.model.CreativeLockup.CHIP);
        request.setLayoutOverrides(new com.conductor.generated.v2.model.CreativeLayoutOverrides()
                .band(java.util.Map.of("9x16", 1200))
                .padBottom(java.util.Map.of("9x16", 500)));

        CreativeService.CreativeView created = creativeService.createCreative(project.getId(), request, admin);
        assertThat(created.creative().getLockup()).isEqualTo("chip");
        assertThat(created.creative().getLayoutOverrides().get("band").get("9x16").asInt()).isEqualTo(1200);
        assertThat(created.creative().getLayoutOverrides().get("padBottom").get("9x16").asInt()).isEqualTo(500);

        PatchCreativeRequest patch = new PatchCreativeRequest(created.creative().getVersion());
        patch.setLockup(com.conductor.generated.v2.model.CreativeLockup.PLAIN);
        patch.setLayoutOverrides(new com.conductor.generated.v2.model.CreativeLayoutOverrides()
                .band(java.util.Map.of("4x5", 900)));
        CreativeService.CreativeView patched = creativeService.patchCreative(
                project.getId(), created.creative().getId(), patch, admin);
        assertThat(patched.creative().getLockup()).isEqualTo("plain");
        assertThat(patched.creative().getLayoutOverrides().get("band").get("4x5").asInt()).isEqualTo(900);

        CreativeService.CreativeView variant = creativeService.createVariant(
                project.getId(), patched.creative().getId(), new CreateCreativeVariantRequest(), admin);
        assertThat(variant.creative().getLockup()).isEqualTo("plain");
        assertThat(variant.creative().getLayoutOverrides().get("band").get("4x5").asInt()).isEqualTo(900);
    }

    @Test
    void layoutOverrideWithAnUnknownPlacementKeyIsRefused() {
        CreateCreativeRequest request = concept("Plan the week in *one sentence*.", "A calm plan.");
        request.setLayoutOverrides(new com.conductor.generated.v2.model.CreativeLayoutOverrides()
                .band(java.util.Map.of("not-a-placement", 1200)));

        assertThatThrownBy(() -> creativeService.createCreative(project.getId(), request, admin))
                .isInstanceOf(CreativeValidationException.class);
    }

    @Test
    void typeOverridesOnCreatePersistReachTheRenderSpecAndMatchTheDraftSpec() {
        java.util.Map<String, List<java.math.BigDecimal>> pinned = java.util.Map.of(
                "9x16", List.of(new java.math.BigDecimal("96"), new java.math.BigDecimal("1.0"), new java.math.BigDecimal("-2.9")),
                "1x1", List.of(new java.math.BigDecimal("88")));
        CreateCreativeRequest request = concept("Plan the week in *one sentence*.", "A calm plan.");
        request.setTypeOverrides(pinned);

        CreativeService.CreativeView created = creativeService.createCreative(project.getId(), request, admin);

        JsonNode stored = created.creative().getTypeOverrides();
        assertThat(stored.get("9x16").get(0).asInt()).isEqualTo(96);
        assertThat(stored.get("9x16").get(2).asDouble()).isEqualTo(-2.9);
        assertThat(stored.get("1x1").get(0).asInt()).isEqualTo(88);
        assertThat(creativeRepository.findById(created.creative().getId()).orElseThrow().getTypeOverrides().get("1x1").get(0).asInt())
                .isEqualTo(88);

        CreativeRenderService.CreateRenderResult render = renderService.requestRender(project.getId(),
                created.creative().getId(), new com.conductor.generated.v2.model.CreateCreativeRenderRequest(), admin);
        assertThat(render.spec().getCreative().getTypeOverrides()).containsOnlyKeys("9x16", "1x1");
        assertThat(render.spec().getCreative().getTypeOverrides().get("9x16").stream()
                .map(java.math.BigDecimal::doubleValue).toList()).containsExactly(96.0, 1.0, -2.9);

        CreativeService.DraftSpec draft = creativeService.buildDraftSpec(project.getId(),
                new CreativeDraftSpecRequest().headline("Plan the week in *one sentence*.").body("A calm plan.")
                        .photoId(photo.getId()).layout("stacked").typeOverrides(pinned), admin);
        JsonNode draftedOverrides = objectMapper.valueToTree(draft.spec().getCreative().getTypeOverrides());
        JsonNode renderedOverrides = objectMapper.valueToTree(render.spec().getCreative().getTypeOverrides());
        assertThat(draftedOverrides).isEqualTo(renderedOverrides);
    }

    @Test
    void aLaterHeadlineChangeStillClearsTypeOverridesPinnedOnCreate() {
        CreateCreativeRequest request = concept("Plan the week in *one sentence*.", "A calm plan.");
        request.setTypeOverrides(java.util.Map.of("9x16", List.of(new java.math.BigDecimal("96"))));
        CreativeService.CreativeView created = creativeService.createCreative(project.getId(), request, admin);

        PatchCreativeRequest unrelated = new PatchCreativeRequest(created.creative().getVersion());
        unrelated.setCaption("A caption.");
        CreativeService.CreativeView afterCaption = creativeService.patchCreative(
                project.getId(), created.creative().getId(), unrelated, admin);
        assertThat(afterCaption.creative().getTypeOverrides().has("9x16")).isTrue();

        PatchCreativeRequest headline = new PatchCreativeRequest(afterCaption.creative().getVersion());
        headline.setHeadline("A new *hook* entirely.");
        CreativeService.CreativeView afterHeadline = creativeService.patchCreative(
                project.getId(), created.creative().getId(), headline, admin);
        assertThat(afterHeadline.creative().getTypeOverrides().size()).isZero();
    }

    @Test
    void invalidTypeOverridesAreRefusedOnCreateDraftAndPatchAlike() {
        CreateCreativeRequest request = concept("Plan the week in *one sentence*.", "A calm plan.");
        request.setTypeOverrides(java.util.Map.of("9x16", List.of(new java.math.BigDecimal("0"))));
        assertThatThrownBy(() -> creativeService.createCreative(project.getId(), request, admin))
                .isInstanceOf(CreativeValidationException.class)
                .satisfies(e -> assertThat(((CreativeValidationException) e).violations())
                        .extracting(CreativeValidationException.Violation::field).containsExactly("typeOverrides"));

        assertThatThrownBy(() -> creativeService.buildDraftSpec(project.getId(),
                new CreativeDraftSpecRequest().headline("Hello").typeOverrides(
                        java.util.Map.of("not-a-placement", List.of(new java.math.BigDecimal("80")))), admin))
                .isInstanceOf(CreativeValidationException.class);

        CreativeService.CreativeView created = creativeService.createCreative(project.getId(),
                concept("Plan the week in *one sentence*.", "A calm plan."), admin);
        PatchCreativeRequest patch = new PatchCreativeRequest(created.creative().getVersion());
        patch.setTypeOverrides(java.util.Map.of("4x5", List.of()));
        assertThatThrownBy(() -> creativeService.patchCreative(project.getId(), created.creative().getId(), patch, admin))
                .isInstanceOf(CreativeValidationException.class);
    }

    @Test
    void aBrandKitCopyRuleFailureSurfacesTheRulesOwnMessage() {
        CopyRule noExclaim = new CopyRule("noExclaim", "!", "No exclamation marks.", List.of(CopyRuleField.HEADLINE));
        BrandKit kit = brandKitService.createKit(project.getId(),
                new CreateBrandKitRequest("strict", "Strict Kit").copyRules(List.of(noExclaim)), admin);

        CreateCreativeRequest request = concept("Dinner is ready!", "A calm plan.");
        request.setBrandKitId(kit.getId());

        assertThatThrownBy(() -> creativeService.createCreative(project.getId(), request, admin))
                .isInstanceOf(CreativeValidationException.class)
                .satisfies(e -> {
                    CreativeValidationException cve = (CreativeValidationException) e;
                    assertThat(cve.violations()).extracting(CreativeValidationException.Violation::message)
                            .contains("No exclamation marks.");
                });
    }

    // ── Delete ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void deletingACreativeRemovesItAndFreesItsNumberForReuse() {
        CreativeService.CreativeView created = creativeService.createCreative(project.getId(),
                concept("Plan the week in *one sentence*.", "A calm plan."), admin);
        String id = created.creative().getId();
        assertThat(created.creative().getNumber()).isEqualTo(1);

        creativeService.deleteCreative(project.getId(), id, admin);

        assertThat(creativeRepository.findById(id)).isEmpty();

        // No stored counter to reset: the next create re-reads MAX(number), which is now null again.
        CreativeService.CreativeView next = creativeService.createCreative(project.getId(),
                concept("A second concept.", "A calm plan."), admin);
        assertThat(next.creative().getNumber()).isEqualTo(1);
    }

    @Test
    void deletingAFamilyRootWithVariantsStillPresentIsRefused() {
        CreativeService.CreativeView root = creativeService.createCreative(project.getId(),
                concept("The original *hook*.", "A calm plan."), admin);
        CreativeService.CreativeView variant = creativeService.createVariant(project.getId(),
                root.creative().getId(), new CreateCreativeVariantRequest(), admin);

        assertThatThrownBy(() -> creativeService.deleteCreative(project.getId(), root.creative().getId(), admin))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Delete its variants first")
                .hasMessageContaining(variant.creative().displayId());

        // The variant itself carries no such restriction — deleting it clears the way for the root.
        creativeService.deleteCreative(project.getId(), variant.creative().getId(), admin);
        creativeService.deleteCreative(project.getId(), root.creative().getId(), admin);
        assertThat(creativeRepository.findById(root.creative().getId())).isEmpty();
    }

    @Test
    void deletingACreativeWithARenderFrameStillOnAPostIsRefused() {
        workflowSeeder.seedMarketing(project);
        WorkItem post = workItemService.createWorkItem(project.getId(), "POST", "Launch teaser", "Caption", "MARKETING", admin);
        CreativeService.CreativeView created = creativeService.createCreative(project.getId(),
                concept("The original *hook*.", "A calm plan."), admin);
        Creative c = created.creative();

        CreativeRenderFrame frame = newSucceededRenderWithFrame(c);
        Asset asset = new Asset();
        asset.setWorkItem(post);
        asset.setType("facebook_post");
        asset.setKind(AssetService.KIND_FILE);
        asset.setRef("marketing-assets/" + frame.getId());
        asset.setGcsPath("marketing-assets/" + frame.getId());
        asset.setContentType("image/jpeg");
        asset.setSizeBytes(500L);
        asset.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        asset.setCreativeFrameId(frame.getId());
        assetRepository.save(asset);

        assertThatThrownBy(() -> creativeService.deleteCreative(project.getId(), c.getId(), admin))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Remove it from the Post first")
                .hasMessageContaining(post.getProject().getKey() + "-" + post.getSequenceNumber());

        assertThat(creativeRepository.findById(c.getId())).isPresent();
    }

    @Test
    void deletingACreativeDeletesItsOwnExperimentsRendersAndFramesAndTheirStorageObjects() {
        CreativeService.CreativeView root = creativeService.createCreative(project.getId(),
                concept("The original *hook*.", "A calm plan."), admin);
        Creative c = root.creative();
        CreativeService.CreativeView variant = creativeService.createVariant(project.getId(),
                c.getId(), new CreateCreativeVariantRequest(), admin);

        CreativeExperiment experiment = new CreativeExperiment();
        experiment.setProjectId(project.getId());
        experiment.setParentCreativeId(c.getId());
        experiment.setMetric(CreativeExperiment.METRIC_VIEWS);
        experiment.setWindowHours(72);
        experiment.setState(CreativeExperiment.STATE_RUNNING);
        experiment.setCreatedBy(admin.getId());
        experiment = experimentRepository.save(experiment);
        String experimentId = experiment.getId();

        CreativeRenderFrame frame = newSucceededRenderWithFrame(c);
        String gcsPath = frame.getGcsPath();
        String renderId = frame.getRenderId();

        // The variant carries no restriction on the root's own delete once it is gone itself.
        creativeService.deleteCreative(project.getId(), variant.creative().getId(), admin);
        creativeService.deleteCreative(project.getId(), c.getId(), admin);

        assertThat(creativeRepository.findById(c.getId())).isEmpty();
        assertThat(experimentRepository.findById(experimentId)).isEmpty();
        assertThat(renderRepository.findById(renderId)).isEmpty();
        assertThat(frameRepository.findAllByCreativeId(c.getId())).isEmpty();
        assertThatThrownBy(() -> storageService.download(gcsPath)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void deletingACreativeThatIsAnExperimentsWinnerElsewhereNullsOutTheReferenceRatherThanDeletingTheExperiment() {
        CreativeService.CreativeView root = creativeService.createCreative(project.getId(),
                concept("The original *hook*.", "A calm plan."), admin);
        CreativeService.CreativeView variant = creativeService.createVariant(project.getId(),
                root.creative().getId(), new CreateCreativeVariantRequest(), admin);

        CreativeExperiment experiment = new CreativeExperiment();
        experiment.setProjectId(project.getId());
        experiment.setParentCreativeId(root.creative().getId());
        experiment.setMetric(CreativeExperiment.METRIC_VIEWS);
        experiment.setWindowHours(72);
        experiment.setState(CreativeExperiment.STATE_DECIDED);
        experiment.setWinnerCreativeId(variant.creative().getId());
        experiment.setCreatedBy(admin.getId());
        experiment = experimentRepository.save(experiment);
        String experimentId = experiment.getId();

        // The variant is the decided winner, but not the family root — deleting it is not blocked.
        creativeService.deleteCreative(project.getId(), variant.creative().getId(), admin);

        CreativeExperiment reloaded = experimentRepository.findById(experimentId).orElseThrow();
        assertThat(reloaded.getWinnerCreativeId()).isNull();
        assertThat(reloaded.getState()).isEqualTo(CreativeExperiment.STATE_DECIDED);
    }

    /** A SUCCEEDED render with one real, uploaded frame for {@code creative} — mirrors
     *  {@code CreativeAttachServiceIntegrationTest}'s own render/frame fixtures. */
    private CreativeRenderFrame newSucceededRenderWithFrame(Creative creative) {
        CreativeRender render = new CreativeRender();
        render.setProjectId(project.getId());
        render.setCreativeId(creative.getId());
        render.setCreativeVersion(creative.getVersion());
        render.setState(CreativeRender.STATE_SUCCEEDED);
        render.setPreviewOnly(false);
        render.setRequestedBy(admin.getId());
        render = renderRepository.save(render);

        String gcsPath = "projects/" + project.getId() + "/creatives/" + creative.getId() + "/renders/"
                + render.getId() + "/9x16.jpg";
        byte[] bytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
        storageService.upload(gcsPath, bytes, "image/jpeg");

        CreativeRenderFrame frame = new CreativeRenderFrame();
        frame.setRenderId(render.getId());
        frame.setCreativeId(creative.getId());
        frame.setPlacementKey("9x16");
        frame.setPlatform("tiktok");
        frame.setGcsPath(gcsPath);
        frame.setContentType("image/jpeg");
        frame.setWidth(1080);
        frame.setHeight(1920);
        frame.setSizeBytes((long) bytes.length);
        frame.setWarnings(objectMapper.createArrayNode());
        return frameRepository.save(frame);
    }

    // ── CAROUSEL (finished images used as-is) ────────────────────────────────────

    private CreativePhoto newImage(int width, int height, boolean checked) {
        CreativePhoto p = newPhoto();
        p.setWidth(width);
        p.setHeight(height);
        p.setChecked(checked);
        return photoRepository.save(p);
    }

    private List<String> imageIds(int count, int width, int height, boolean checked) {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(newImage(width, height, checked).getId());
        }
        return ids;
    }

    private CreateCreativeRequest carouselRequest(Map<String, List<String>> slides) {
        CreateCreativeRequest request = new CreateCreativeRequest();
        request.setKind(CreativeKind.CAROUSEL);
        request.setCaption("Seven ways to save recipes.");
        request.setAltText("Recipe cards.");
        request.setSlides(slides);
        return request;
    }

    @Test
    void aCarouselRecordsItsSlideStyleAndPatchKeepsOrReplacesIt() {
        CreateCreativeRequest request = carouselRequest(Map.of("default", imageIds(2, 1080, 1920, true)));
        request.setSlideStyle(new SlideshowStyle().fontFamily("Poppins").accent("#FF5A5F").defaultText("box").textSize(60));
        Creative created = creativeService.createCreative(project.getId(), request, admin).creative();
        assertThat(created.getSlideStyle().get("fontFamily").asText()).isEqualTo("Poppins");
        assertThat(created.getSlideStyle().get("textSize").asInt()).isEqualTo(60);

        PatchCreativeRequest keep = new PatchCreativeRequest();
        keep.setVersion(created.getVersion());
        keep.setCaption("Changed.");
        Creative kept = creativeService.patchCreative(project.getId(), created.getId(), keep, admin).creative();
        assertThat(kept.getSlideStyle().get("accent").asText()).isEqualTo("#FF5A5F");

        PatchCreativeRequest replace = new PatchCreativeRequest();
        replace.setVersion(kept.getVersion());
        replace.setSlideStyle(new SlideshowStyle().defaultText("dark"));
        Creative replaced = creativeService.patchCreative(project.getId(), created.getId(), replace, admin).creative();
        assertThat(replaced.getSlideStyle().has("fontFamily")).isFalse();
        assertThat(replaced.getSlideStyle().get("defaultText").asText()).isEqualTo("dark");
    }

    @Test
    void aSlideStyleOnANonCarouselIsRefusedWith422() {
        CreateCreativeRequest request = new CreateCreativeRequest();
        request.setHeadline("Hello");
        request.setSlideStyle(new SlideshowStyle().accent("#FF5A5F"));
        assertThatThrownBy(() -> creativeService.createCreative(project.getId(), request, admin))
                .isInstanceOf(CreativeValidationException.class)
                .satisfies(e -> assertThat(((CreativeValidationException) e).violations())
                        .extracting(CreativeValidationException.Violation::field).contains("slideStyle"));
    }

    @Test
    void anInvalidSlideStyleNamesTheBadFieldsWith422() {
        CreateCreativeRequest request = carouselRequest(Map.of("default", imageIds(2, 1080, 1920, true)));
        request.setSlideStyle(new SlideshowStyle().outline("blue").defaultText("neon").subSize(5).fontUrl("javascript:alert(1)"));
        assertThatThrownBy(() -> creativeService.createCreative(project.getId(), request, admin))
                .isInstanceOf(CreativeValidationException.class)
                .satisfies(e -> assertThat(((CreativeValidationException) e).violations())
                        .extracting(CreativeValidationException.Violation::field)
                        .containsExactlyInAnyOrder("slideStyle.outline", "slideStyle.defaultText",
                                "slideStyle.subSize", "slideStyle.fontUrl"));
    }

    @Test
    void creatingACarouselDerivesPlacementsFromTheSlideKeysAndGoesReady() {
        Map<String, List<String>> slides = new java.util.LinkedHashMap<>();
        slides.put("4x5", imageIds(3, 1080, 1350, true));
        slides.put("9x16", imageIds(3, 1080, 1920, true));
        CreateCreativeRequest request = carouselRequest(slides);
        request.setState(com.conductor.generated.v2.model.CreativeState.READY);

        CreativeService.CreativeView created = creativeService.createCreative(project.getId(), request, admin);

        Creative creative = created.creative();
        assertThat(creative.getKind()).isEqualTo(Creative.KIND_CAROUSEL);
        assertThat(creative.getState()).isEqualTo(Creative.STATE_READY);
        assertThat(creative.getPlacements()).extracting(JsonNode::asText).containsExactly("4x5", "9x16");
        assertThat(creative.getSlides().get("4x5")).hasSize(3);
        assertThat(creative.getPhotoId()).isNull();
        assertThat(creativeService.readiness(project.getId(), creative.getId(), admin).ready()).isTrue();
    }

    @Test
    void aCarouselWithOnlyADefaultListTargetsThePlacementNearestTheFirstImage() {
        CreativeService.CreativeView square = creativeService.createCreative(project.getId(),
                carouselRequest(Map.of("default", imageIds(2, 1080, 1080, true))), admin);
        assertThat(square.creative().getPlacements()).extracting(JsonNode::asText).containsExactly("1x1");

        CreativeService.CreativeView portrait = creativeService.createCreative(project.getId(),
                carouselRequest(Map.of("default", imageIds(2, 1080, 1350, true))), admin);
        assertThat(portrait.creative().getPlacements()).extracting(JsonNode::asText).containsExactly("4x5");
    }

    @Test
    void aCarouselWithAWrongShapedSlideIsRefusedWithSlideAspect() {
        CreateCreativeRequest request = carouselRequest(Map.of("4x5", imageIds(1, 1080, 1080, true)));
        assertThatThrownBy(() -> creativeService.createCreative(project.getId(), request, admin))
                .isInstanceOf(CreativeValidationException.class)
                .satisfies(e -> assertThat(((CreativeValidationException) e).violations())
                        .extracting(CreativeValidationException.Violation::ruleId).containsExactly("slideAspect"));
    }

    @Test
    void carouselReadinessChecksEverySlideAcrossAllPlacementsAndAcceptsPerSlideAltText() {
        Map<String, List<String>> slides = new java.util.LinkedHashMap<>();
        slides.put("4x5", imageIds(2, 1080, 1350, true));
        CreativePhoto unchecked = newImage(1080, 1920, false);
        unchecked.setAiGenerated(true);
        photoRepository.save(unchecked);
        slides.put("9x16", List.of(unchecked.getId()));
        CreateCreativeRequest request = carouselRequest(slides);
        request.setAltText(null);
        CreativeService.CreativeView created = creativeService.createCreative(project.getId(), request, admin);

        CreativeService.Readiness readiness = creativeService.readiness(project.getId(), created.creative().getId(), admin);
        assertThat(readiness.items()).extracting(CreativeService.ReadinessItem::key)
                .containsExactly("slides", "caption", "altText", "photoChecked", "mediaProvenance", "aiDisclosure", "state");
        assertThat(item(readiness, "photoChecked").ok()).isFalse();
        assertThat(item(readiness, "altText").ok()).isFalse();
        assertThat(item(readiness, "aiDisclosure").ok()).isFalse();
        assertThat(item(readiness, "aiDisclosure").blocking()).isFalse();
        assertThat(item(readiness, "slides").ok()).isTrue();

        PatchCreativeRequest patch = new PatchCreativeRequest(created.creative().getVersion());
        patch.setSlideAltText(List.of("One", "Two"));
        CreativeService.CreativeView patched = creativeService.patchCreative(project.getId(), created.creative().getId(), patch, admin);
        assertThat(item(creativeService.readiness(project.getId(), patched.creative().getId(), admin), "altText").ok()).isTrue();
    }

    // ── Music on CAROUSEL and CLIP ───────────────────────────────────────────────────────────────

    private CreativePhoto newTrack(boolean withProvenance) {
        CreativePhoto p = new CreativePhoto();
        p.setProjectId(project.getId());
        p.setGcsPath("projects/" + project.getId() + "/marketing/photos/" + UUID.randomUUID() + ".mp3");
        p.setContentType("audio/mpeg");
        p.setMediaKind(CreativePhoto.MEDIA_KIND_AUDIO);
        p.setSizeBytes(500_000L);
        p.setDurationSeconds(new java.math.BigDecimal("30"));
        if (withProvenance) {
            p.setSource("Pixabay");
            p.setLicence("Pixabay licence");
        }
        p.setFocal(objectMapper.createObjectNode());
        p.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        p.setCreatedBy(admin.getId());
        return photoRepository.save(p);
    }

    private com.conductor.generated.v2.model.CreativeAudio audio(String source, String trackId) {
        return new com.conductor.generated.v2.model.CreativeAudio().source(source).trackId(trackId);
    }

    private CreateCreativeRequest carouselWithTrack(CreativePhoto track) {
        CreateCreativeRequest request = carouselRequest(Map.of("9x16", imageIds(3, 1080, 1920, true)));
        request.setAudio(audio("track", track.getId()));
        return request;
    }

    @Test
    void aCarouselWithATrackStoresTheAudioDefaultsAndAddsAudioProvenanceToReadiness() {
        CreativePhoto track = newTrack(true);
        CreativeService.CreativeView created = creativeService.createCreative(project.getId(), carouselWithTrack(track), admin);

        JsonNode audio = created.creative().getAudio();
        assertThat(audio.get("source").asText()).isEqualTo("track");
        assertThat(audio.get("trackId").asText()).isEqualTo(track.getId());
        assertThat(audio.get("volume").asDouble()).isEqualTo(1.0);
        assertThat(audio.get("fadeOutSec").asDouble()).isEqualTo(1.0);

        CreativeService.Readiness readiness = creativeService.readiness(project.getId(), created.creative().getId(), admin);
        assertThat(item(readiness, "audioProvenance").ok()).isTrue();
        assertThat(item(readiness, "audioProvenance").blocking()).isTrue();

        // A track with no source/licence blocks, as it does on a MOTION.
        CreativePhoto bare = newTrack(false);
        CreativeService.CreativeView unprovenanced = creativeService.createCreative(project.getId(), carouselWithTrack(bare), admin);
        CreativeService.Readiness blocked = creativeService.readiness(project.getId(), unprovenanced.creative().getId(), admin);
        assertThat(item(blocked, "audioProvenance").ok()).isFalse();
        assertThat(blocked.ready()).isFalse();
    }

    @Test
    void aTrackNamedWithoutASourceIsPlayedNotSilentlyDropped() {
        CreativePhoto track = newTrack(true);
        CreateCreativeRequest carousel = carouselRequest(Map.of("9x16", imageIds(3, 1080, 1920, true)));
        carousel.setAudio(audio(null, track.getId()));
        JsonNode stored = creativeService.createCreative(project.getId(), carousel, admin).creative().getAudio();
        assertThat(stored.get("source").asText()).isEqualTo("track");
        assertThat(stored.get("trackId").asText()).isEqualTo(track.getId());
    }

    @Test
    void aCarouselWithoutAnyAudioOrWithNoneStoresNoAudioAtAll() {
        CreateCreativeRequest none = carouselRequest(Map.of("9x16", imageIds(2, 1080, 1920, true)));
        none.setAudio(audio("none", null));
        assertThat(creativeService.createCreative(project.getId(), none, admin).creative().getAudio()).isNull();
        assertThat(creativeService.createCreative(project.getId(),
                carouselRequest(Map.of("9x16", imageIds(2, 1080, 1920, true))), admin).creative().getAudio()).isNull();
    }

    @Test
    void carouselAudioAndSlideshowAreValidatedOnWrite() {
        CreateCreativeRequest notATrack = carouselRequest(Map.of("9x16", imageIds(2, 1080, 1920, true)));
        notATrack.setAudio(audio("track", photo.getId()));
        assertThatThrownBy(() -> creativeService.createCreative(project.getId(), notATrack, admin))
                .isInstanceOf(CreativeValidationException.class)
                .satisfies(e -> assertThat(((CreativeValidationException) e).violations())
                        .extracting(CreativeValidationException.Violation::ruleId).contains("mediaNotAudio"));

        CreateCreativeRequest mixOnCarousel = carouselRequest(Map.of("9x16", imageIds(2, 1080, 1920, true)));
        mixOnCarousel.setAudio(audio("mix", newTrack(true).getId()));
        assertThatThrownBy(() -> creativeService.createCreative(project.getId(), mixOnCarousel, admin))
                .isInstanceOf(CreativeValidationException.class)
                .satisfies(e -> assertThat(((CreativeValidationException) e).violations())
                        .extracting(CreativeValidationException.Violation::ruleId).containsExactly("audioSource"));

        CreateCreativeRequest wrongCount = carouselWithTrack(newTrack(true));
        wrongCount.setSlideshow(new com.conductor.generated.v2.model.CreativeSlideshow()
                .slideDurations(List.of(new java.math.BigDecimal("3"))));
        assertThatThrownBy(() -> creativeService.createCreative(project.getId(), wrongCount, admin))
                .isInstanceOf(CreativeValidationException.class)
                .satisfies(e -> assertThat(((CreativeValidationException) e).violations())
                        .extracting(CreativeValidationException.Violation::ruleId).containsExactly("slideshowDurations"));

        CreateCreativeRequest slideshowOnStill = concept("Plan the week in *one sentence*.", "A calm plan.");
        slideshowOnStill.setSlideshow(new com.conductor.generated.v2.model.CreativeSlideshow().zoom(true));
        assertThatThrownBy(() -> creativeService.createCreative(project.getId(), slideshowOnStill, admin))
                .isInstanceOf(CreativeValidationException.class)
                .satisfies(e -> assertThat(((CreativeValidationException) e).violations())
                        .extracting(CreativeValidationException.Violation::ruleId).contains("kindFields"));
    }

    @Test
    void theSlideshowIsStoredWithZoomDefaultedAndCarriedToAVariant() {
        CreateCreativeRequest request = carouselWithTrack(newTrack(true));
        request.setSlideshow(new com.conductor.generated.v2.model.CreativeSlideshow().slideDurations(List.of(
                new java.math.BigDecimal("4"), new java.math.BigDecimal("2"), new java.math.BigDecimal("2"))));
        CreativeService.CreativeView created = creativeService.createCreative(project.getId(), request, admin);

        assertThat(created.creative().getSlideshow().get("zoom").asBoolean()).isTrue();
        assertThat(created.creative().getSlideshow().get("slideDurations")).hasSize(3);

        CreativeService.CreativeView variant = creativeService.createVariant(project.getId(), created.creative().getId(),
                new CreateCreativeVariantRequest(), admin);
        assertThat(variant.creative().getAudio().get("trackId")).isEqualTo(created.creative().getAudio().get("trackId"));
        assertThat(variant.creative().getSlideshow().get("slideDurations")).hasSize(3);
        assertThat(variant.creative().getSlideshow().get("zoom").asBoolean()).isTrue();
    }

    @Test
    void switchingAKindClearsTheSoundAndSlideshowOfTheKindLeft() {
        CreativeService.CreativeView created = creativeService.createCreative(project.getId(),
                carouselWithTrack(newTrack(true)), admin);

        PatchCreativeRequest toStill = new PatchCreativeRequest(created.creative().getVersion());
        toStill.setKind(CreativeKind.STILL);
        toStill.setPhotoId(photo.getId());
        toStill.setHeadline("Plan the week in *one sentence*.");
        toStill.setSlides(Map.of());
        CreativeService.CreativeView patched = creativeService.patchCreative(project.getId(), created.creative().getId(), toStill, admin);

        assertThat(patched.creative().getAudio()).isNull();
        assertThat(patched.creative().getSlideshow()).isNull();
    }

    @Test
    void aClipTakesTrackMixAndNoneAndStoresTheirDefaults() {
        CreativePhoto clip = newVideoPhoto();
        clip.setHasAudio(true);
        photoRepository.save(clip);
        CreativePhoto track = newTrack(true);

        CreateCreativeRequest replace = clipRequest(clip);
        replace.setAudio(audio("track", track.getId()));
        JsonNode replaced = creativeService.createCreative(project.getId(), replace, admin).creative().getAudio();
        assertThat(replaced.get("volume").asDouble()).isEqualTo(1.0);

        CreateCreativeRequest mix = clipRequest(clip);
        mix.setAudio(audio("mix", track.getId()));
        JsonNode mixed = creativeService.createCreative(project.getId(), mix, admin).creative().getAudio();
        assertThat(mixed.get("source").asText()).isEqualTo("mix");
        assertThat(mixed.get("volume").asDouble()).isEqualTo(0.3);

        CreateCreativeRequest mute = clipRequest(clip);
        mute.setAudio(audio("none", null));
        assertThat(creativeService.createCreative(project.getId(), mute, admin).creative().getAudio().get("source").asText())
                .isEqualTo("none");

        // "clip" is the clip's own sound: what an absent audio already means, stored as null.
        CreateCreativeRequest own = clipRequest(clip);
        own.setAudio(audio("clip", null));
        assertThat(creativeService.createCreative(project.getId(), own, admin).creative().getAudio()).isNull();
    }

    @Test
    void mixIsRefusedOnAClipWithoutSoundAndAClipTracksProvenanceIsReadinessToo() {
        CreativePhoto silent = newVideoPhoto();
        silent.setHasAudio(false);
        photoRepository.save(silent);
        CreativePhoto track = newTrack(false);

        CreateCreativeRequest mix = clipRequest(silent);
        mix.setAudio(audio("mix", track.getId()));
        assertThatThrownBy(() -> creativeService.createCreative(project.getId(), mix, admin))
                .isInstanceOf(CreativeValidationException.class)
                .satisfies(e -> assertThat(((CreativeValidationException) e).violations())
                        .extracting(CreativeValidationException.Violation::ruleId).containsExactly("audioClipHasNoAudio"));

        CreateCreativeRequest replace = clipRequest(silent);
        replace.setAudio(audio("track", track.getId()));
        CreativeService.CreativeView created = creativeService.createCreative(project.getId(), replace, admin);
        CreativeService.Readiness readiness = creativeService.readiness(project.getId(), created.creative().getId(), admin);
        assertThat(item(readiness, "audioProvenance").ok()).isFalse();
        assertThat(item(readiness, "audioProvenance").blocking()).isTrue();
    }

    private CreateCreativeRequest clipRequest(CreativePhoto clip) {
        CreateCreativeRequest request = new CreateCreativeRequest();
        request.setKind(CreativeKind.CLIP);
        request.setCaption("A caption.");
        request.setClipMedia(Map.of("default", clip.getId()));
        return request;
    }

    private CreativeService.ReadinessItem item(CreativeService.Readiness readiness, String key) {
        return readiness.items().stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void patchingCarouselSlidesRederivesPlacementsAndAVariantCopiesThem() {
        CreativeService.CreativeView created = creativeService.createCreative(project.getId(),
                carouselRequest(Map.of("4x5", imageIds(2, 1080, 1350, true))), admin);

        PatchCreativeRequest patch = new PatchCreativeRequest(created.creative().getVersion());
        Map<String, List<String>> next = new java.util.LinkedHashMap<>();
        next.put("1x1", imageIds(2, 1080, 1080, true));
        patch.setSlides(next);
        CreativeService.CreativeView patched = creativeService.patchCreative(project.getId(), created.creative().getId(), patch, admin);
        assertThat(patched.creative().getPlacements()).extracting(JsonNode::asText).containsExactly("1x1");

        CreativeService.CreativeView variant = creativeService.createVariant(project.getId(), created.creative().getId(),
                new CreateCreativeVariantRequest(), admin);
        assertThat(variant.creative().getKind()).isEqualTo(Creative.KIND_CAROUSEL);
        assertThat(variant.creative().getSlides()).isEqualTo(patched.creative().getSlides());
    }

    @Test
    void slidesOnAStillAreRefusedAsKindFields() {
        CreateCreativeRequest request = concept("Plan the week in *one sentence*.", "A calm plan.");
        request.setSlides(Map.of("4x5", imageIds(1, 1080, 1350, true)));
        assertThatThrownBy(() -> creativeService.createCreative(project.getId(), request, admin))
                .isInstanceOf(CreativeValidationException.class)
                .satisfies(e -> assertThat(((CreativeValidationException) e).violations())
                        .extracting(CreativeValidationException.Violation::ruleId).contains("kindFields"));
    }

    @Test
    void switchingAStillWithAPhotoToCarouselAndBackDropsTheFieldsTheOtherKindCannotCarry() {
        CreateCreativeRequest still = concept("Plan the week in *one sentence*.", "A calm plan.");
        CreativeService.CreativeView created = creativeService.createCreative(project.getId(), still, admin);
        assertThat(created.creative().getPhotoId()).isNotNull();

        // What the editor sends: photoId null (= keep, in a PATCH), and no placements before any slide is chosen.
        PatchCreativeRequest toCarousel = new PatchCreativeRequest(created.creative().getVersion());
        toCarousel.setKind(CreativeKind.CAROUSEL);
        toCarousel.setSlides(Map.of());
        toCarousel.setSequence(List.of());
        CreativeService.CreativeView carousel = creativeService.patchCreative(project.getId(),
                created.creative().getId(), toCarousel, admin);
        assertThat(carousel.creative().getKind()).isEqualTo(Creative.KIND_CAROUSEL);
        assertThat(carousel.creative().getPhotoId()).isNull();
        assertThat(carousel.creative().getPlacements()).isEmpty();

        PatchCreativeRequest addSlides = new PatchCreativeRequest(carousel.creative().getVersion());
        addSlides.setSlides(Map.of("4x5", imageIds(2, 1080, 1350, true)));
        addSlides.setSlideAltText(List.of("One", "Two"));
        CreativeService.CreativeView withSlides = creativeService.patchCreative(project.getId(),
                created.creative().getId(), addSlides, admin);
        assertThat(withSlides.creative().getPlacements()).extracting(JsonNode::asText).containsExactly("4x5");

        // And back: the slides are only carried over from the row, so the switch drops them.
        PatchCreativeRequest toStill = new PatchCreativeRequest(withSlides.creative().getVersion());
        toStill.setKind(CreativeKind.STILL);
        toStill.setPhotoId(photo.getId());
        CreativeService.CreativeView back = creativeService.patchCreative(project.getId(),
                created.creative().getId(), toStill, admin);
        assertThat(back.creative().getKind()).isEqualTo(Creative.KIND_STILL);
        assertThat(back.creative().getSlides()).isNull();
        assertThat(back.creative().getSlideAltText()).isNull();
    }

    @Test
    void aCarouselDraftSpecJudgesLocalSlidesByTheirDeclaredSizeAndProvenance() {
        LocalMediaInfo good = new LocalMediaInfo(MediaKind.IMAGE).width(1080).height(1350).source("own").licence("Own work");
        LocalMediaInfo wrong = new LocalMediaInfo(MediaKind.IMAGE).width(1080).height(1080);

        CreativeService.DraftSpec draft = creativeService.buildDraftSpec(project.getId(),
                new CreativeDraftSpecRequest().kind(CreativeKind.CAROUSEL).caption("Seven ways.").altText("Cards.")
                        .slides(Map.of("4x5", List.of("local:one", "local:two")))
                        .localMedia(Map.of("one", good, "two", good)), admin);
        assertThat(draft.spec().getRenderId()).isEqualTo("draft");
        assertThat(draft.spec().getPlacements()).containsExactly("4x5");
        assertThat(item(draft.readiness(), "mediaProvenance").ok()).isTrue();
        // A local file has not been vetted by anyone, so it is unchecked until uploaded and confirmed.
        assertThat(item(draft.readiness(), "photoChecked").ok()).isFalse();

        assertThatThrownBy(() -> creativeService.buildDraftSpec(project.getId(),
                new CreativeDraftSpecRequest().kind(CreativeKind.CAROUSEL).caption("Seven ways.")
                        .slides(Map.of("4x5", List.of("local:one", "local:bad")))
                        .localMedia(Map.of("one", good, "bad", wrong)), admin))
                .isInstanceOf(CreativeValidationException.class)
                .satisfies(e -> assertThat(((CreativeValidationException) e).violations())
                        .extracting(CreativeValidationException.Violation::ruleId).containsExactly("slideAspect"));
    }

    // ── draft spec (preview-first): builds a spec, persists nothing ────────────────────────────────

    @Test
    void aDraftSpecIsTheSpecARenderOfTheSameSavedCreativeWouldGet() {
        CreativeService.CreativeView saved = creativeService.createCreative(project.getId(),
                concept("Plan the week in *one sentence*.", "A calm plan."), admin);
        CreativeRenderService.CreateRenderResult render = renderService.requestRender(project.getId(),
                saved.creative().getId(), new com.conductor.generated.v2.model.CreateCreativeRenderRequest(), admin);

        CreativeService.DraftSpec draft = creativeService.buildDraftSpec(project.getId(),
                new CreativeDraftSpecRequest().headline("Plan the week in *one sentence*.").body("A calm plan.")
                        .photoId(photo.getId()).layout("stacked"), admin);

        JsonNode drafted = objectMapper.valueToTree(draft.spec());
        JsonNode rendered = objectMapper.valueToTree(render.spec());
        assertThat(drafted.get("renderId").asText()).isEqualTo("draft");
        assertThat(drafted.get("previewOnly").asBoolean()).isTrue();
        com.fasterxml.jackson.databind.node.ObjectNode draftedRest = ((com.fasterxml.jackson.databind.node.ObjectNode) drafted).deepCopy();
        com.fasterxml.jackson.databind.node.ObjectNode renderedRest = ((com.fasterxml.jackson.databind.node.ObjectNode) rendered).deepCopy();
        draftedRest.remove(List.of("renderId", "previewOnly"));
        renderedRest.remove(List.of("renderId", "previewOnly"));
        assertThat((JsonNode) draftedRest).isEqualTo(renderedRest);
    }

    @Test
    void aDraftSpecPersistsNothing() {
        CreativeService.CreativeView saved = creativeService.createCreative(project.getId(),
                concept("The old *hook*.", "A calm plan."), admin);
        String baseId = saved.creative().getId();
        int photosBefore = photoRepository.findAllByProjectIdOrderByCreatedAtDesc(project.getId()).size();
        int creativesBefore = creativeRepository.findAllByProjectIdOrderByNumberDescVariantLetterAsc(project.getId()).size();
        long kitsBefore = brandKitRepository.countByProjectId(project.getId());

        CreativeService.DraftSpec draft = creativeService.buildDraftSpec(project.getId(),
                new CreativeDraftSpecRequest().baseCreativeId(baseId).headline("A new *hook*.")
                        .photoId("local:hero").localMedia(java.util.Map.of("hero", new LocalMediaInfo(MediaKind.IMAGE))),
                admin);

        assertThat(draft.spec().getCreative().getHeadline()).isEqualTo("A new *hook*.");
        assertThat(draft.spec().getCreative().getPhotoUrl()).isEqualTo("local:hero");
        assertThat(draft.spec().getCreative().getBody()).isEqualTo("A calm plan.");
        assertThat(photoRepository.findAllByProjectIdOrderByCreatedAtDesc(project.getId())).hasSize(photosBefore);
        assertThat(creativeRepository.findAllByProjectIdOrderByNumberDescVariantLetterAsc(project.getId())).hasSize(creativesBefore);
        assertThat(renderRepository.findAllByCreativeId(baseId)).isEmpty();
        assertThat(brandKitRepository.countByProjectId(project.getId())).isEqualTo(kitsBefore);
        Creative reloaded = creativeRepository.findById(baseId).orElseThrow();
        assertThat(reloaded.getHeadline()).isEqualTo("The old *hook*.");
        assertThat(reloaded.getVersion()).isEqualTo(saved.creative().getVersion());
        assertThat(reloaded.getPhotoId()).isEqualTo(photo.getId());
    }

    @Test
    void aDraftSpecInAWorkspaceWithNoBrandKitSeedsNoKit() {
        Project bare = new Project();
        bare.setName("Bare");
        bare.setKey("BR" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        bare.setCreatedBy(admin);
        bare = projectRepository.save(bare);
        ProjectMember membership = new ProjectMember();
        membership.setProject(bare);
        membership.setUser(admin);
        membership.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(membership);
        assertThat(brandKitRepository.countByProjectId(bare.getId())).isZero();

        CreativeService.DraftSpec draft = creativeService.buildDraftSpec(bare.getId(),
                new CreativeDraftSpecRequest().headline("Hello").photoId("local:photo")
                        .localMedia(Map.of("photo", new LocalMediaInfo(MediaKind.IMAGE).width(1200).height(1600))), admin);

        assertThat(draft.spec().getBrand().getTokens()).containsKey("accent");
        assertThat(draft.spec().getPlacements()).containsExactlyInAnyOrder("9x16", "4x5", "1x1");
        assertThat(brandKitRepository.countByProjectId(bare.getId())).isZero();
    }

    @Test
    void aDraftSpecBreakingACopyRuleIsRefusedWithTheSameViolationsCreateReports() {
        CopyRule noExclaim = new CopyRule("noExclaim", "!", "No exclamation marks.", List.of(CopyRuleField.HEADLINE));
        BrandKit strict = brandKitService.createKit(project.getId(),
                new CreateBrandKitRequest("strict", "Strict Kit").copyRules(List.of(noExclaim)), admin);

        CreateCreativeRequest create = concept("Dinner is ready!", "Body");
        create.setBrandKitId(strict.getId());
        CreativeValidationException fromCreate = org.junit.jupiter.api.Assertions.assertThrows(
                CreativeValidationException.class, () -> creativeService.createCreative(project.getId(), create, admin));

        CreativeDraftSpecRequest draft = new CreativeDraftSpecRequest().brandKitId(strict.getId())
                .headline("Dinner is ready!").body("Body").photoId(photo.getId()).layout("stacked");
        CreativeValidationException fromDraft = org.junit.jupiter.api.Assertions.assertThrows(
                CreativeValidationException.class, () -> creativeService.buildDraftSpec(project.getId(), draft, admin));

        assertThat(fromDraft.violations()).isNotEmpty().isEqualTo(fromCreate.violations());
    }

    private CreateCreativeRequest concept(String headline, String body) {
        CreateCreativeRequest request = new CreateCreativeRequest();
        request.setHeadline(headline);
        request.setBody(body);
        request.setPhotoId(photo.getId());
        request.setLayout("stacked");
        return request;
    }

    private CreativePhoto newVideoPhoto() {
        CreativePhoto p = new CreativePhoto();
        p.setProjectId(project.getId());
        p.setGcsPath("projects/" + project.getId() + "/marketing/photos/" + UUID.randomUUID() + ".mp4");
        p.setContentType("video/mp4");
        p.setMediaKind(CreativePhoto.MEDIA_KIND_VIDEO);
        p.setSizeBytes(9_000_000L);
        p.setWidth(1080);
        p.setHeight(1920);
        p.setDurationSeconds(new java.math.BigDecimal("10"));
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
        user.setName("Creative Admin");
        return userRepository.save(user);
    }
}
