package com.conductor.internal;

import com.conductor.creative.BrandKit;
import com.conductor.creative.BrandKitService;
import com.conductor.creative.CreativePhotoService;
import com.conductor.entity.MemberRole;
import com.conductor.entity.Project;
import com.conductor.entity.ProjectMember;
import com.conductor.entity.User;
import com.conductor.generated.v2.model.CreateCreativePhotoRequest;
import com.conductor.repository.ProjectMemberRepository;
import com.conductor.repository.ProjectRepository;
import com.conductor.repository.UserRepository;
import com.conductor.service.StorageService;
import com.conductor.support.AbstractNoneWebIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code local}-profile passthrough for Brand Kit images and Creative photos (COND-24 T2),
 * mirroring {@code WorkItemAssetContentController}'s own test gap being closed here for the first
 * time: {@code BrandKitService#mintImageUpload}/{@code CreativePhotoService#createPhoto} must hand
 * back a working {@code uploadUrl} when {@code StorageService} can't mint a signed one (the active
 * test profile is {@code local} — see {@code AbstractPostgresIntegrationTest}), the passthrough
 * controller must actually write the bytes, and the signed-GET side must then resolve them —
 * {@code LocalStorageService#generateSignedUrl} needs no profile-specific branching to do that, so
 * this is really proving the write side closes the loop.
 */
class CreativeContentControllerIntegrationTest extends AbstractNoneWebIntegrationTest {

    @Autowired private CreativeContentController controller;
    @Autowired private BrandKitService brandKitService;
    @Autowired private CreativePhotoService photoService;
    @Autowired private StorageService storageService;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProjectMemberRepository projectMemberRepository;

    private User admin;
    private Project project;

    @BeforeEach
    void setUp() {
        admin = newUser();
        project = new Project();
        project.setName("Passthrough Test");
        project.setKey("PT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        project.setCreatedBy(admin);
        project = projectRepository.save(project);

        ProjectMember membership = new ProjectMember();
        membership.setProject(project);
        membership.setUser(admin);
        membership.setRole(MemberRole.ADMIN);
        projectMemberRepository.save(membership);
    }

    @Test
    void aCreativePhotoUploadsThroughThePassthroughOnTheLocalProfile() {
        CreateCreativePhotoRequest request = new CreateCreativePhotoRequest();
        request.setContentType(CreateCreativePhotoRequest.ContentTypeEnum.IMAGE_PNG);
        request.setSizeBytes(1000L);
        request.setWidth(2400);
        request.setHeight(3000);
        CreativePhotoService.PhotoView minted = photoService.createPhoto(project.getId(), request, admin);

        // The local profile can never mint a signed bucket URL, so the client must get our passthrough.
        assertThat(minted.uploadUrl()).contains("/internal/v1/projects/" + project.getId()
                + "/creative-photos/" + minted.photo().getId() + "/content");

        byte[] bytes = "a tiny fake png".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<Void> response = controller.uploadCreativePhotoContent(
                project.getId(), minted.photo().getId(), new ByteArrayResource(bytes));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(storageService.download(minted.photo().getGcsPath())).isEqualTo(bytes);

        CreativePhotoService.PhotoView confirmed = photoService.confirmPhoto(project.getId(),
                minted.photo().getId(), (long) bytes.length, admin);
        assertThat(confirmed.photo().isUploaded()).isTrue();
        // The signed-GET side needs no local-profile special-casing: LocalStorageService.generateSignedUrl
        // always returns a working local-files URL once the bytes actually exist.
        assertThat(confirmed.url()).contains("/api/v1/local-files/");
    }

    @Test
    void creativePhotoContentUploadIsScopedToItsOwnProject() {
        CreateCreativePhotoRequest request = new CreateCreativePhotoRequest();
        request.setContentType(CreateCreativePhotoRequest.ContentTypeEnum.IMAGE_PNG);
        request.setSizeBytes(1000L);
        request.setWidth(2400);
        request.setHeight(3000);
        CreativePhotoService.PhotoView minted = photoService.createPhoto(project.getId(), request, admin);

        ResponseEntity<Void> response = controller.uploadCreativePhotoContent(
                "some-other-project", minted.photo().getId(), new ByteArrayResource(new byte[]{1, 2, 3}));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void aBrandKitImageUploadsThroughThePassthroughOnTheLocalProfile() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());
        BrandKitService.ImageUploadTicket ticket = brandKitService.mintImageUpload(
                project.getId(), kit.getId(), "mark", "image/png", 500L, admin);

        assertThat(ticket.uploadUrl()).contains("/internal/v1/projects/" + project.getId()
                + "/brand-kits/" + kit.getId() + "/images/mark/content");
        assertThat(ticket.uploadUrl()).contains("gcsPath=");

        byte[] bytes = "a tiny fake logo".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<Void> response = controller.uploadBrandKitImageContent(
                project.getId(), kit.getId(), "mark", ticket.gcsPath(), new ByteArrayResource(bytes));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(storageService.download(ticket.gcsPath())).isEqualTo(bytes);

        BrandKit confirmed = brandKitService.confirmImage(project.getId(), kit.getId(), "mark", ticket.gcsPath(), admin);
        assertThat(brandKitService.resolveImageUrl(confirmed.getMarkGcsPath())).contains("/api/v1/local-files/");
    }

    @Test
    void brandKitImageContentUploadIsScopedToItsOwnProject() {
        BrandKit kit = brandKitService.resolveDefault(project.getId());

        ResponseEntity<Void> response = controller.uploadBrandKitImageContent(
                "some-other-project", kit.getId(), "mark", "does-not-matter", new ByteArrayResource(new byte[]{1}));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private User newUser() {
        User user = new User();
        user.setFirebaseUid("uid-" + UUID.randomUUID());
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setName("Passthrough Admin");
        return userRepository.save(user);
    }
}
