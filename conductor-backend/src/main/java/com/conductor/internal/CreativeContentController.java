package com.conductor.internal;

import com.conductor.creative.BrandKitService;
import com.conductor.creative.CreativePhotoService;
import com.conductor.generated.internal.api.CreativeContentInternalApi;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Local-profile passthrough for uploading Brand Kit image and Creative photo raw bytes (COND-24 T2).
 * Mirrors {@link WorkItemAssetContentController} exactly: {@code StorageService} can't mint signed
 * upload URLs on the {@code local} profile (see {@code LocalStorageService}), so
 * {@code BrandKitService#mintImageUpload}/{@code CreativePhotoService#createPhoto} hand the client one
 * of these URLs instead of a signed bucket URL. The mapping has to match what those services mint,
 * byte for byte — the path lives in {@code openapi-internal.yaml} and reaches both sides through the
 * generated {@link CreativeContentInternalApi}, so the two can't drift. Bare mapping:
 * {@code ApiPathConfig} prefixes every {@code com.conductor.internal} controller with
 * {@code /internal/v1}.
 *
 * <p><b>Why {@code @Profile("local")}.</b> Same reasoning as the work-item-asset passthrough: no run
 * token is involved, so the only thing standing between a caller and an object write is knowing the
 * server-minted id (and, for a Brand Kit image, its own {@code gcsPath}). Rather than lean on that in
 * production, the endpoint simply doesn't exist there — {@code GcpStorageService#generateSignedUploadUrl}
 * never returns null, so the URL is never minted off the {@code local} profile.
 *
 * <p>Scope check: each resource must belong to the {@code projectId} in the path, so a caller holding
 * one project's kit/photo id can't aim the write at another project's. Confirming the upload stays on
 * the membership-gated v2 endpoints.
 */
@RestController
@Profile("local")
public class CreativeContentController implements CreativeContentInternalApi {

    private final BrandKitService brandKitService;
    private final CreativePhotoService photoService;

    public CreativeContentController(BrandKitService brandKitService, CreativePhotoService photoService) {
        this.brandKitService = brandKitService;
        this.photoService = photoService;
    }

    @Override
    public ResponseEntity<Void> uploadBrandKitImageContent(String projectId, String kitId, String slot,
                                                            String gcsPath, Resource body) {
        if (!brandKitService.belongsToProject(kitId, projectId)) {
            return ResponseEntity.notFound().build();
        }
        brandKitService.uploadImageContentPassthrough(projectId, kitId, slot, gcsPath, readAllBytes(body));
        return ResponseEntity.ok().build();
    }

    @Override
    public ResponseEntity<Void> uploadCreativePhotoContent(String projectId, String photoId, Resource body) {
        if (!photoService.belongsToProject(photoId, projectId)) {
            return ResponseEntity.notFound().build();
        }
        photoService.uploadContentPassthrough(photoId, readAllBytes(body));
        return ResponseEntity.ok().build();
    }

    private static byte[] readAllBytes(Resource body) {
        try (InputStream in = body.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read upload body", e);
        }
    }
}
