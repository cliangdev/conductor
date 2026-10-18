package com.conductor.service;

import com.conductor.creative.CreativePhoto;
import com.conductor.creative.CreativePhotoService;
import com.conductor.entity.Asset;
import com.conductor.entity.Project;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.entity.WorkflowDefinitionVersion;
import com.conductor.exception.BusinessException;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.repository.WorkflowDefinitionVersionRepository;
import com.conductor.service.view.AssetInput;
import com.conductor.service.view.AssetPatch;
import com.conductor.signal.SignalBus;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.InputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Signed-upload lifecycle for file Assets (COND-23 T2.2): allowlist + size ceiling at mint, server-side
 * re-validation at confirm, and the statechart-derived immutability guard.
 */
@ExtendWith(MockitoExtension.class)
class AssetUploadServiceTest {

    private static final String PROJECT_ID = "proj-1";
    private static final String ITEM_ID = "item-1";
    private static final String USER_ID = "user-1";
    private static final String BACKEND_URL = "http://localhost:8080";
    private static final long FIVE_HUNDRED_MB = 500L * 1024 * 1024;

    @Mock private AssetRepository assetRepository;
    @Mock private WorkItemRepository workItemRepository;
    @Mock private ProjectSecurityService projectSecurityService;
    @Mock private WorkflowDefinitionVersionRepository versionRepository;
    @Mock private StorageService storageService;
    @Mock private SignalBus signalBus;
    @Mock private CreativePhotoService photoService;

    private AssetService service;

    @BeforeEach
    void setUp() {
        lenient().when(versionRepository.findLatestPublished(any(), eq("MARKETING")))
                .thenReturn(Optional.of(snapshot("/schema/examples/marketing.workflow.json")));
        WorkflowDefinitionResolver resolver = new WorkflowDefinitionResolver(versionRepository);
        service = new AssetService(assetRepository, workItemRepository, projectSecurityService, resolver,
                signalBus, storageService, photoService, BACKEND_URL);
        lenient().when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(photoService.createForPostUpload(any(), any(), any(), any(), anyLong(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    CreativePhoto media = new CreativePhoto();
                    media.setId("media-1");
                    media.setGcsPath(inv.getArgument(1));
                    return media;
                });
        lenient().when(projectSecurityService.isProjectMember(PROJECT_ID, USER_ID)).thenReturn(true);
    }

    private WorkflowDefinitionVersion snapshot(String resource) {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            WorkflowDefinitionVersion v = new WorkflowDefinitionVersion();
            v.setVersion(1);
            v.setDefinition(new ObjectMapper().readTree(in));
            return v;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private WorkItem post(String status) {
        WorkItem item = new WorkItem();
        item.setId(ITEM_ID);
        item.setTitle("Launch teaser");
        Project project = new Project();
        project.setId(PROJECT_ID);
        item.setProject(project);
        item.setWorkflow("MARKETING");
        item.setCurrentStatus(status);
        return item;
    }

    private User caller() {
        User u = new User();
        u.setId(USER_ID);
        return u;
    }

    private AssetService.FileAssetInput video(String filename) {
        return new AssetService.FileAssetInput("youtube_video", "Teaser cut", filename, "video/mp4",
                FIVE_HUNDRED_MB);
    }

    private Asset pendingAsset(String contentType) {
        Asset asset = new Asset();
        asset.setId("asset-1");
        asset.setWorkItem(post("DRAFT"));
        asset.setType("youtube_video");
        asset.setKind(AssetService.KIND_FILE);
        asset.setContentType(contentType);
        asset.setSizeBytes(FIVE_HUNDRED_MB);
        asset.setGcsPath("marketing-assets/" + PROJECT_ID + "/" + ITEM_ID + "/asset-1-teaser.mp4");
        asset.setRef(asset.getGcsPath());
        asset.setUploadStatus(AssetService.UPLOAD_STATUS_PENDING);
        return asset;
    }

    // --- [auto] Allowed image and video types mint a signed PUT URL and confirm to UPLOADED -------

    @Test
    void allowedVideoTypeMintsSignedPutUrlAndPendingRow() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));
        when(storageService.generateSignedUploadUrl(anyString(), eq("video/mp4"), anyInt()))
                .thenReturn("https://storage.example/signed-put");

