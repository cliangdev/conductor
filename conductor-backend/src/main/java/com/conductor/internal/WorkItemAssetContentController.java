package com.conductor.internal;

import com.conductor.generated.internal.api.WorkItemAssetsInternalApi;
import com.conductor.service.AssetService;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Local-profile passthrough for uploading a work-item file Asset's raw bytes (COND-23). Mirrors
 * {@link WorkflowArtifactContentController}: {@code StorageService} can't mint signed upload URLs on the
 * {@code local} profile (see {@code LocalStorageService}), so {@code AssetService#createFileAsset} hands the
 * client this URL instead of a signed bucket URL. The mapping therefore has to match what that service mints,
 * byte for byte — the path lives in {@code openapi-internal.yaml} and reaches both sides through the generated
 * {@link WorkItemAssetsInternalApi}, so the two can't drift. Bare mapping: {@code ApiPathConfig} prefixes every
 * {@code com.conductor.internal} controller with {@code /internal/v1}.
 *
 * <p><b>Why {@code @Profile("local")}.</b> Unlike the workflow-artifact passthrough there is no run token to
 * check here — no workflow run is involved — so the only thing standing between a caller and an object write
 * would be knowing the server-minted {@code assetId}. Rather than lean on that in production, the endpoint
 * simply doesn't exist there: {@code GcpStorageService#generateSignedUploadUrl} never returns null, so the URL
 * is never minted off the {@code local} profile and nothing legitimate calls it. Same gating as
 * {@code LocalFileController}, which serves the matching read side.
 *
 * <p>Scope check: the Asset must belong to the {@code workItemId} in the path, so a caller holding one
 * work item's asset id can't aim the write at another's. Confirming the upload (and thus the allowlist
 * re-check) stays on the membership-gated v2 endpoint.
 */
@RestController
@Profile("local")
public class WorkItemAssetContentController implements WorkItemAssetsInternalApi {

    private final AssetService assetService;

    public WorkItemAssetContentController(AssetService assetService) {
        this.assetService = assetService;
    }

    @Override
    public ResponseEntity<Void> uploadWorkItemAssetContent(String workItemId, String assetId, Resource body) {
        if (!assetService.belongsToWorkItem(assetId, workItemId)) {
            return ResponseEntity.notFound().build();
        }
        assetService.uploadContentPassthrough(assetId, readAllBytes(body, assetId));
        return ResponseEntity.ok().build();
    }

    private static byte[] readAllBytes(Resource body, String assetId) {
        try (InputStream in = body.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read upload body for asset " + assetId, e);
        }
    }
}
