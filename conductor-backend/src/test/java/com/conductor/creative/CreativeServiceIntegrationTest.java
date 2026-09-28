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
import com.conductor.generated.v2.model.CreateCreativeVariantRequest;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
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

    private CreateCreativeRequest concept(String headline, String body) {
        CreateCreativeRequest request = new CreateCreativeRequest();
        request.setHeadline(headline);
        request.setBody(body);
        request.setPhotoId(photo.getId());
        request.setLayout("stacked");
        return request;
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
