package com.conductor.v2.controller;

import com.conductor.entity.Asset;
import com.conductor.entity.User;
import com.conductor.generated.v2.api.WorkItemAssetsApi;
import com.conductor.generated.v2.model.AssetResponse;
import com.conductor.generated.v2.model.CreateAssetRequest;
import com.conductor.generated.v2.model.PatchAssetRequest;
import com.conductor.service.AssetService;
import com.conductor.service.view.AssetInput;
import com.conductor.service.view.AssetPatch;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

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
 */
@RestController
public class WorkItemAssetsController implements WorkItemAssetsApi {

    private final AssetService assetService;

    public WorkItemAssetsController(AssetService assetService) {
        this.assetService = assetService;
    }

    @Override
    public ResponseEntity<List<AssetResponse>> listWorkItemAssets(String projectId, String workItemId) {
        List<AssetResponse> body = assetService.listAssets(projectId, workItemId, currentUser()).stream()
                .map(WorkItemAssetsController::toV2)
                .toList();
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<AssetResponse> createWorkItemAsset(String projectId, String workItemId,
                                                             CreateAssetRequest request) {
        AssetInput input = new AssetInput(
                request.getType(), request.getLabel(), request.getKind().getValue(),
                request.getRef(), request.getDone());
        Asset created = assetService.createAsset(projectId, workItemId, input, currentUser());
        return ResponseEntity.status(201).body(toV2(created));
    }

    @Override
    public ResponseEntity<AssetResponse> patchWorkItemAsset(String projectId, String workItemId, String assetId,
                                                            PatchAssetRequest request) {
        AssetPatch patch = new AssetPatch(request.getLabel(), request.getRef(), request.getDone());
        Asset updated = assetService.patchAsset(projectId, workItemId, assetId, patch, currentUser());
        return ResponseEntity.ok(toV2(updated));
    }

    @Override
    public ResponseEntity<Void> deleteWorkItemAsset(String projectId, String workItemId, String assetId) {
        assetService.deleteAsset(projectId, workItemId, assetId, currentUser());
        return ResponseEntity.noContent().build();
    }

    private static AssetResponse toV2(Asset asset) {
        return new AssetResponse(
                asset.getId(),
                asset.getWorkItem().getId(),
                asset.getType(),
                AssetResponse.KindEnum.fromValue(asset.getKind()),
                asset.getRef(),
                asset.isDone(),
                asset.getCreatedAt(),
                asset.getUpdatedAt())
                .label(asset.getLabel());
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