        AssetService.FileAssetUploadTicket ticket =
                service.createFileAsset(PROJECT_ID, ITEM_ID, video("teaser.mp4"), caller());

        assertThat(ticket.uploadUrl()).isEqualTo("https://storage.example/signed-put");
        Asset asset = ticket.asset();
        assertThat(asset.getKind()).isEqualTo(AssetService.KIND_FILE);
        assertThat(asset.getUploadStatus()).isEqualTo(AssetService.UPLOAD_STATUS_PENDING);
        assertThat(asset.getContentType()).isEqualTo("video/mp4");
        assertThat(asset.getSizeBytes()).isEqualTo(FIVE_HUNDRED_MB);
        assertThat(asset.isDone()).isFalse();
        assertThat(asset.getGcsPath())
                .isEqualTo("marketing-assets/" + PROJECT_ID + "/" + ITEM_ID + "/" + asset.getId() + "-teaser.mp4");
        assertThat(asset.getRef()).isEqualTo(asset.getGcsPath());
        verify(assetRepository).save(any(Asset.class));
    }

    @Test
    void allowedImageTypeMintsSignedPutUrl() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));
        when(storageService.generateSignedUploadUrl(anyString(), eq("image/png"), anyInt()))
                .thenReturn("https://storage.example/png-put");

        AssetService.FileAssetUploadTicket ticket = service.createFileAsset(PROJECT_ID, ITEM_ID,
                new AssetService.FileAssetInput("instagram_post", "Card", "card.png", "image/png", 2048L),
                caller());

        assertThat(ticket.uploadUrl()).isEqualTo("https://storage.example/png-put");
        assertThat(ticket.asset().getContentType()).isEqualTo("image/png");
    }

    @Test
    void confirmFlipsPendingRowToUploaded() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));
        when(assetRepository.findByIdAndWorkItemId("asset-1", ITEM_ID))
                .thenReturn(Optional.of(pendingAsset("video/mp4")));

        Asset confirmed = service.confirmUpload(PROJECT_ID, ITEM_ID, "asset-1", FIVE_HUNDRED_MB, caller());

        assertThat(confirmed.getUploadStatus()).isEqualTo(AssetService.UPLOAD_STATUS_UPLOADED);
        assertThat(confirmed.getContentType()).isEqualTo("video/mp4");
        assertThat(confirmed.getSizeBytes()).isEqualTo(FIVE_HUNDRED_MB);
        assertThat(confirmed.isDone()).isTrue();
    }

    @Test
    void confirmRevalidatesStoredContentTypeServerSide() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));
        // A row whose stored type is not on the allowlist can never be confirmed, whatever the client says.
        when(assetRepository.findByIdAndWorkItemId("asset-1", ITEM_ID))
                .thenReturn(Optional.of(pendingAsset("application/pdf")));

        assertThatThrownBy(() -> service.confirmUpload(PROJECT_ID, ITEM_ID, "asset-1", 10L, caller()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("application/pdf");
        verify(assetRepository, never()).save(any());
    }

    @Test
    void confirmRejectsSizeAboveCeiling() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));
        when(assetRepository.findByIdAndWorkItemId("asset-1", ITEM_ID))
                .thenReturn(Optional.of(pendingAsset("video/mp4")));

        assertThatThrownBy(() -> service.confirmUpload(PROJECT_ID, ITEM_ID, "asset-1",
                AssetUploadPolicy.MAX_UPLOAD_BYTES + 1, caller()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exceeds");
        verify(assetRepository, never()).save(any());
    }

    // --- One media library: a Post's image/video upload also lands in Media on the same object ------

    @Test
    void anImageUploadAlsoCreatesAPendingMediaItemOnTheSameObjectAndLinksIt() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));

        AssetService.FileAssetUploadTicket ticket = service.createFileAsset(PROJECT_ID, ITEM_ID,
                new AssetService.FileAssetInput("instagram_post", null, "spring card.png", "image/png", 2048L),
                caller());

        Asset asset = ticket.asset();
        assertThat(asset.getMediaId()).isEqualTo("media-1");
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> label = ArgumentCaptor.forClass(String.class);
        verify(photoService).createForPostUpload(eq(PROJECT_ID), path.capture(), label.capture(), eq("image/png"),
                eq(2048L), any(), any(), any(), eq(USER_ID));
        assertThat(path.getValue()).isEqualTo(asset.getGcsPath());
        assertThat(label.getValue()).isEqualTo(asset.getLabel().replaceFirst("\\.png$", ""));
    }

    @Test
    void theMediaLabelIsTheAssetLabelOrTheFilenameWithoutItsExtension() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));

        service.createFileAsset(PROJECT_ID, ITEM_ID,
                new AssetService.FileAssetInput("instagram_post", "Hero", "a.png", "image/png", 10L), caller());
        verify(photoService).createForPostUpload(any(), any(), eq("Hero"), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void aVideoUploadCreatesMediaWithItsDeclaredShape() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));

        service.createFileAsset(PROJECT_ID, ITEM_ID, new AssetService.FileAssetInput("youtube_video", "Teaser",
                "teaser.mp4", "video/mp4", 5000L, 1080, 1920, new java.math.BigDecimal("12.5")), caller());

        verify(photoService).createForPostUpload(eq(PROJECT_ID), any(), eq("Teaser"), eq("video/mp4"), eq(5000L),
                eq(1080), eq(1920), eq(new java.math.BigDecimal("12.5")), eq(USER_ID));
    }

    @Test
    void confirmMarksTheLinkedMediaUploadedWithTheConfirmedSizeAndDimensions() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));
        Asset pending = pendingAsset("video/mp4");
        pending.setMediaId("media-1");
        pending.setWidth(1080);
        pending.setHeight(1920);
        when(assetRepository.findByIdAndWorkItemId("asset-1", ITEM_ID)).thenReturn(Optional.of(pending));

        service.confirmUpload(PROJECT_ID, ITEM_ID, "asset-1", 4096L, caller());

        verify(photoService).confirmForPostUpload("media-1", 4096L, 1080, 1920);
    }

    @Test
    void confirmingAnAssetWithNoMediaLeavesTheLibraryAlone() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));
        when(assetRepository.findByIdAndWorkItemId("asset-1", ITEM_ID))
                .thenReturn(Optional.of(pendingAsset("video/mp4")));

        service.confirmUpload(PROJECT_ID, ITEM_ID, "asset-1", 4096L, caller());

        verify(photoService, never()).confirmForPostUpload(any(), any(), any(), any());
    }

    @Test
    void deletingAFileAssetAsksTheLibraryToDropItsStillPendingMedia() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));
        Asset asset = pendingAsset("video/mp4");
        asset.setMediaId("media-1");
        when(assetRepository.findByIdAndWorkItemId("asset-1", ITEM_ID)).thenReturn(Optional.of(asset));

        service.deleteAsset(PROJECT_ID, ITEM_ID, "asset-1", caller());

        verify(assetRepository).delete(asset);
        verify(photoService).deletePendingPostUpload("media-1", "asset-1");
    }

    // --- add from media ----------------------------------------------------------------------------

    private CreativePhoto media(String id, String label) {
        CreativePhoto media = new CreativePhoto();
        media.setId(id);
        media.setLabel(label);
        media.setGcsPath("projects/" + PROJECT_ID + "/marketing/photos/" + id + ".jpg");
        media.setContentType("image/jpeg");
        media.setSizeBytes(1234L);
        media.setWidth(1080);
        media.setHeight(1350);
        return media;
    }

    @Test
    void addFromMediaLinksEachItemInRequestOrderReusingItsObject() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));
        when(photoService.requireLinkable(PROJECT_ID, java.util.List.of("m2", "m1")))
                .thenReturn(java.util.List.of(media("m2", "Second"), media("m1", "First")));

        java.util.List<Asset> created = service.addFromMedia(PROJECT_ID, ITEM_ID, java.util.List.of("m2", "m1"), caller());

        assertThat(created).extracting(Asset::getMediaId).containsExactly("m2", "m1");
        assertThat(created).extracting(Asset::getLabel).containsExactly("Second", "First");
        assertThat(created.get(0).getGcsPath()).isEqualTo("projects/" + PROJECT_ID + "/marketing/photos/m2.jpg");
        assertThat(created.get(0).getUploadStatus()).isEqualTo(AssetService.UPLOAD_STATUS_UPLOADED);
        assertThat(created.get(0).getWidth()).isEqualTo(1080);
        assertThat(created.get(0).getType()).isNotBlank();
        // PUBLISH_ORDER sorts by createdAt, so the order asked for must survive it.
        assertThat(created.get(0).getCreatedAt()).isBefore(created.get(1).getCreatedAt());
        assertThat(created.stream().sorted(AssetService.PUBLISH_ORDER).map(Asset::getMediaId)).containsExactly("m2", "m1");
        verifyNoInteractions(storageService);
    }

    @Test
    void addFromMediaRefusesARepeatedIdAndAnEmptyList() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));

        assertThatThrownBy(() -> service.addFromMedia(PROJECT_ID, ITEM_ID, java.util.List.of("m1", "m1"), caller()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> service.addFromMedia(PROJECT_ID, ITEM_ID, java.util.List.of(), caller()))
                .isInstanceOf(BusinessException.class);
        verify(assetRepository, never()).save(any());
    }

    @Test
    void addFromMediaSurfacesTheLibrarysRefusalOfUnlinkableMedia() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));
        when(photoService.requireLinkable(PROJECT_ID, java.util.List.of("m1")))
                .thenThrow(new BusinessException("Media \"Bad\" is blocked and can't be added to a post"));

        assertThatThrownBy(() -> service.addFromMedia(PROJECT_ID, ITEM_ID, java.util.List.of("m1"), caller()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("blocked");
        verify(assetRepository, never()).save(any());
    }

    @Test
    void addFromMediaOnAnApprovedPostIsLockedLikeAnUpload() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("APPROVED")));

        assertThatThrownBy(() -> service.addFromMedia(PROJECT_ID, ITEM_ID, java.util.List.of("m1"), caller()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sent back for changes");
        verify(assetRepository, never()).save(any());
    }

    @Test
    void addFromMediaByANonMemberIsNotFound() {
        when(projectSecurityService.isProjectMember(PROJECT_ID, USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.addFromMedia(PROJECT_ID, ITEM_ID, java.util.List.of("m1"), caller()))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    }

    // --- [auto] A disallowed content type is rejected with a 4xx before any signed URL is issued ---

    @Test
    void disallowedContentTypeIsRejectedBeforeAnySignedUrlOrRowIsCreated() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));

        assertThatThrownBy(() -> service.createFileAsset(PROJECT_ID, ITEM_ID,
                new AssetService.FileAssetInput("youtube_video", "Deck", "deck.pdf", "application/pdf", 1024L),
                caller()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("application/pdf");

        verifyNoInteractions(storageService);
        verify(assetRepository, never()).save(any());
    }

    @Test
    void sizeAboveCeilingIsRejectedAtMintBeforeAnySignedUrlOrRowIsCreated() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));

        assertThatThrownBy(() -> service.createFileAsset(PROJECT_ID, ITEM_ID,
                new AssetService.FileAssetInput("youtube_video", "Huge", "huge.mp4", "video/mp4",
                        AssetUploadPolicy.MAX_UPLOAD_BYTES + 1),
                caller()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exceeds");

        verifyNoInteractions(storageService);
        verify(assetRepository, never()).save(any());
    }

    @Test
    void pathTraversalFilenameIsSanitizedSoTheGcsPathStaysInsideTheItemPrefix() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));
        when(storageService.generateSignedUploadUrl(anyString(), anyString(), anyInt()))
                .thenReturn("https://storage.example/put");

        AssetService.FileAssetUploadTicket ticket =
                service.createFileAsset(PROJECT_ID, ITEM_ID, video("../../../etc/passwd"), caller());

        String prefix = "marketing-assets/" + PROJECT_ID + "/" + ITEM_ID + "/";
        assertThat(ticket.asset().getGcsPath()).startsWith(prefix);
        assertThat(ticket.asset().getGcsPath().substring(prefix.length())).doesNotContain("/").doesNotContain("..");

        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(storageService).generateSignedUploadUrl(path.capture(), anyString(), anyInt());
        assertThat(path.getValue()).startsWith(prefix).doesNotContain("..");
    }

    // --- [auto] Asset mutation on an Approved-or-later Post is rejected -------------------------

    @Test
    void createFileAssetOnApprovedPostIsRejected() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("APPROVED")));

        assertThatThrownBy(() -> service.createFileAsset(PROJECT_ID, ITEM_ID, video("teaser.mp4"), caller()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sent back for changes");

        verifyNoInteractions(storageService);
        verify(assetRepository, never()).save(any());
    }

    @Test
    void replacingAFileAssetOnApprovedPostIsRejected() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("APPROVED")));
        when(assetRepository.findByIdAndWorkItemId("asset-1", ITEM_ID))
                .thenReturn(Optional.of(pendingAsset("video/mp4")));

        assertThatThrownBy(() -> service.patchAsset(PROJECT_ID, ITEM_ID, "asset-1",
                new AssetPatch("New label", null, null), caller()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sent back for changes");
        verify(assetRepository, never()).save(any());
    }

    @Test
    void deletingAFileAssetOnApprovedPostIsRejected() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("APPROVED")));
        when(assetRepository.findByIdAndWorkItemId("asset-1", ITEM_ID))
                .thenReturn(Optional.of(pendingAsset("video/mp4")));

        assertThatThrownBy(() -> service.deleteAsset(PROJECT_ID, ITEM_ID, "asset-1", caller()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sent back for changes");
        verify(assetRepository, never()).delete(any());
    }

    @Test
    void confirmingAFileAssetOnApprovedPostIsRejected() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("APPROVED")));
        when(assetRepository.findByIdAndWorkItemId("asset-1", ITEM_ID))
                .thenReturn(Optional.of(pendingAsset("video/mp4")));

        assertThatThrownBy(() -> service.confirmUpload(PROJECT_ID, ITEM_ID, "asset-1", 10L, caller()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("sent back for changes");
    }

    @Test
    void aScheduledPostIsAlsoLockedBecauseItIsPastTheReviewGate() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("SCHEDULED")));

        assertThatThrownBy(() -> service.createFileAsset(PROJECT_ID, ITEM_ID, video("teaser.mp4"), caller()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void mutationSucceedsAgainOnceTheReviewerSendsItBack() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("CHANGES_REQUESTED")));
        when(storageService.generateSignedUploadUrl(anyString(), anyString(), anyInt()))
                .thenReturn("https://storage.example/signed-put");

        AssetService.FileAssetUploadTicket ticket =
                service.createFileAsset(PROJECT_ID, ITEM_ID, video("teaser.mp4"), caller());

        assertThat(ticket.uploadUrl()).isEqualTo("https://storage.example/signed-put");
        assertThat(ticket.asset().getUploadStatus()).isEqualTo(AssetService.UPLOAD_STATUS_PENDING);
    }

    @Test
    void deleteSucceedsAgainOnceTheReviewerSendsItBack() {
        WorkItem inReview = post("CHANGES_REQUESTED");
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(inReview));
        Asset asset = pendingAsset("video/mp4");
        when(assetRepository.findByIdAndWorkItemId("asset-1", ITEM_ID)).thenReturn(Optional.of(asset));

        service.deleteAsset(PROJECT_ID, ITEM_ID, "asset-1", caller());

        verify(assetRepository).delete(asset);
    }

    @Test
    void linkAssetsAreUnaffectedByTheImmutabilityGuard() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("APPROVED")));

        Asset link = service.createAsset(PROJECT_ID, ITEM_ID,
                new AssetInput("facebook_post", "FB", "link", "https://fb/1", null), caller());

        assertThat(link.getKind()).isEqualTo("link");
        assertThat(link.getUploadStatus()).isNull();
        verifyNoInteractions(storageService);
    }

    @Test
    void deletingALinkAssetOnAnApprovedPostIsAllowed() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("APPROVED")));
        Asset link = new Asset();
        link.setId("asset-2");
        link.setKind("link");
        link.setRef("https://fb/1");
        when(assetRepository.findByIdAndWorkItemId("asset-2", ITEM_ID)).thenReturn(Optional.of(link));

        service.deleteAsset(PROJECT_ID, ITEM_ID, "asset-2", caller());

        verify(assetRepository).delete(link);
    }

    @Test
    void linkAssetOnADraftPostIsUnaffected() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));

        Asset link = service.createAsset(PROJECT_ID, ITEM_ID,
                new AssetInput("tiktok_post", "TT", "link", "https://tt/1", null), caller());

        assertThat(link.getKind()).isEqualTo("link");
        verifyNoInteractions(storageService);
    }

    // --- local-profile fallback + preview URL ---------------------------------------------------

    @Test
    void fallsBackToInternalPassthroughUrlWhenSignedUploadsAreUnsupported() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));
        when(storageService.generateSignedUploadUrl(anyString(), anyString(), anyInt())).thenReturn(null);

        AssetService.FileAssetUploadTicket ticket =
                service.createFileAsset(PROJECT_ID, ITEM_ID, video("teaser.mp4"), caller());

        assertThat(ticket.uploadUrl()).isEqualTo(BACKEND_URL + "/internal/v1/work-items/" + ITEM_ID
                + "/assets/" + ticket.asset().getId() + "/content");
    }

    @Test
    void passthroughUploadStreamsBytesStraightIntoStorage() {
        Asset asset = pendingAsset("video/mp4");
        when(assetRepository.findById("asset-1")).thenReturn(Optional.of(asset));

        service.uploadContentPassthrough("asset-1", new byte[] {1, 2, 3});

        verify(storageService).upload(asset.getGcsPath(), new byte[] {1, 2, 3}, "video/mp4");
    }

    @Test
    void previewUrlIsSignedForSixtyMinutes() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("APPROVED")));
        Asset uploaded = pendingAsset("video/mp4");
        uploaded.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        when(assetRepository.findByIdAndWorkItemId("asset-1", ITEM_ID)).thenReturn(Optional.of(uploaded));
        when(storageService.generateSignedUrl(uploaded.getGcsPath(), 60)).thenReturn("https://storage/read");

        assertThat(service.resolvePreviewUrl(PROJECT_ID, ITEM_ID, "asset-1", caller()))
                .isEqualTo("https://storage/read");
    }

    @Test
    void previewUrlIsRefusedWhileTheUploadIsStillPending() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("DRAFT")));
        when(assetRepository.findByIdAndWorkItemId("asset-1", ITEM_ID))
                .thenReturn(Optional.of(pendingAsset("video/mp4")));

        assertThatThrownBy(() -> service.resolvePreviewUrl(PROJECT_ID, ITEM_ID, "asset-1", caller()))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(storageService);
    }
}
