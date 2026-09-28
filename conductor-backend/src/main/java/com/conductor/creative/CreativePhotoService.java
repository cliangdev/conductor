package com.conductor.creative;

import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.generated.v2.model.CreateCreativePhotoRequest;
import com.conductor.generated.v2.model.MediaKind;
import com.conductor.generated.v2.model.PatchCreativePhotoRequest;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Photos with provenance a Creative renders with (COND-24 T2, extended to video/audio in PR1's "media
 * library"): mint a signed upload, confirm it, then patch provenance/verdict/focal points. Same mint
 * -&gt; PUT -&gt; confirm shape as {@link com.conductor.service.AssetService#createFileAsset}, but with its
 * own storage rows since a Creative photo has no owning Work Item.
 *
 * <p>The table/entity are still called "photo", but the API calls this the project's "media": IMAGE (the
 * original photo library), VIDEO and AUDIO. A VIDEO also carries an optional JPEG poster, minted through
 * its own {@link #mintPoster}/{@link #confirmPoster} pair.
 */
@Service
public class CreativePhotoService {

    /** Nexus's advisory bar: below this on the long edge, the 2x export upscales. Applies to IMAGE only. */
    static final int MIN_LONG_EDGE_PX = 2160;
    /** Advisory only (never a refusal): most platforms cap short-form video well under this. */
    static final int VIDEO_DURATION_WARNING_SECONDS = 180;
    private static final int URL_EXPIRY_MINUTES = 15;
    private static final int UPLOAD_URL_EXPIRY_MINUTES = 60;
    private static final long MAX_UPLOAD_BYTES_IMAGE = 100L * 1024 * 1024;
    private static final long MAX_UPLOAD_BYTES_VIDEO = 1024L * 1024 * 1024;
    private static final long MAX_UPLOAD_BYTES_AUDIO = 50L * 1024 * 1024;
    private static final Pattern FOCAL_PATTERN = Pattern.compile("^\\d{1,3}%\\s\\d{1,3}%$");
    private static final Logger log = LoggerFactory.getLogger(CreativePhotoService.class);

    /** One allowed content type: its file extension and the media kind it belongs to. */
    private record MediaTypeInfo(String extension, String mediaKind) {
    }

    private static final Map<String, MediaTypeInfo> ALLOWED_TYPES = Map.ofEntries(
            Map.entry("image/jpeg", new MediaTypeInfo("jpg", CreativePhoto.MEDIA_KIND_IMAGE)),
            Map.entry("image/png", new MediaTypeInfo("png", CreativePhoto.MEDIA_KIND_IMAGE)),
            Map.entry("image/webp", new MediaTypeInfo("webp", CreativePhoto.MEDIA_KIND_IMAGE)),
            Map.entry("video/mp4", new MediaTypeInfo("mp4", CreativePhoto.MEDIA_KIND_VIDEO)),
            Map.entry("video/quicktime", new MediaTypeInfo("mov", CreativePhoto.MEDIA_KIND_VIDEO)),
            Map.entry("video/webm", new MediaTypeInfo("webm", CreativePhoto.MEDIA_KIND_VIDEO)),
            Map.entry("audio/mpeg", new MediaTypeInfo("mp3", CreativePhoto.MEDIA_KIND_AUDIO)),
            Map.entry("audio/mp4", new MediaTypeInfo("m4a", CreativePhoto.MEDIA_KIND_AUDIO)),
            Map.entry("audio/aac", new MediaTypeInfo("aac", CreativePhoto.MEDIA_KIND_AUDIO)),
            Map.entry("audio/wav", new MediaTypeInfo("wav", CreativePhoto.MEDIA_KIND_AUDIO)));

    private final CreativePhotoRepository photoRepository;
    private final CreativeRepository creativeRepository;
    private final CreativeRegistry registry;
    private final ProjectSecurityService projectSecurityService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final String backendBaseUrl;

    public CreativePhotoService(CreativePhotoRepository photoRepository,
                                CreativeRepository creativeRepository,
                                CreativeRegistry registry,
                                ProjectSecurityService projectSecurityService,
                                StorageService storageService,
                                ObjectMapper objectMapper,
                                @Value("${conductor.backend.url:http://localhost:8080}") String backendBaseUrl) {
        this.photoRepository = photoRepository;
        this.creativeRepository = creativeRepository;
        this.registry = registry;
        this.projectSecurityService = projectSecurityService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.backendBaseUrl = backendBaseUrl;
    }

    /**
     * The photo plus its short-lived signed GET ({@code url}, null until uploaded), the one-time signed
     * PUT ({@code uploadUrl}, present only on the create response), the signed GET of its poster
     * ({@code posterUrl}, null until a VIDEO has one) and any advisory warnings.
     */
    public record PhotoView(CreativePhoto photo, String url, String uploadUrl, String posterUrl, List<String> warnings) {
    }

    /** The one-time signed PUT a poster's bytes go to, and the path it will live at. */
    public record PosterUploadTicket(String uploadUrl, String gcsPath) {
    }

    @Transactional(readOnly = true)
    public List<PhotoView> listPhotos(String projectId, boolean includeBlocked, Set<String> mediaKinds, User caller) {
        requireMember(projectId, caller);
        List<CreativePhoto> photos = includeBlocked
                ? photoRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId)
                : photoRepository.findAllByProjectIdAndBlockedFalseOrderByCreatedAtDesc(projectId);
        if (mediaKinds != null && !mediaKinds.isEmpty()) {
            photos = photos.stream().filter(p -> mediaKinds.contains(p.getMediaKind())).toList();
        }
        return photos.stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public PhotoView getPhoto(String projectId, String photoId, User caller) {
        requireMember(projectId, caller);
        return toView(findPhoto(projectId, photoId));
    }

    @Transactional
    public PhotoView createPhoto(String projectId, CreateCreativePhotoRequest request, User caller) {
        requireEditor(projectId, caller);
        String contentType = request.getContentType() != null ? request.getContentType().getValue() : null;
        MediaTypeInfo typeInfo = ALLOWED_TYPES.get(normalize(contentType));
        if (typeInfo == null) {
            throw new BusinessException("Content type '" + contentType + "' is not allowed for a Creative photo."
                    + " Allowed types: " + ALLOWED_TYPES.keySet().stream().sorted().toList());
        }
        // mediaKind defaults to IMAGE (the OpenAPI schema's default), but the content type is always the
        // source of truth for which kind this actually is — an explicit mediaKind is only ever a sanity
        // check against it, so a caller that names a video content type need not also spell out "VIDEO".
        if (request.getMediaKind() != null && !request.getMediaKind().getValue().equals(typeInfo.mediaKind())) {
            throw new BusinessException("mediaKind " + request.getMediaKind().getValue()
                    + " does not match content type '" + contentType + "'");
        }
        String mediaKind = typeInfo.mediaKind();
        long sizeBytes = requireAllowedSize(request.getSizeBytes(), mediaKind);

        boolean needsDimensions = !CreativePhoto.MEDIA_KIND_AUDIO.equals(mediaKind);
        if (needsDimensions && (request.getWidth() == null || request.getWidth() <= 0
                || request.getHeight() == null || request.getHeight() <= 0)) {
            throw new BusinessException("width and height are required and must be positive for " + mediaKind);
        }
        BigDecimal durationSeconds = null;
        boolean needsDuration = !CreativePhoto.MEDIA_KIND_IMAGE.equals(mediaKind);
        if (needsDuration) {
            if (request.getDurationSeconds() == null || request.getDurationSeconds().signum() <= 0) {
                throw new BusinessException("durationSeconds is required and must be positive for " + mediaKind);
            }
            durationSeconds = request.getDurationSeconds();
        }

        CreativePhoto photo = new CreativePhoto();
        String photoId = UUID.randomUUID().toString();
        photo.setId(photoId);
        photo.setProjectId(projectId);
        photo.setLabel(request.getLabel());
        String gcsPath = "projects/" + projectId + "/marketing/photos/" + photoId + "." + typeInfo.extension();
        photo.setGcsPath(gcsPath);
        photo.setContentType(normalize(contentType));
        photo.setSizeBytes(sizeBytes);
        photo.setMediaKind(mediaKind);
        photo.setWidth(needsDimensions ? request.getWidth() : null);
        photo.setHeight(needsDimensions ? request.getHeight() : null);
        photo.setDurationSeconds(durationSeconds);
        photo.setHasAudio(CreativePhoto.MEDIA_KIND_VIDEO.equals(mediaKind) ? request.getHasAudio() : null);
        photo.setCodec(request.getCodec());
        photo.setSource(request.getSource());
        photo.setLicence(request.getLicence());
        photo.setAiGenerated(Boolean.TRUE.equals(request.getAiGenerated()));
        photo.setFocal(objectMapper.createObjectNode());
        photo.setUploadStatus(CreativePhoto.UPLOAD_STATUS_PENDING);
        photo.setCreatedBy(caller.getId());
        photo = photoRepository.save(photo);

        String signedUploadUrl = storageService.generateSignedUploadUrl(gcsPath, normalize(contentType), UPLOAD_URL_EXPIRY_MINUTES);
        String uploadUrl = signedUploadUrl != null ? signedUploadUrl : passthroughUploadUrl(projectId, photoId);
        return new PhotoView(photo, null, uploadUrl, null, warningsFor(photo));
    }

    /**
     * Local-profile fallback, mirroring {@code AssetService#passthroughContentUrl}: {@code
     * StorageService} can't mint a signed upload URL on the {@code local} profile, so the client is
     * handed this URL instead of a signed bucket URL. Served by
     * {@code com.conductor.internal.CreativeContentController} ({@code @Profile("local")}, so it does
     * not exist in production). Unlike the Brand Kit image slots, the pending row already carries
     * {@code gcsPath} and {@code contentType}, so — exactly like {@code AssetService}'s own passthrough
     * — {@link #uploadContentPassthrough} needs nothing from the URL beyond the photo id.
     */
    private String passthroughUploadUrl(String projectId, String photoId) {
        return backendBaseUrl + "/internal/v1/projects/" + projectId + "/creative-photos/" + photoId + "/content";
    }

    private String passthroughPosterUploadUrl(String projectId, String photoId) {
        return backendBaseUrl + "/internal/v1/projects/" + projectId + "/creative-photos/" + photoId + "/poster-content";
    }

    /** Scope check for {@code CreativeContentController}: does this photo belong to this project? */
    @Transactional(readOnly = true)
    public boolean belongsToProject(String photoId, String projectId) {
        return photoRepository.findByIdAndProjectId(photoId, projectId).isPresent();
    }

    /**
     * Local-profile passthrough write: streams the raw body straight into the configured storage at the
     * pending row's own {@code gcsPath}/{@code contentType} — mirrors
     * {@code AssetService#uploadContentPassthrough}. No caller/membership check here; the caller-facing
     * scope check is {@link #belongsToProject}, done by the controller first.
     */
    @Transactional
    public void uploadContentPassthrough(String photoId, byte[] content) {
        CreativePhoto photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new EntityNotFoundException("Photo not found"));
        storageService.upload(photo.getGcsPath(), content, photo.getContentType());
    }

    /** As {@link #uploadContentPassthrough}, for a VIDEO's poster JPEG. */
    @Transactional
    public void uploadPosterContentPassthrough(String photoId, byte[] content) {
        CreativePhoto photo = photoRepository.findById(photoId)
                .orElseThrow(() -> new EntityNotFoundException("Photo not found"));
        storageService.upload(posterGcsPath(photo.getProjectId(), photoId), content, "image/jpeg");
    }

    @Transactional
    public PhotoView confirmPhoto(String projectId, String photoId, Long observedSizeBytes, User caller) {
        requireEditor(projectId, caller);
        CreativePhoto photo = findPhoto(projectId, photoId);
        if (observedSizeBytes != null) {
            photo.setSizeBytes(requireAllowedSize(observedSizeBytes, photo.getMediaKind()));
        }
        photo.setUploadStatus(CreativePhoto.UPLOAD_STATUS_UPLOADED);
        photo = photoRepository.save(photo);
        return toView(photo);
    }

    @Transactional
    public PhotoView patchPhoto(String projectId, String photoId, PatchCreativePhotoRequest request, User caller) {
        requireEditor(projectId, caller);
        CreativePhoto photo = findPhoto(projectId, photoId);
        if (request.getLabel() != null) {
            photo.setLabel(request.getLabel());
        }
        if (request.getSource() != null) {
            photo.setSource(request.getSource());
        }
        if (request.getLicence() != null) {
            photo.setLicence(request.getLicence());
        }
        if (request.getAiGenerated() != null) {
            photo.setAiGenerated(request.getAiGenerated());
        }
        if (request.getChecked() != null) {
            photo.setChecked(request.getChecked());
        }
        if (request.getBlocked() != null) {
            photo.setBlocked(request.getBlocked());
        }
        if (request.getBlockedReason() != null) {
            photo.setBlockedReason(request.getBlockedReason());
        }
        if (request.getFocal() != null) {
            photo.setFocal(validateAndConvertFocal(request.getFocal()));
        }
        photo = photoRepository.save(photo);
        return toView(photo);
    }

    // ── Poster (VIDEO only) ─────────────────────────────────────────────────────────────────────

    /** Mints a signed PUT for a VIDEO's JPEG poster. Refused with 422 when the photo is not a VIDEO. */
    @Transactional
    public PosterUploadTicket mintPoster(String projectId, String photoId, User caller) {
        requireEditor(projectId, caller);
        CreativePhoto photo = findPhoto(projectId, photoId);
        requireVideo(photo);
        String gcsPath = posterGcsPath(projectId, photoId);
        String signedUploadUrl = storageService.generateSignedUploadUrl(gcsPath, "image/jpeg", UPLOAD_URL_EXPIRY_MINUTES);
        String uploadUrl = signedUploadUrl != null ? signedUploadUrl : passthroughPosterUploadUrl(projectId, photoId);
        return new PosterUploadTicket(uploadUrl, gcsPath);
    }

    /** Confirms an uploaded poster and sets it on the photo. Refused with 422 off a VIDEO or a mismatched path. */
    @Transactional
    public PhotoView confirmPoster(String projectId, String photoId, String gcsPath, User caller) {
        requireEditor(projectId, caller);
        CreativePhoto photo = findPhoto(projectId, photoId);
        requireVideo(photo);
        String expected = posterGcsPath(projectId, photoId);
        if (gcsPath == null || !gcsPath.equals(expected)) {
            throw new UnprocessableEntityException("gcsPath does not match this photo's poster prefix");
        }
        photo.setPosterGcsPath(gcsPath);
        photo = photoRepository.save(photo);
        return toView(photo);
    }

    private void requireVideo(CreativePhoto photo) {
        if (!photo.isVideo()) {
            throw new UnprocessableEntityException("Only a VIDEO photo can have a poster");
        }
    }

    private String posterGcsPath(String projectId, String photoId) {
        return "projects/" + projectId + "/marketing/photos/" + photoId + "-poster.jpg";
    }

    /**
     * Deletes a Creative photo's row and its stored object (best-effort, after the delete commits —
     * see {@link AfterCommitStorageCleanup}). Refused with 409 when any Creative in the project still
     * uses it, either as its main photo, as a sequence beat's photo, or (for a VIDEO) as a CLIP's
     * {@code clipMedia}.
     */
    @Transactional
    public void deletePhoto(String projectId, String photoId, User caller) {
        requireEditor(projectId, caller);
        CreativePhoto photo = findPhoto(projectId, photoId);

        Set<String> usedByDisplayIds = new TreeSet<>();
        for (Creative c : creativeRepository.findAllByPhotoId(photoId)) {
            if (projectId.equals(c.getProjectId())) {
                usedByDisplayIds.add(c.displayId());
            }
        }
        for (Creative c : creativeRepository.findAllByProjectIdOrderByNumberDescVariantLetterAsc(projectId)) {
            if (sequenceReferencesPhoto(c.getSequence(), photoId) || clipMediaReferencesPhoto(c.getClipMedia(), photoId)) {
                usedByDisplayIds.add(c.displayId());
            }
        }
        if (!usedByDisplayIds.isEmpty()) {
            throw new ConflictException("Creative " + String.join(", ", usedByDisplayIds)
                    + " uses this photo — remove it there first");
        }

        List<String> gcsPaths = new ArrayList<>();
        gcsPaths.add(photo.getGcsPath());
        if (photo.getPosterGcsPath() != null) {
            gcsPaths.add(photo.getPosterGcsPath());
        }
        photoRepository.delete(photo);
        AfterCommitStorageCleanup.deleteAfterCommit(storageService, gcsPaths, log);
    }

    /** Whether any beat of a Creative's {@code sequence} JSON array names this photo. */
    private boolean sequenceReferencesPhoto(JsonNode sequence, String photoId) {
        if (sequence == null || !sequence.isArray()) {
            return false;
        }
        for (JsonNode beat : sequence) {
            JsonNode beatPhotoId = beat.get("photoId");
            if (beatPhotoId != null && beatPhotoId.isTextual() && photoId.equals(beatPhotoId.asText())) {
                return true;
            }
        }
        return false;
    }

    /** Whether a CLIP's {@code clipMedia} map ({@code {"default": id, "<placement>": id, ...}}) names this photo. */
    private boolean clipMediaReferencesPhoto(JsonNode clipMedia, String photoId) {
        if (clipMedia == null || !clipMedia.isObject()) {
            return false;
        }
        var it = clipMedia.fields();
        while (it.hasNext()) {
            JsonNode value = it.next().getValue();
            if (value != null && value.isTextual() && photoId.equals(value.asText())) {
                return true;
            }
        }
        return false;
    }

    private JsonNode validateAndConvertFocal(Map<String, String> focal) {
        Map<String, String> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : focal.entrySet()) {
            if (!registry.hasPlacement(entry.getKey())) {
                throw new BusinessException("Unknown placement in focal: " + entry.getKey());
            }
            if (entry.getValue() == null || !FOCAL_PATTERN.matcher(entry.getValue()).matches()) {
                throw new BusinessException("focal." + entry.getKey() + " must look like \"50% 30%\"");
            }
            ordered.put(entry.getKey(), entry.getValue());
        }
        return objectMapper.valueToTree(ordered);
    }

    /**
     * IMAGE: a long edge under nexus's 2160px bar is advisory only (never a refusal — no image pipeline
     * here). VIDEO: longer than {@link #VIDEO_DURATION_WARNING_SECONDS} is advisory only ("longer than
     * most platforms take" — no length cap here either, platform caps are enforced at publish time by
     * {@code MediaTargetValidator}). AUDIO: no warnings today.
     */
    List<String> warningsFor(CreativePhoto photo) {
        if (CreativePhoto.MEDIA_KIND_VIDEO.equals(photo.getMediaKind())) {
            BigDecimal duration = photo.getDurationSeconds();
            if (duration != null && duration.compareTo(BigDecimal.valueOf(VIDEO_DURATION_WARNING_SECONDS)) > 0) {
                return List.of("This video is " + duration.stripTrailingZeros().toPlainString()
                        + " seconds — longer than most platforms take.");
            }
            return List.of();
        }
        if (CreativePhoto.MEDIA_KIND_AUDIO.equals(photo.getMediaKind())) {
            return List.of();
        }
        Integer width = photo.getWidth();
        Integer height = photo.getHeight();
        if (width == null || height == null) {
            return List.of();
        }
        int longEdge = Math.max(width, height);
        if (longEdge < MIN_LONG_EDGE_PX) {
            return List.of("Long edge is " + longEdge + "px, under the " + MIN_LONG_EDGE_PX
                    + "px bar — a 2x export will upscale it.");
        }
        return List.of();
    }

    private PhotoView toView(CreativePhoto photo) {
        String url = photo.isUploaded() ? storageService.generateSignedUrl(photo.getGcsPath(), URL_EXPIRY_MINUTES) : null;
        String posterUrl = photo.getPosterGcsPath() != null
                ? storageService.generateSignedUrl(photo.getPosterGcsPath(), URL_EXPIRY_MINUTES) : null;
        return new PhotoView(photo, url, null, posterUrl, warningsFor(photo));
    }

    private static long requireAllowedSize(Long sizeBytes, String mediaKind) {
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new BusinessException("Photo size must be a positive number of bytes");
        }
        long max = maxBytesFor(mediaKind);
        if (sizeBytes > max) {
            throw new BusinessException("Photo size " + sizeBytes + " bytes exceeds the " + max
                    + " byte ceiling for " + mediaKind);
        }
        return sizeBytes;
    }

    private static long maxBytesFor(String mediaKind) {
        return switch (mediaKind == null ? CreativePhoto.MEDIA_KIND_IMAGE : mediaKind) {
            case CreativePhoto.MEDIA_KIND_VIDEO -> MAX_UPLOAD_BYTES_VIDEO;
            case CreativePhoto.MEDIA_KIND_AUDIO -> MAX_UPLOAD_BYTES_AUDIO;
            default -> MAX_UPLOAD_BYTES_IMAGE;
        };
    }

    private static String normalize(String contentType) {
        return contentType == null ? null : contentType.trim().toLowerCase(Locale.ROOT);
    }

    /** {@code MediaKind} query values -> the entity's stored strings, for {@link #listPhotos}. */
    public static Set<String> mediaKindValues(List<MediaKind> mediaKinds) {
        if (mediaKinds == null || mediaKinds.isEmpty()) {
            return Set.of();
        }
        Set<String> values = new java.util.LinkedHashSet<>();
        for (MediaKind kind : mediaKinds) {
            values.add(kind.getValue());
        }
        return values;
    }

    CreativePhoto findPhoto(String projectId, String photoId) {
        return photoRepository.findByIdAndProjectId(photoId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Photo not found"));
    }

    private void requireMember(String projectId, User caller) {
        if (!projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Project not found");
        }
    }

    private void requireEditor(String projectId, User caller) {
        requireMember(projectId, caller);
        if (!projectSecurityService.isAdminOrCreator(projectId, caller.getId())) {
            throw new com.conductor.exception.ForbiddenException("Only ADMIN or CREATOR can manage Creative photos");
        }
    }
}
