package com.conductor.controller;

import com.conductor.entity.User;
import com.conductor.generated.api.AssetsApi;
import com.conductor.generated.model.AssetResponse;
import com.conductor.generated.model.CreateAssetRequest;
import com.conductor.generated.model.PatchAssetRequest;
import com.conductor.service.AssetService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AssetController implements AssetsApi {

    private final AssetService assetService;

    public AssetController(AssetService assetService) {
        this.assetService = assetService;
    }

    @Override
    public ResponseEntity<List<AssetResponse>> listAssets(String projectId, String issueId) {
        return ResponseEntity.ok(assetService.listAssets(projectId, issueId, currentUser()));
    }

    @Override
    public ResponseEntity<AssetResponse> createAsset(String projectId, String issueId, CreateAssetRequest createAssetRequest) {
        AssetResponse response = assetService.createAsset(projectId, issueId, createAssetRequest, currentUser());
        return ResponseEntity.status(201).body(response);
    }

    @Override
    public ResponseEntity<AssetResponse> patchAsset(String projectId, String issueId, String assetId, PatchAssetRequest patchAssetRequest) {
        AssetResponse response = assetService.patchAsset(projectId, issueId, assetId, patchAssetRequest, currentUser());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<Void> deleteAsset(String projectId, String issueId, String assetId) {
        assetService.deleteAsset(projectId, issueId, assetId, currentUser());
        return ResponseEntity.noContent().build();
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
