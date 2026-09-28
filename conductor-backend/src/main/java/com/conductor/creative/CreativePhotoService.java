package com.conductor.creative;

import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.generated.v2.model.CreateCreativePhotoRequest;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Photos with provenance a Creative renders with (COND-24 T2): mint a signed upload, confirm it, then
 * patch provenance/verdict/focal points. Same mint -&gt; PUT -&gt; confirm shape as
 * {@link com.conductor.service.AssetService#createFileAsset}, but with its own storage rows since a
 * Creative photo has no owning Work Item.
 */
@Service
public class CreativePhotoService {

    /** Nexus's advisory bar: below this on the long edge, the 2x export upscales. */
    static final int MIN_LONG_EDGE_PX = 2160;
    private static final int URL_EXPIRY_MINUTES = 15;
    private static final int UPLOAD_URL_EXPIRY_MINUTES = 60;
    private static final long MAX_UPLOAD_BYTES = 100L * 1024 * 1024;
    private static final Pattern FOCAL_PATTERN = Pattern.compile("^\\d{1,3}%\\s\\d{1,3}%$");
    private static final Logger log = LoggerFactory.getLogger(CreativePhotoService.class);

    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");

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
     * PUT ({@code uploadUrl}, present only on the create response) and any advisory warnings.
     */
    public record PhotoView(CreativePhoto photo, String url, String uploadUrl, List<String> warnings) {
    }

    @Transactional(readOnly = true)
    public List<PhotoView> listPhotos(String projectId, boolean includeBlocked, User caller) {
        requireMember(projectId, caller);
        List<CreativePhoto> photos = includeBlocked
                ? photoRepository.findAllByProjectIdOrderByCreatedAtDesc(projectId)
                : photoRepository.findAllByProjectIdAndBlockedFalseOrderByCreatedAtDesc(projectId);
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
        String extension = ALLOWED_TYPES.get(normalize(contentType));
        if (extension == null) {
            throw new BusinessException("Content type '" + contentType + "' is not allowed for a Creative photo."
                    + " Allowed types: " + ALLOWED_TYPES.keySet().stream().sorted().toList());
        }
        long sizeBytes = requireAllowedSize(request.getSizeBytes());
        if (request.getWidth() == null || request.getWidth() <= 0 || request.getHeight() == null || request.getHeight() <= 0) {
            throw new BusinessException("width and height are required and must be positive");
        }

        CreativePhoto photo = new CreativePhoto();
        String photoId = UUID.randomUUID().toString();
        photo.setId(photoId);
        photo.setProjectId(projectId);
        photo.setLabel(request.getLabel());
        String gcsPath = "projects/" + projectId + "/marketing/photos/" + photoId + "." + extension;
        photo.setGcsPath(gcsPath);
        photo.setContentType(normalize(contentType));
        photo.setSizeBytes(sizeBytes);
        photo.setWidth(request.getWidth());
        photo.setHeight(request.getHeight());
        photo.setSource(request.getSource());
        photo.setLicence(request.getLicence());
        photo.setAiGenerated(Boolean.TRUE.equals(request.getAiGenerated()));
        photo.setFocal(objectMapper.createObjectNode());
        photo.setUploadStatus(CreativePhoto.UPLOAD_STATUS_PENDING);
        photo.setCreatedBy(caller.getId());
        photo = photoRepository.save(photo);

        String signedUploadUrl = storageService.generateSignedUploadUrl(gcsPath, normalize(contentType), UPLOAD_URL_EXPIRY_MINUTES);
        String uploadUrl = signedUploadUrl != null ? signedUploadUrl : passthroughUploadUrl(projectId, photoId);
        return new PhotoView(photo, null, uploadUrl, warningsFor(photo));
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

    @Transactional
    public PhotoView confirmPhoto(String projectId, String photoId, Long observedSizeBytes, User caller) {
        requireEditor(projectId, caller);
        CreativePhoto photo = findPhoto(projectId, photoId);
        if (observedSizeBytes != null) {
            photo.setSizeBytes(requireAllowedSize(observedSizeBytes));
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

    /**
     * Deletes a Creative photo's row and its stored object (best-effort, after the delete commits —
     * see {@link AfterCommitStorageCleanup}). Refused with 409 when any Creative in the project still
     * uses it, either as its main photo or as a sequence beat's photo.
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
            if (sequenceReferencesPhoto(c.getSequence(), photoId)) {
                usedByDisplayIds.add(c.displayId());
            }
        }
        if (!usedByDisplayIds.isEmpty()) {
            throw new ConflictException("Creative " + String.join(", ", usedByDisplayIds)
                    + " uses this photo — remove it there first");
        }

        photoRepository.delete(photo);
        AfterCommitStorageCleanup.deleteAfterCommit(storageService, List.of(photo.getGcsPath()), log);
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

    /** A long edge under nexus's 2160px bar is advisory only — never a refusal (no image pipeline here). */
    List<String> warningsFor(CreativePhoto photo) {
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
        return new PhotoView(photo, url, null, warningsFor(photo));
    }

    private static long requireAllowedSize(Long sizeBytes) {
        if (sizeBytes == null || sizeBytes <= 0) {
            throw new BusinessException("Photo size must be a positive number of bytes");
        }
        if (sizeBytes > MAX_UPLOAD_BYTES) {
            throw new BusinessException("Photo size " + sizeBytes + " bytes exceeds the " + MAX_UPLOAD_BYTES + " byte ceiling");
        }
        return sizeBytes;
    }

    private static String normalize(String contentType) {
        return contentType == null ? null : contentType.trim().toLowerCase(Locale.ROOT);
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
