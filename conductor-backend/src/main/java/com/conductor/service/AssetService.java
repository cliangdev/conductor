package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.WorkItem;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.WorkItemRepository;
import com.conductor.service.view.AssetInput;
import com.conductor.service.view.AssetPatch;
import com.conductor.signal.Signal;
import com.conductor.signal.SignalBus;
import com.conductor.signal.SignalOrigin;
import com.conductor.signal.SignalTypes;
import com.conductor.workflow.lifecycle.Statechart;
import com.conductor.workflow.lifecycle.WorkflowDefinitionResolver;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Produced-output Assets on a Work Item (COND-18 E5). Mirrors the {@code comments} service template:
 * fat service owning {@code @Transactional}, membership/role checks, the asset-type guard against the bound
 * Workflow's {@code asset_types}, notification dispatch, and entity→DTO assembly.
 *
 * <p><b>File assets (COND-23).</b> A {@code file}-kind Asset carries real bytes in object storage and follows
 * the signed-upload lifecycle borrowed from {@link WorkflowArtifactService}: {@link #createFileAsset} validates
 * the request against {@link AssetUploadPolicy} <em>before</em> touching storage or the DB, inserts a
 * {@code PENDING} row at {@code marketing-assets/{projectId}/{workItemId}/{assetId}-{filename}} and mints a
 * signed {@code PUT} URL; the client uploads straight to the bucket (bytes never traverse the backend); then
 * {@link #confirmUpload} flips the row to {@code UPLOADED}. On the {@code local} profile
 * {@link StorageService#generateSignedUploadUrl} returns null, so the mint falls back to the internal
 * passthrough {@code PUT /internal/v1/work-items/{workItemId}/assets/{assetId}/content}, which streams the body
 * into {@link #uploadContentPassthrough}. Reads for preview go through {@link #resolvePreviewUrl}, a
 * short-lived (15 minute) signed GET.
 *
 * <p><b>Immutability.</b> Mutating a file asset is refused once the owning Work Item is past its Workflow's
 * review gate — see {@link #guardFileAssetMutable} and {@link AssetUploadPolicy#isApprovedOrLater}. Link assets
 * are unaffected.
 */
@Service
public class AssetService {

    private static final Logger log = LoggerFactory.getLogger(AssetService.class);

    /** {@code kind} value for an Asset whose {@code ref} is a stored object rather than a URL. */
    public static final String KIND_FILE = "file";
    /** {@code kind} value for an Asset whose {@code ref} is a URL. */
    public static final String KIND_LINK = "link";

    public static final String UPLOAD_STATUS_PENDING = "PENDING";
    public static final String UPLOAD_STATUS_UPLOADED = "UPLOADED";

    static final String GCS_PREFIX = "marketing-assets";
    private static final int UPLOAD_URL_EXPIRY_MINUTES = 60;
    private static final int PREVIEW_URL_EXPIRY_MINUTES = 15;

    private final AssetRepository assetRepository;
    private final WorkItemRepository workItemRepository;
    private final ProjectSecurityService projectSecurityService;
    private final WorkflowDefinitionResolver resolver;
    private final SignalBus signalBus;
    private final StorageService storageService;
    private final String backendBaseUrl;

    public AssetService(AssetRepository assetRepository,
                        WorkItemRepository workItemRepository,
                        ProjectSecurityService projectSecurityService,
                        WorkflowDefinitionResolver resolver,
                        SignalBus signalBus,
                        StorageService storageService,
                        @Value("${conductor.backend.url:http://localhost:8080}") String backendBaseUrl) {
        this.assetRepository = assetRepository;
        this.workItemRepository = workItemRepository;
        this.projectSecurityService = projectSecurityService;
        this.resolver = resolver;
        this.signalBus = signalBus;
        this.storageService = storageService;
        this.backendBaseUrl = backendBaseUrl;
    }

    /**
     * Fields needed to mint a file Asset, decoupled from any generated request DTO version.
     *
     * <p>{@code width}/{@code height}/{@code durationSeconds} are the optional client-declared media shape
     * (COND-23 T5.1) the per-target publish rules validate against — see {@link MediaMetadata} for how each
     * one is populated and what a null means. They are optional because a caller that knows nothing about the
     * media is still allowed to upload; the five-argument constructor is exactly that caller, and video
     * duration is the one value no amount of server-side work can recover afterwards.
     */
    public record FileAssetInput(String type, String label, String filename, String contentType, Long sizeBytes,
                                 Integer width, Integer height, BigDecimal durationSeconds) {

        /** A file whose media shape the caller does not declare; images still get theirs at confirm. */
        public FileAssetInput(String type, String label, String filename, String contentType, Long sizeBytes) {
            this(type, label, filename, contentType, sizeBytes, null, null, null);
        }
    }

    /** The PENDING row plus the URL the client must {@code PUT} the bytes to. */
    public record FileAssetUploadTicket(Asset asset, String uploadUrl) {
    }

    @Transactional(readOnly = true)
    public List<Asset> listAssets(String projectId, String workItemId, User caller) {
        verifyMembership(projectId, caller.getId());
        findWorkItemInProject(projectId, workItemId);
        return assetRepository.findAllByWorkItemId(workItemId);
    }

    @Transactional
    public Asset createAsset(String projectId, String workItemId, AssetInput input, User caller) {
        verifyMembership(projectId, caller.getId());
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);
        validateAssetType(projectId, workItem, input.type());
        if (KIND_FILE.equals(input.kind())) {
            guardFileAssetMutable(projectId, workItem);
        }

        Asset asset = new Asset();
        asset.setWorkItem(workItem);
        asset.setType(input.type());
        asset.setLabel(input.label());
        asset.setKind(input.kind());
        asset.setRef(input.ref());
        asset.setDone(Boolean.TRUE.equals(input.done()));
        assetRepository.save(asset);

        publishAssetAdded(projectId, workItem, asset);
        return asset;
    }

    /**
     * Mints a file Asset: validates the request, inserts the {@code PENDING} row and returns the signed
     * {@code PUT} URL the client uploads to directly.
     *
     * <p>Validation order matters and is part of the contract — content type, size and filename are checked
     * before the workflow's {@code asset_types} guard, the immutability guard, any repository write and any
     * call into {@link StorageService}. A disallowed type therefore never yields a signed URL nor a row.
     */
    @Transactional
    public FileAssetUploadTicket createFileAsset(String projectId, String workItemId, FileAssetInput input,
                                                 User caller) {
        verifyMembership(projectId, caller.getId());
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);

        String contentType = AssetUploadPolicy.requireAllowedContentType(input.contentType());
        long sizeBytes = AssetUploadPolicy.requireAllowedSize(input.sizeBytes());
        String filename = AssetUploadPolicy.sanitizeFilename(input.filename());

        validateAssetType(projectId, workItem, input.type());
        guardFileAssetMutable(projectId, workItem);

        String assetId = UUID.randomUUID().toString();
        String gcsPath = GCS_PREFIX + "/" + projectId + "/" + workItemId + "/" + assetId + "-" + filename;

        Asset asset = new Asset();
        asset.setId(assetId);
        asset.setWorkItem(workItem);
        asset.setType(input.type());
        asset.setLabel(input.label() != null ? input.label() : filename);
        asset.setKind(KIND_FILE);
        asset.setRef(gcsPath);
        asset.setGcsPath(gcsPath);
        asset.setContentType(contentType);
        asset.setSizeBytes(sizeBytes);
        asset.setWidth(positiveOrNull(input.width()));
        asset.setHeight(positiveOrNull(input.height()));
        asset.setDurationSeconds(positiveOrNull(input.durationSeconds()));
        asset.setUploadStatus(UPLOAD_STATUS_PENDING);
        asset.setDone(false);
        assetRepository.save(asset);

        String signedUploadUrl = storageService.generateSignedUploadUrl(gcsPath, contentType, UPLOAD_URL_EXPIRY_MINUTES);
        String uploadUrl = signedUploadUrl != null ? signedUploadUrl : passthroughContentUrl(workItemId, assetId);
        return new FileAssetUploadTicket(asset, uploadUrl);
    }

    /**
     * Confirms the client's {@code PUT} landed and flips the row to {@code UPLOADED}.
     *
     * <p>The content type is <em>not</em> a parameter by design: the only type this method trusts is the one
     * persisted at mint, which is the same type the signed URL was cryptographically bound to (the storage
     * backend rejects a {@code PUT} whose {@code Content-Type} differs). That stored type is re-checked against
     * {@link AssetUploadPolicy#ALLOWED_CONTENT_TYPES} here as defence in depth, so a row that somehow carries a
     * disallowed type can never reach {@code UPLOADED}. {@code observedSizeBytes} is the byte count the client
     * actually wrote; it is re-validated against the ceiling before being persisted.
     *
     * <p>This is also where an image's real pixel dimensions are captured (COND-23 T5.1) — see
     * {@link #captureImageDimensions}. Confirm is the only moment the definitive bytes are known to exist,
     * and the approval gate needs the shape without going near object storage while a human waits.
     */
    @Transactional
    public Asset confirmUpload(String projectId, String workItemId, String assetId, Long observedSizeBytes,
                               User caller) {
        verifyMembership(projectId, caller.getId());
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);
        Asset asset = findAssetInWorkItem(workItemId, assetId);
        requireFileAsset(asset);
        guardFileAssetMutable(projectId, workItem);

        AssetUploadPolicy.requireAllowedContentType(asset.getContentType());
        if (observedSizeBytes != null) {
            asset.setSizeBytes(AssetUploadPolicy.requireAllowedSize(observedSizeBytes));
        }
        captureImageDimensions(asset);
        asset.setUploadStatus(UPLOAD_STATUS_UPLOADED);
        asset.setDone(true);
        assetRepository.save(asset);

        publishAssetAdded(projectId, workItem, asset);
        return asset;
    }

    /**
     * Reads the uploaded image's real dimensions out of its header and records them on the row, overriding
     * anything the client declared — the bytes in the bucket are the bytes a platform will receive, so they
     * are the only trustworthy source. Best effort: an unreadable format (WebP), a storage hiccup or an
     * oversized object leaves the existing values untouched rather than failing the confirm, and the
     * approval gate then blocks on the unknown shape instead, where a human can see and fix it.
     *
     * <p>Video is skipped entirely: no container parser ships with the JDK, so duration and dimensions come
     * from the client at mint (see {@link FileAssetInput}).
     */
    private void captureImageDimensions(Asset asset) {
        String contentType = AssetUploadPolicy.normalizeContentType(asset.getContentType());
        if (contentType == null || !contentType.startsWith("image/") || asset.getGcsPath() == null) {
            return;
        }
        if (asset.getSizeBytes() != null && asset.getSizeBytes() > MediaMetadata.MAX_PROBE_BYTES) {
            return;
        }
        try {
            MediaMetadata.probeImage(storageService.download(asset.getGcsPath()))
                    .ifPresent(metadata -> {
                        asset.setWidth(metadata.width());
                        asset.setHeight(metadata.height());
                    });
        } catch (RuntimeException e) {
            log.warn("Could not read image dimensions for asset {}: {}", asset.getId(), e.toString());
        }
    }

    private static Integer positiveOrNull(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    private static BigDecimal positiveOrNull(BigDecimal value) {
        return value != null && value.signum() > 0 ? value : null;
    }

    /** Short-lived (15 minute) signed GET for previewing an uploaded file Asset. Reads are never gated by status. */
    @Transactional(readOnly = true)
    public String resolvePreviewUrl(String projectId, String workItemId, String assetId, User caller) {
        verifyMembership(projectId, caller.getId());
        findWorkItemInProject(projectId, workItemId);
        Asset asset = findAssetInWorkItem(workItemId, assetId);
        requireFileAsset(asset);
        if (!UPLOAD_STATUS_UPLOADED.equals(asset.getUploadStatus()) || asset.getGcsPath() == null) {
            throw new BusinessException("Asset upload has not been confirmed yet");
        }
        return storageService.generateSignedUrl(asset.getGcsPath(), PREVIEW_URL_EXPIRY_MINUTES);
    }

    @Transactional
    public Asset patchAsset(String projectId, String workItemId, String assetId,
                            AssetPatch patch, User caller) {
        verifyMembership(projectId, caller.getId());
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);
        Asset asset = findAssetInWorkItem(workItemId, assetId);
        if (KIND_FILE.equals(asset.getKind())) {
            guardFileAssetMutable(projectId, workItem);
        }
        if (patch.label() != null) {
            asset.setLabel(patch.label());
        }
        if (patch.ref() != null) {
            asset.setRef(patch.ref());
        }
        if (patch.done() != null) {
            asset.setDone(patch.done());
        }
        assetRepository.save(asset);
        return asset;
    }

    @Transactional
    public void deleteAsset(String projectId, String workItemId, String assetId, User caller) {
        verifyMembership(projectId, caller.getId());
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);
        Asset asset = findAssetInWorkItem(workItemId, assetId);
        if (KIND_FILE.equals(asset.getKind())) {
            guardFileAssetMutable(projectId, workItem);
        }
        assetRepository.delete(asset);
    }

    /**
     * Local-profile passthrough: streams the raw body straight into the configured storage. Mirrors
     * {@code WorkflowArtifactService#uploadContentPassthrough} — used only when the storage backend cannot
     * mint signed upload URLs.
     */
    @Transactional
    public void uploadContentPassthrough(String assetId, byte[] content) {
        Asset asset = assetRepository.findById(assetId)
                .orElseThrow(() -> new EntityNotFoundException("Asset not found: " + assetId));
        storageService.upload(asset.getGcsPath(), content, asset.getContentType());
    }

    /** True if the asset exists and belongs to {@code workItemId} — the passthrough controller's scope check. */
    @Transactional(readOnly = true)
    public boolean belongsToWorkItem(String assetId, String workItemId) {
        return assetRepository.findByIdAndWorkItemId(assetId, workItemId).isPresent();
    }

    /**
     * System path: record an arbitrary produced Asset on a Work Item (no caller/membership check). Idempotent
     * on {@code (workItem, type, ref)}. Domain-agnostic — the type is passed in, not hardcoded — so any lifecycle
     * (marketing, docs, …) can auto-record its outputs, not just GitHub PRs.
     */
    @Transactional
    public void recordAsset(WorkItem workItem, String type, String ref, String label, String kind) {
        if (ref == null || ref.isBlank()) {
            return;
        }
        if (assetRepository.existsByWorkItemIdAndTypeAndRef(workItem.getId(), type, ref)) {
            return;
        }
        Asset asset = new Asset();
        asset.setWorkItem(workItem);
        asset.setType(type);
        asset.setLabel(label);
        asset.setKind(kind);
        asset.setRef(ref);
        asset.setDone(true);
        assetRepository.save(asset);
    }

    /**
     * Refuses to create, replace, confirm or delete a <em>file</em> asset once the Work Item has passed its
     * Workflow's review gate ("Approved or later"). The rule is read from the bound {@link Statechart}, never a
     * hardcoded status list — see {@link AssetUploadPolicy#isApprovedOrLater} for the derivation. Workflows with
     * no review-gated transition never lock, and link assets never reach this guard.
     */
    private void guardFileAssetMutable(String projectId, WorkItem workItem) {
        Statechart statechart = resolveStatechart(projectId, workItem);
        if (!AssetUploadPolicy.isUnderReviewOrLater(statechart, workItem.getCurrentStatus())) {
            return;
        }
        String noun = statechart.noun();
        String statusLabel = statechart.status(workItem.getCurrentStatus())
                .map(s -> s.displayLabel())
                .orElse(workItem.getCurrentStatus());
        // Names the move, not a status: the author cannot revert it themselves once it is under review,
        // so "revert it to In Review" was advice they could not act on — and In Review is now locked too.
        throw new BusinessException("Assets are locked once this " + noun + " is " + statusLabel
                + ". It has to be " + AssetUploadPolicy.reopenHint(statechart, workItem.getCurrentStatus())
                + " before its assets can change.");
    }

    private void requireFileAsset(Asset asset) {
        if (!KIND_FILE.equals(asset.getKind())) {
            throw new BusinessException("Asset " + asset.getId() + " is not a file asset");
        }
    }

    private void validateAssetType(String projectId, WorkItem workItem, String type) {
        Statechart statechart = resolveStatechart(projectId, workItem);
        List<String> allowed = statechart.assetTypes();
        if (!allowed.isEmpty() && !allowed.contains(type)) {
            throw new BusinessException("Asset type '" + type + "' is not allowed by workflow "
                    + workflowSlug(workItem));
        }
    }

    private Statechart resolveStatechart(String projectId, WorkItem workItem) {
        return resolver.resolveRequired(projectId, workflowSlug(workItem), workItem.getWorkflowVersion());
    }

    private String workflowSlug(WorkItem workItem) {
        return workItem.getWorkflow() != null ? workItem.getWorkflow() : WorkItemWorkflowService.DEFAULT_WORKFLOW;
    }

    private void publishAssetAdded(String projectId, WorkItem workItem, Asset asset) {
        signalBus.publish(Signal.of(SignalTypes.CONDUCTOR_WORK_ITEM_ASSET_ADDED, projectId, workItem.getId(),
                Instant.now(),
                Map.of("workItemId", workItem.getId(), "workItemTitle", workItem.getTitle(), "assetType", asset.getType()),
                new SignalOrigin("work_item", workItem.getId())));
    }

    private String passthroughContentUrl(String workItemId, String assetId) {
        return backendBaseUrl + "/internal/v1/work-items/" + workItemId + "/assets/" + assetId + "/content";
    }

    private void verifyMembership(String projectId, String userId) {
        if (!projectSecurityService.isProjectMember(projectId, userId)) {
            throw new EntityNotFoundException("Work Item not found");
        }
    }

    private WorkItem findWorkItemInProject(String projectId, String workItemId) {
        return workItemRepository.findById(workItemId)
                .filter(i -> i.getProject() != null && projectId.equals(i.getProject().getId()))
                .orElseThrow(() -> new EntityNotFoundException("Work Item not found"));
    }

    private Asset findAssetInWorkItem(String workItemId, String assetId) {
        return assetRepository.findByIdAndWorkItemId(assetId, workItemId)
                .orElseThrow(() -> new EntityNotFoundException("Asset not found in Work Item"));
    }
}
