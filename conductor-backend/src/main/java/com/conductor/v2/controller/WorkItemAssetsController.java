package com.conductor.v2.controller;

import com.conductor.entity.Asset;
import com.conductor.entity.User;
import com.conductor.generated.v2.api.WorkItemAssetsApi;
import com.conductor.generated.v2.model.AssetResponse;
import com.conductor.generated.v2.model.ConfirmAssetUploadRequest;
import com.conductor.generated.v2.model.CreateAssetRequest;
import com.conductor.generated.v2.model.CreateAssetUploadRequest;
import com.conductor.generated.v2.model.CreateAssetUploadResponse;
import com.conductor.generated.v2.model.PatchAssetRequest;
import com.conductor.service.AssetService;
import com.conductor.service.view.AssetInput;
import com.conductor.service.view.AssetPatch;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Canonical v2 Assets sub-resource ({@code /api/v2/projects/{projectId}/work-items/{workItemId}/assets}).
 * Successor to the legacy v1 {@code AssetController}; additive, no v1 behavior change. All business logic
 * (membership/role checks, asset-type validation against the bound Workflow, notification dispatch) lives in
 * the shared {@link AssetService}, which returns Asset entities — this controller maps them to the v2 response
 * (surfacing the parent Work Item id as {@code workItemId}).
 *
 * <p>The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig} for controllers under the
 * {@code com.conductor.v2} package, so this class maps at bare paths via the generated interface.
 *
 * <p>No {@code @Transactional} here: the mapping only reads the Asset's own columns and the parent id
 * ({@code asset.getWorkItem().getId()} resolves off the already-loaded reference), so no lazy load is triggered.
 *
 * <p><b>File uploads (COND-23).</b> {@code POST .../assets/uploads} mints a PENDING row plus the URL the client
 * PUTs bytes to, and {@code POST .../assets/{assetId}/confirm} flips it to UPLOADED — both thin wrappers over
 * {@link AssetService}, which owns the allowlist, the size ceiling and the review-gate lock. {@code previewUrl}
 * is minted per response rather than stored, so it is filled in only for UPLOADED file Assets.
 *
 * <p>The mint request's optional {@code width}/{@code height}/{@code durationSeconds} are passed straight
 * through. They exist for video: the JDK can read an image's dimensions out of its bytes at confirm, but it
 * has no container parser, so a video's shape and duration only ever reach the row if the browser measures
 * them off an {@code HTMLVideoElement} and sends them here — and without them the media rules at approval
 * block on an unmeasured value.
 */
@RestController
public class WorkItemAssetsController implements WorkItemAssetsApi {

    /**
     * Mirrors {@code AssetService}'s upload-URL lifetime, which isn't exposed on its public surface. Only used
     * to report {@code expiresAt} back to the client; the real expiry is enforced by the signed URL itself.
     */
    private static final int UPLOAD_URL_EXPIRY_MINUTES = 60;

    private final AssetService assetService;

    public WorkItemAssetsController(AssetService assetService) {
        this.assetService = assetService;
    }

    @Override
    public ResponseEntity<List<AssetResponse>> listWorkItemAssets(String projectId, String workItemId) {
        User caller = currentUser();
        List<AssetResponse> body = assetService.listAssets(projectId, workItemId, caller).stream()
                .map(asset -> toV2(projectId, workItemId, asset, caller))
                .toList();
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<AssetResponse> createWorkItemAsset(String projectId, String workItemId,
                                                             CreateAssetRequest request) {
        AssetInput input = new AssetInput(
                request.getType(), request.getLabel(), request.getKind().getValue(),
                request.getRef(), request.getDone());
        User caller = currentUser();
        Asset created = assetService.createAsset(projectId, workItemId, input, caller);
        return ResponseEntity.status(201).body(toV2(projectId, workItemId, created, caller));
    }

    @Override
    public ResponseEntity<CreateAssetUploadResponse> createWorkItemAssetUpload(String projectId, String workItemId,
                                                                              CreateAssetUploadRequest request) {
        AssetService.FileAssetInput input = new AssetService.FileAssetInput(
                request.getType(), request.getLabel(), request.getFilename(),
                request.getContentType(), request.getSizeBytes(),
                request.getWidth(), request.getHeight(), request.getDurationSeconds());
        AssetService.FileAssetUploadTicket ticket =
                assetService.createFileAsset(projectId, workItemId, input, currentUser());
        Asset asset = ticket.asset();
        CreateAssetUploadResponse body = new CreateAssetUploadResponse(
                asset.getId(),
                ticket.uploadUrl(),
                asset.getGcsPath(),
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(UPLOAD_URL_EXPIRY_MINUTES));
        return ResponseEntity.status(201).body(body);
    }

    @Override
    public ResponseEntity<Void> confirmWorkItemAssetUpload(String projectId, String workItemId, String assetId,
                                                           ConfirmAssetUploadRequest request) {
        Long observedSizeBytes = request != null ? request.getSizeBytes() : null;
        assetService.confirmUpload(projectId, workItemId, assetId, observedSizeBytes, currentUser());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<AssetResponse> patchWorkItemAsset(String projectId, String workItemId, String assetId,
                                                            PatchAssetRequest request) {
        AssetPatch patch = new AssetPatch(request.getLabel(), request.getRef(), request.getDone());
        User caller = currentUser();
        Asset updated = assetService.patchAsset(projectId, workItemId, assetId, patch, caller);
        return ResponseEntity.ok(toV2(projectId, workItemId, updated, caller));
    }

    @Override
    public ResponseEntity<Void> deleteWorkItemAsset(String projectId, String workItemId, String assetId) {
        assetService.deleteAsset(projectId, workItemId, assetId, currentUser());
        return ResponseEntity.noContent().build();
    }

    /**
     * A {@code previewUrl} is minted per response, never persisted, and only for a file Asset whose bytes are
     * actually there — {@code AssetService#resolvePreviewUrl} refuses a PENDING row, so the status check here is
     * the precondition, not an optimization.
     */
    private AssetResponse toV2(String projectId, String workItemId, Asset asset, User caller) {
        AssetResponse response = new AssetResponse(
                asset.getId(),
                asset.getWorkItem().getId(),
                asset.getType(),
                AssetResponse.KindEnum.fromValue(asset.getKind()),
                asset.getRef(),
                asset.isDone(),
                asset.getCreatedAt(),
                asset.getUpdatedAt())
                .label(asset.getLabel())
                .uploadStatus(asset.getUploadStatus())
                .contentType(asset.getContentType())
                .sizeBytes(asset.getSizeBytes());
        if (AssetService.KIND_FILE.equals(asset.getKind())
                && AssetService.UPLOAD_STATUS_UPLOADED.equals(asset.getUploadStatus())) {
            response.previewUrl(assetService.resolvePreviewUrl(projectId, workItemId, asset.getId(), caller));
        }
        return response;
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
