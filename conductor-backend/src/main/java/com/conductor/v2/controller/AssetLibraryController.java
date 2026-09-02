package com.conductor.v2.controller;

import com.conductor.entity.User;
import com.conductor.generated.v2.api.AreaAssetsApi;
import com.conductor.generated.v2.model.AreaAssetResponse;
import com.conductor.generated.v2.model.AreaAssetWorkItemRef;
import com.conductor.service.AssetLibraryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * The Area asset library ({@code GET /api/v2/projects/{projectId}/areas/{area}/assets}) — a flat, filterable
 * view of every uploaded file Asset produced under one Area, whichever Workflows happen to live there.
 *
 * <p>Thin by design: {@link AssetLibraryService} owns the membership gate, the Area→Workflow resolution, the
 * filters and the signed preview URLs; this class only maps the service's rows onto the generated v2 DTOs.
 * The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig} for controllers under
 * {@code com.conductor.v2}, so the mapping comes from the generated interface at bare paths.
 */
@RestController
public class AssetLibraryController implements AreaAssetsApi {

    private final AssetLibraryService assetLibraryService;

    public AssetLibraryController(AssetLibraryService assetLibraryService) {
        this.assetLibraryService = assetLibraryService;
    }

    @Override
    public ResponseEntity<List<AreaAssetResponse>> listAreaAssets(String projectId, String area, String mediaType,
                                                                  String workflow, String status,
                                                                  OffsetDateTime uploadedAfter,
                                                                  OffsetDateTime uploadedBefore,
                                                                  Integer page, Integer size) {
        AssetLibraryService.LibraryQuery query =
                new AssetLibraryService.LibraryQuery(mediaType, workflow, status, uploadedAfter, uploadedBefore);
        List<AreaAssetResponse> body =
                assetLibraryService.listAreaAssets(projectId, area, query, page, size, currentUser()).stream()
                        .map(AssetLibraryController::toV2)
                        .toList();
        return ResponseEntity.ok(body);
    }

    private static AreaAssetResponse toV2(AssetLibraryService.LibraryAsset asset) {
        AreaAssetWorkItemRef workItem = new AreaAssetWorkItemRef(
                asset.workItemId(),
                asset.workItemDisplayId(),
                asset.workItemTitle(),
                asset.workItemStatus(),
                asset.workflow());
        return new AreaAssetResponse(asset.assetId(), asset.previewUrl(), asset.uploadedAt(), workItem)
                .contentType(asset.contentType())
                .sizeBytes(asset.sizeBytes());
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
