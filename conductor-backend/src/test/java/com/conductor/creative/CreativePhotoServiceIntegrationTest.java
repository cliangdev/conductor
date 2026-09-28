package com.conductor.creative;

import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.exception.ConflictException;
import com.conductor.generated.v2.model.CreateCreativePhotoRequest;
import com.conductor.generated.v2.model.CreateCreativeRequest;
import com.conductor.generated.v2.model.PatchCreativePhotoRequest;
import com.conductor.generated.v2.model.SequenceBeat;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.StorageService;
import com.conductor.support.AbstractNoneWebIntegrationTest;
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
 * {@link CreativePhotoService} against a real database (COND-24 T2): mint -> confirm, the advisory
 * long-edge warning (never a refusal — no image pipeline here), and focal-point validation.
 */
class CreativePhotoServiceIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private CreativePhotoService photoService;
    @Autowired private CreativeService creativeService;
    @Autowired private CreativeRepository creativeRepository;
    @Autowired private BrandKitService brandKitService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;
    @Autowired private StorageService storageService;

    private User admin;
    private Project project;

    @BeforeEach
    void setUp() {
        admin = newUser();
        project = new Project();
        project.setName("Photo Test");
        project.setKey("PH" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(admin);
        project = projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(admin);
        membership.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(membership);
    }

    @Test
    void aPhotoUnderTheLongEdgeBarWarnsButNeverRefuses() {
        CreateCreativePhotoRequest request = new CreateCreativePhotoRequest();
        request.setContentType(CreateCreativePhotoRequest.ContentTypeEnum.IMAGE_JPEG);
        request.setSizeBytes(500L);
        request.setWidth(1200);
        request.setHeight(1500);

        CreativePhotoService.PhotoView created = photoService.createPhoto(project.getId(), request, admin);
        assertThat(created.warnings()).anyMatch(w -> w.contains("2160"));
        assertThat(created.photo().getUploadStatus()).isEqualTo(CreativePhoto.UPLOAD_STATUS_PENDING);

        CreativePhotoService.PhotoView confirmed = photoService.confirmPhoto(project.getId(),
                created.photo().getId(), null, admin);
        assertThat(confirmed.photo().isUploaded()).isTrue();
        assertThat(confirmed.warnings()).anyMatch(w -> w.contains("2160"));
    }

    @Test
    void aPhotoAtOrAboveTheLongEdgeBarHasNoWarning() {
        CreateCreativePhotoRequest request = new CreateCreativePhotoRequest();
        request.setContentType(CreateCreativePhotoRequest.ContentTypeEnum.IMAGE_WEBP);
        request.setSizeBytes(2_000_000L);
        request.setWidth(2400);
        request.setHeight(3000);

        CreativePhotoService.PhotoView created = photoService.createPhoto(project.getId(), request, admin);
        assertThat(created.warnings()).isEmpty();
    }

    @Test
    void focalMustNameARealPlacementAndLookLikeAPercentagePair() {
        CreateCreativePhotoRequest request = new CreateCreativePhotoRequest();
        request.setContentType(CreateCreativePhotoRequest.ContentTypeEnum.IMAGE_PNG);
        request.setSizeBytes(1000L);
        request.setWidth(2400);
        request.setHeight(3000);
        CreativePhotoService.PhotoView created = photoService.createPhoto(project.getId(), request, admin);

        PatchCreativePhotoRequest patch = new PatchCreativePhotoRequest();
        patch.setFocal(Map.of("9x16", "50% 30%"));
        CreativePhotoService.PhotoView patched = photoService.patchPhoto(project.getId(),
                created.photo().getId(), patch, admin);
        assertThat(patched.photo().getFocal().get("9x16").asText()).isEqualTo("50% 30%");
    }

    // ── Delete ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void deletingAPhotoNotUsedByAnyCreativeRemovesItAndItsStorageObject() {
        CreateCreativePhotoRequest request = new CreateCreativePhotoRequest();
        request.setContentType(CreateCreativePhotoRequest.ContentTypeEnum.IMAGE_JPEG);
        request.setSizeBytes(1000L);
        request.setWidth(2400);
        request.setHeight(3000);
        CreativePhotoService.PhotoView created = photoService.createPhoto(project.getId(), request, admin);
        storageService.upload(created.photo().getGcsPath(), new byte[]{1, 2, 3}, "image/jpeg");
        String gcsPath = created.photo().getGcsPath();

        photoService.deletePhoto(project.getId(), created.photo().getId(), admin);

        assertThatThrownBy(() -> photoService.getPhoto(project.getId(), created.photo().getId(), admin))
                .isInstanceOf(EntityNotFoundException.class);
        assertThatThrownBy(() -> storageService.download(gcsPath)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void deletingAPhotoUsedAsACreativesMainPhotoIsRefused() {
        CreativePhotoService.PhotoView usedPhoto = newUploadedPhoto();
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        CreateCreativeRequest request = new CreateCreativeRequest();
        request.setHeadline("A hook.");
        request.setLayout("stacked");
        request.setPhotoId(usedPhoto.photo().getId());
        CreativeService.CreativeView creative = creativeService.createCreative(project.getId(), request, admin);

        assertThatThrownBy(() -> photoService.deletePhoto(project.getId(), usedPhoto.photo().getId(), admin))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(creative.creative().displayId());
    }

    @Test
    void deletingAPhotoUsedInASequenceBeatIsRefused() {
        CreativePhotoService.PhotoView mainPhoto = newUploadedPhoto();
        CreativePhotoService.PhotoView beatPhoto = newUploadedPhoto();

        SequenceBeat beat1 = new SequenceBeat();
        beat1.setPhotoId(beatPhoto.photo().getId());
        SequenceBeat beat2 = new SequenceBeat();
        beat2.setHeadline("A second beat.");

        CreateCreativeRequest request = new CreateCreativeRequest();
        request.setHeadline("A hook.");
        request.setLayout("stacked");
        request.setPhotoId(mainPhoto.photo().getId());
        request.setSequenceKind(com.conductor.generated.v2.model.SequenceKind.STORY);
        request.setSequence(List.of(beat1, beat2));
        CreativeService.CreativeView creative = creativeService.createCreative(project.getId(), request, admin);

        assertThatThrownBy(() -> photoService.deletePhoto(project.getId(), beatPhoto.photo().getId(), admin))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining(creative.creative().displayId());
    }

    private CreativePhotoService.PhotoView newUploadedPhoto() {
        CreateCreativePhotoRequest request = new CreateCreativePhotoRequest();
        request.setContentType(CreateCreativePhotoRequest.ContentTypeEnum.IMAGE_JPEG);
        request.setSizeBytes(1000L);
        request.setWidth(2400);
        request.setHeight(3000);
        CreativePhotoService.PhotoView created = photoService.createPhoto(project.getId(), request, admin);
        return photoService.confirmPhoto(project.getId(), created.photo().getId(), null, admin);
    }

    private User newUser() {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Photo Admin");
        return userRepository.save(user);
    }
}
