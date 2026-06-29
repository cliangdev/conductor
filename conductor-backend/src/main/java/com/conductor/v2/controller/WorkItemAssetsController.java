package com.conductor.v2.controller;

import com.conductor.entity.User;
import com.conductor.generated.v2.api.WorkItemAssetsApi;
import com.conductor.generated.v2.model.AssetResponse;
import com.conductor.generated.v2.model.CreateAssetRequest;
import com.conductor.generated.v2.model.PatchAssetRequest;
import com.conductor.service.AssetService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Canonical v2 Assets sub-resource ({@code /api/v2/projects/{projectId}/work-items/{workItemId}/assets}).
 * Successor to the legacy v1 {@code AssetController}; additive, no v1 behavior change. All business logic
 * (membership/role checks, asset-type validation against the bound Workflow, notification dispatch) lives in
 * the shared {@link AssetService}, which returns fully-assembled DTOs — this controller only translates the
 * v1 service DTOs to their v2 copies (the only shape difference is {@code issueId} → {@code workItemId}).
 *
 * <p>The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig} for controllers under the
 * {@code com.conductor.v2} package, so this class maps at bare paths via the generated interface.
 *
 * <p>No {@code @Transactional} here: {@link AssetService} returns DTOs assembled inside its own transaction,
 * so no lazy associations are touched during this controller's mapping (open-in-view is disabled).
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
        com.conductor.generated.model.AssetResponse created =
                assetService.createAsset(projectId, workItemId, toV1(request), currentUser());
        return ResponseEntity.status(201).body(toV2(created));
    }

    @Override
    public ResponseEntity<AssetResponse> patchWorkItemAsset(String projectId, String workItemId, String assetId,
                                                            PatchAssetRequest request) {
        com.conductor.generated.model.AssetResponse updated =
                assetService.patchAsset(projectId, workItemId, assetId, toV1(request), currentUser());
        return ResponseEntity.ok(toV2(updated));
    }

    @Override
    public ResponseEntity<Void> deleteWorkItemAsset(String projectId, String workItemId, String assetId) {
        assetService.deleteAsset(projectId, workItemId, assetId, currentUser());
        return ResponseEntity.noContent().build();
    }

    private static com.conductor.generated.model.CreateAssetRequest toV1(CreateAssetRequest v2) {
        com.conductor.generated.model.CreateAssetRequest v1 =
                new com.conductor.generated.model.CreateAssetRequest(
                        v2.getType(),
                        com.conductor.generated.model.CreateAssetRequest.KindEnum.fromValue(v2.getKind().getValue()),
                        v2.getRef());
        v1.setLabel(v2.getLabel());
        v1.setDone(v2.getDone());
        return v1;
    }

    private static com.conductor.generated.model.PatchAssetRequest toV1(PatchAssetRequest v2) {
        com.conductor.generated.model.PatchAssetRequest v1 =
                new com.conductor.generated.model.PatchAssetRequest();
        v1.setLabel(v2.getLabel());
        v1.setRef(v2.getRef());
        v1.setDone(v2.getDone());
        return v1;
    }

    private static AssetResponse toV2(com.conductor.generated.model.AssetResponse v1) {
        return new AssetResponse(
                v1.getId(),
                v1.getIssueId(),
                v1.getType(),
                AssetResponse.KindEnum.fromValue(v1.getKind().getValue()),
                v1.getRef(),
                v1.getDone(),
                v1.getCreatedAt(),
                v1.getUpdatedAt())
                .label(v1.getLabel());
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
