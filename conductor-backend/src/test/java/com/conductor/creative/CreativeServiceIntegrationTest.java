package com.conductor.creative;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.ConflictException;
import com.conductor.generated.v2.model.CopyRule;
import com.conductor.generated.v2.model.CopyRuleField;
import com.conductor.generated.v2.model.CreateBrandKitRequest;
import com.conductor.generated.v2.model.CreateCreativeRequest;
import com.conductor.generated.v2.model.CreateCreativeVariantRequest;
import com.conductor.generated.v2.model.PatchCreativeRequest;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
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
 * {@link CreativeService} against a real database (COND-24 T2): number/letter assignment, the 409 on a
 * stale {@code version}, cutting a variant (AC-P0-2.3), and a Brand Kit copy rule's own message
 * surfacing on a 422 (AC-P0-2.1).
 */
class CreativeServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private CreativeService creativeService;
    @Autowired private CreativePhotoRepository photoRepository;
    @Autowired private BrandKitService brandKitService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
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
