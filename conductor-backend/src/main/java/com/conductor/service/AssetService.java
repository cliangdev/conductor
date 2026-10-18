package com.conductor.service;

import com.conductor.entity.Asset;
import com.conductor.entity.WorkItem;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.creative.CreativePhoto;
import com.conductor.creative.CreativePhotoService;
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
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Comparator;
import java.util.HashSet;
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

    /**
     * The order a Post's media goes out in when a target has not chosen its own: upload order, with id as
     * the tie-break so it is total and stable. Shared by everything that has to agree on "the Post's media
     * set" — the resolver, the approval gate, the bundle hash and each platform publisher — because a
     * carousel published in a different order than the one that was approved is a different post.
     */
    public static final Comparator<Asset> PUBLISH_ORDER =
            Comparator.comparing(Asset::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(Asset::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Whether this Asset is a file whose bytes actually landed. The publishable-media predicate: a
     * {@code link} asset has no bytes to send and a {@code PENDING} upload may never arrive, so both are
     * excluded everywhere media is resolved or validated.
     */
    public static boolean isUploadedFile(Asset asset) {
        return asset != null
                && KIND_FILE.equals(asset.getKind())
                && UPLOAD_STATUS_UPLOADED.equals(asset.getUploadStatus());
    }

    static final String GCS_PREFIX = "marketing-assets";
    private static final int UPLOAD_URL_EXPIRY_MINUTES = 60;
    private static final int PREVIEW_URL_EXPIRY_MINUTES = 15;

    private final AssetRepository assetRepository;
    private final WorkItemRepository workItemRepository;
    private final ProjectSecurityService projectSecurityService;
    private final WorkflowDefinitionResolver resolver;
    private final SignalBus signalBus;
    private final StorageService storageService;
    private final CreativePhotoService photoService;
    private final String backendBaseUrl;

    public AssetService(AssetRepository assetRepository,
                        WorkItemRepository workItemRepository,
                        ProjectSecurityService projectSecurityService,
                        WorkflowDefinitionResolver resolver,
                        SignalBus signalBus,
                        StorageService storageService,
                        CreativePhotoService photoService,
                        @Value("${conductor.backend.url:http://localhost:8080}") String backendBaseUrl) {
        this.assetRepository = assetRepository;
        this.workItemRepository = workItemRepository;
        this.projectSecurityService = projectSecurityService;
        this.resolver = resolver;
        this.signalBus = signalBus;
        this.storageService = storageService;
        this.photoService = photoService;
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

    /**
     * A file Asset whose bytes are already sitting in storage at {@code gcsPath} — the shape a system
     * caller uses when it copied the bytes itself rather than minting a signed upload (COND-24 T3:
     * {@code CreativeAttachService} copies a Creative render frame into the Post's own asset prefix, then
     * calls {@link #createFromStoredObject} to record it as a Post asset in one step, already
     * {@code UPLOADED}). {@code durationSeconds} is set for a CLIP creative's video frame (COND-24 PR1),
     * so {@code MediaTargetValidator}'s video duration rules (reels, stories, TikTok's per-creator cap)
     * can run against it exactly as they would a directly-uploaded video Asset.
     */
    public record StoredObjectInput(String type, String label, String gcsPath, String contentType, long sizeBytes,
                                    Integer width, Integer height, String creativeFrameId,
                                    java.math.BigDecimal durationSeconds, String mediaId) {

        /** A stored object that is not a link to a Media library item. */
        public StoredObjectInput(String type, String label, String gcsPath, String contentType, long sizeBytes,
                                 Integer width, Integer height, String creativeFrameId,
                                 java.math.BigDecimal durationSeconds) {
            this(type, label, gcsPath, contentType, sizeBytes, width, height, creativeFrameId, durationSeconds, null);
        }

        /** A stored object with no known duration — every caller but a video Creative frame. */
        public StoredObjectInput(String type, String label, String gcsPath, String contentType, long sizeBytes,
                                 Integer width, Integer height, String creativeFrameId) {
            this(type, label, gcsPath, contentType, sizeBytes, width, height, creativeFrameId, null);
        }
    }

    /**
     * Records a file Asset whose bytes are already at {@code input.gcsPath()} — no signed URL, no PENDING
     * row, straight to {@code UPLOADED}. Runs the same type/immutability guards {@link #createFileAsset}
     * does, but skips {@link AssetUploadPolicy}'s allowlist: the caller already validated (or produced)
     * these bytes itself (a render frame is always {@code image/png}), and re-checking against the
     * upload-facing allowlist here would only reject a type that allowlist has no reason to know about.
     *
     * <p>No caller/membership check — this is a system path invoked from within another service's own
     * transaction (mirrors {@link #recordAsset}), after that service has already established the caller
     * may edit the Work Item.
     */
    @Transactional
    public Asset createFromStoredObject(String projectId, String workItemId, StoredObjectInput input) {
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);
        validateAssetType(projectId, workItem, input.type());
        guardFileAssetMutable(projectId, workItem);

        Asset asset = new Asset();
        asset.setWorkItem(workItem);
        asset.setType(input.type());
        asset.setLabel(input.label());
        asset.setKind(KIND_FILE);
        asset.setRef(input.gcsPath());
        asset.setGcsPath(input.gcsPath());
        asset.setContentType(input.contentType());
        asset.setSizeBytes(input.sizeBytes());
        asset.setWidth(positiveOrNull(input.width()));
        asset.setHeight(positiveOrNull(input.height()));
        asset.setDurationSeconds(input.durationSeconds());
        asset.setUploadStatus(UPLOAD_STATUS_UPLOADED);
        asset.setDone(true);
        asset.setCreativeFrameId(input.creativeFrameId());
        asset.setMediaId(input.mediaId());
        assetRepository.save(asset);

        publishAssetAdded(projectId, workItem, asset);
        return asset;
    }

    /** The most Media items one add-from-media request may link — the OpenAPI {@code maxItems}. */
    public static final int MAX_FROM_MEDIA = 35;

    /**
     * Links Media library items to a Work Item as file Assets, in the order given. Each Asset reuses the
     * item's stored object (nothing is copied) and carries its shape. Refuses a blocked, still-uploading,
     * AUDIO, other-project or repeated id, and a Work Item past its review gate, exactly as an upload would.
     */
    @Transactional
    public List<Asset> addFromMedia(String projectId, String workItemId, List<String> mediaIds, User caller) {
        verifyMembership(projectId, caller.getId());
        WorkItem workItem = findWorkItemInProject(projectId, workItemId);
        if (mediaIds == null || mediaIds.isEmpty() || mediaIds.size() > MAX_FROM_MEDIA) {
            throw new BusinessException("Add between 1 and " + MAX_FROM_MEDIA + " media items at a time");
        }
        if (new HashSet<>(mediaIds).size() != mediaIds.size()) {
            throw new BusinessException("The same media item can't be added twice in one request");
        }
        guardFileAssetMutable(projectId, workItem);
        List<CreativePhoto> media = photoService.requireLinkable(projectId, mediaIds);
        String type = defaultAssetType(projectId, workItem);

        // Microsecond steps keep upload order (PUBLISH_ORDER sorts by createdAt) equal to request order.
        OffsetDateTime base = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        List<Asset> created = new ArrayList<>();
        for (int i = 0; i < media.size(); i++) {
            CreativePhoto m = media.get(i);
            Asset asset = new Asset();
            asset.setWorkItem(workItem);
            asset.setType(type);
            asset.setLabel(m.getLabel());
            asset.setKind(KIND_FILE);
            asset.setRef(m.getGcsPath());
            asset.setGcsPath(m.getGcsPath());
            asset.setContentType(m.getContentType());
            asset.setSizeBytes(m.getSizeBytes());
            asset.setWidth(positiveOrNull(m.getWidth()));
            asset.setHeight(positiveOrNull(m.getHeight()));
            asset.setDurationSeconds(positiveOrNull(m.getDurationSeconds()));
            asset.setUploadStatus(UPLOAD_STATUS_UPLOADED);
            asset.setDone(true);
            asset.setMediaId(m.getId());
            asset.setCreatedAt(base.plusNanos(1000L * i));
            assetRepository.save(asset);
            created.add(asset);
            publishAssetAdded(projectId, workItem, asset);
        }
        return created;
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
        // An image or video also lands in the Media library on the same object, so it can be reused on other posts.
        if (contentType.startsWith("image/") || contentType.startsWith("video/")) {
            String label = input.label() != null ? input.label() : stripExtension(filename);
            CreativePhoto media = photoService.createForPostUpload(projectId, gcsPath, label, contentType, sizeBytes,
                    asset.getWidth(), asset.getHeight(), asset.getDurationSeconds(), caller.getId());
            asset.setMediaId(media.getId());
        }
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
        if (UPLOAD_STATUS_UPLOADED.equals(asset.getUploadStatus())) {
            // Idempotent: a retried confirm (or a confirm of an Asset that links already-uploaded Media, as
            // from-media creates) changes nothing — in particular it must not overwrite a shared Media
            // item's size or dimensions, nor fire asset-added a second time.
            return asset;
        }
        guardFileAssetMutable(projectId, workItem);

        AssetUploadPolicy.requireAllowedContentType(asset.getContentType());
        if (observedSizeBytes != null) {
            asset.setSizeBytes(AssetUploadPolicy.requireAllowedSize(observedSizeBytes));
        }
        captureImageDimensions(asset);
        asset.setUploadStatus(UPLOAD_STATUS_UPLOADED);
        asset.setDone(true);
        assetRepository.save(asset);
        if (asset.getMediaId() != null) {
            photoService.confirmForPostUpload(asset.getMediaId(), asset.getSizeBytes(), asset.getWidth(), asset.getHeight());
        }

        publishAssetAdded(projectId, workItem, asset);
        return asset;
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
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

    /**
     * {@link #resolvePreviewUrl} for assets the caller already holds. The membership and Work Item checks
     * ran when those assets were loaded ({@link #listAssets}, {@link #createAsset}, ...), and each file
     * Asset carries its own storage path, so this touches no tables: it only signs. Keyed by asset id;
     * link, PENDING and path-less assets are simply absent.
     */
    public Map<String, String> resolvePreviewUrls(String projectId, String workItemId, Collection<Asset> assets) {
        Map<String, String> urls = new HashMap<>();
        for (Asset asset : assets) {
            if (asset.getWorkItem() == null || !workItemId.equals(asset.getWorkItem().getId())) {
                continue;
            }
            if (KIND_FILE.equals(asset.getKind()) && UPLOAD_STATUS_UPLOADED.equals(asset.getUploadStatus())
                    && asset.getGcsPath() != null) {
                urls.put(asset.getId(), storageService.generateSignedUrl(asset.getGcsPath(), PREVIEW_URL_EXPIRY_MINUTES));
            }
        }
        return urls;
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
        if (asset.getMediaId() != null) {
            // The Media row stays library content once uploaded; only a never-confirmed upload goes with its Asset.
            assetRepository.flush();
            photoService.deletePendingPostUpload(asset.getMediaId(), asset.getId());
        }
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
        if (!AssetUploadPolicy.isFrozen(statechart, workItem.getCurrentStatus())) {
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

    /**
     * The asset type a system caller should file an Asset under when it has no meaningful type of its own
     * to offer — the workflow's first declared {@code asset_types} entry, mirroring the frontend's
     * {@code MediaUploadPanel} default (the type used to be a real "which channel" choice and no longer
     * decides anything; see that component's own doc comment), or {@code "media"} for a workflow that
     * declares none. Used by {@code CreativeAttachService}, which has nothing better to call a Creative
     * render frame copied onto a Post.
     */
    public String defaultAssetType(String projectId, WorkItem workItem) {
        List<String> allowed = resolveStatechart(projectId, workItem).assetTypes();
        return allowed.isEmpty() ? "media" : allowed.get(0);
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
