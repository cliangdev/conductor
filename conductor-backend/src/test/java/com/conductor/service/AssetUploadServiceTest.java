package com.conductor.service;

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

    private AssetService service;

    @BeforeEach
    void setUp() {
        lenient().when(versionRepository.findLatestPublished(any(), eq("MARKETING")))
                .thenReturn(Optional.of(snapshot("/schema/examples/marketing.workflow.json")));
        WorkflowDefinitionResolver resolver = new WorkflowDefinitionResolver(versionRepository);
        service = new AssetService(assetRepository, workItemRepository, projectSecurityService, resolver,
                signalBus, storageService, BACKEND_URL);
        lenient().when(assetRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
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
                .hasMessageContaining("In Review");

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
                .hasMessageContaining("In Review");
        verify(assetRepository, never()).save(any());
    }

    @Test
    void deletingAFileAssetOnApprovedPostIsRejected() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("APPROVED")));
        when(assetRepository.findByIdAndWorkItemId("asset-1", ITEM_ID))
                .thenReturn(Optional.of(pendingAsset("video/mp4")));

        assertThatThrownBy(() -> service.deleteAsset(PROJECT_ID, ITEM_ID, "asset-1", caller()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("In Review");
        verify(assetRepository, never()).delete(any());
    }

    @Test
    void confirmingAFileAssetOnApprovedPostIsRejected() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("APPROVED")));
        when(assetRepository.findByIdAndWorkItemId("asset-1", ITEM_ID))
                .thenReturn(Optional.of(pendingAsset("video/mp4")));

        assertThatThrownBy(() -> service.confirmUpload(PROJECT_ID, ITEM_ID, "asset-1", 10L, caller()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("In Review");
    }

    @Test
    void aScheduledPostIsAlsoLockedBecauseItIsPastTheReviewGate() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("SCHEDULED")));

        assertThatThrownBy(() -> service.createFileAsset(PROJECT_ID, ITEM_ID, video("teaser.mp4"), caller()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void mutationSucceedsAgainAfterThePostIsRevertedToInReview() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("IN_REVIEW")));
        when(storageService.generateSignedUploadUrl(anyString(), anyString(), anyInt()))
                .thenReturn("https://storage.example/signed-put");

        AssetService.FileAssetUploadTicket ticket =
                service.createFileAsset(PROJECT_ID, ITEM_ID, video("teaser.mp4"), caller());

        assertThat(ticket.uploadUrl()).isEqualTo("https://storage.example/signed-put");
        assertThat(ticket.asset().getUploadStatus()).isEqualTo(AssetService.UPLOAD_STATUS_PENDING);
    }

    @Test
    void deleteSucceedsAgainAfterThePostIsRevertedToInReview() {
        WorkItem inReview = post("IN_REVIEW");
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
    void previewUrlIsSignedForFifteenMinutes() {
        when(workItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(post("APPROVED")));
        Asset uploaded = pendingAsset("video/mp4");
        uploaded.setUploadStatus(AssetService.UPLOAD_STATUS_UPLOADED);
        when(assetRepository.findByIdAndWorkItemId("asset-1", ITEM_ID)).thenReturn(Optional.of(uploaded));
        when(storageService.generateSignedUrl(uploaded.getGcsPath(), 15)).thenReturn("https://storage/read");

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
