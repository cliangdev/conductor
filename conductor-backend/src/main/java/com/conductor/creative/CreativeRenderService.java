package com.conductor.creative;

import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.generated.v2.model.CompleteCreativeRenderRequest;
import com.conductor.generated.v2.model.CreateCreativeRenderRequest;
import com.conductor.generated.v2.model.CreativeAudio;
import com.conductor.generated.v2.model.CreativeKind;
import com.conductor.generated.v2.model.CreativeMotion;
import com.conductor.generated.v2.model.CreativeMotionBackground;
import com.conductor.generated.v2.model.CreativeRenderFrameResponse;
import com.conductor.generated.v2.model.CreativeRenderResponse;
import com.conductor.generated.v2.model.CreativeRenderSpec;
import com.conductor.generated.v2.model.CreativeRenderSpecAudio;
import com.conductor.generated.v2.model.CreativeRenderSpecBeat;
import com.conductor.generated.v2.model.CreativeLayoutOverrides;
import com.conductor.generated.v2.model.CreativeLockup;
import com.conductor.generated.v2.model.CreativeRenderSpecBrand;
import com.conductor.generated.v2.model.CreativeRenderSpecCreative;
import com.conductor.generated.v2.model.CreativeRenderSpecLogos;
import com.conductor.generated.v2.model.CreativeRenderSpecMotion;
import com.conductor.generated.v2.model.CreativeRenderSpecMotionBackground;
import com.conductor.generated.v2.model.CreativeRenderState;
import com.conductor.generated.v2.model.CreativeRenderWarning;
import com.conductor.generated.v2.model.CreativeTheme;
import com.conductor.generated.v2.model.FailCreativeRenderRequest;
import com.conductor.generated.v2.model.SequenceBeat;
import com.conductor.generated.v2.model.SequenceKind;
import com.conductor.service.AssetUploadPolicy;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.StorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Renders of a Creative (COND-24 T3). Rendering itself happens on the user's own machine — through the
 * Conductor MCP server or CLI (Playwright, driving {@code conductor-creative/job}'s render core), or a
 * self-hosted Workflow running the same CLI — never here: this service only issues the render input
 * ({@link #requestRender}'s {@code spec}), stores the frames a local job {@code PUT}s, and answers reads.
 *
 * <p>There is no scheduler and nothing ever launches or watches a render: a {@code RUNNING} row that a
 * local job died without finishing is only ever noticed, and flipped to {@code FAILED} "timed out", the
 * next time it is read ({@link #applyLazyTimeout}) — see {@code docs/creatives.md}.
 */
@Service
public class CreativeRenderService {

    static final int RENDER_TIMEOUT_MINUTES = 30;
    static final long MAX_FRAME_BYTES = 20L * 1024 * 1024;
    /** COND-24 PR2: a MOTION frame is video/mp4, much larger than an image frame. */
    static final long MAX_VIDEO_FRAME_BYTES = 500L * 1024 * 1024;
    /** A placement frame is JPEG (Instagram feed images and TikTok photo posts both refuse PNG); the
     * `sheet` contact sheet of a preview-only render stays PNG. See {@code docs/creatives.md}. */
    static final String CONTENT_TYPE_JPEG = "image/jpeg";
    static final String CONTENT_TYPE_PNG = "image/png";
    /** COND-24 PR2: a MOTION render's frame. */
    static final String CONTENT_TYPE_MP4 = "video/mp4";
    private static final Set<String> ALLOWED_FRAME_CONTENT_TYPES = Set.of(CONTENT_TYPE_JPEG, CONTENT_TYPE_PNG, CONTENT_TYPE_MP4);
    private static final int MAX_RENDERS_LISTED = 20;
    private static final int FRAME_URL_EXPIRY_MINUTES = 15;
    /** Contract: signed URLs in a render spec must stay valid at least 30 minutes. */
    private static final int SPEC_URL_EXPIRY_MINUTES = 45;

    private static final Logger log = LoggerFactory.getLogger(CreativeRenderService.class);

    private final CreativeRenderRepository renderRepository;
    private final CreativeRenderFrameRepository frameRepository;
    private final CreativeRepository creativeRepository;
    private final CreativePhotoRepository photoRepository;
    private final BrandKitRepository brandKitRepository;
    private final CreativeRegistry registry;
    private final CreativeValidator validator;
    private final ProjectSecurityService projectSecurityService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    public CreativeRenderService(CreativeRenderRepository renderRepository,
                                 CreativeRenderFrameRepository frameRepository,
                                 CreativeRepository creativeRepository,
                                 CreativePhotoRepository photoRepository,
                                 BrandKitRepository brandKitRepository,
                                 CreativeRegistry registry,
                                 CreativeValidator validator,
                                 ProjectSecurityService projectSecurityService,
                                 StorageService storageService,
                                 ObjectMapper objectMapper) {
        this.renderRepository = renderRepository;
        this.frameRepository = frameRepository;
        this.creativeRepository = creativeRepository;
        this.photoRepository = photoRepository;
        this.brandKitRepository = brandKitRepository;
        this.registry = registry;
        this.validator = validator;
        this.projectSecurityService = projectSecurityService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    /** A render plus its frames, assembled into the response shape. */
    public record RenderView(CreativeRender render, List<CreativeRenderFrame> frames) {
    }

    /** {@code frames} is non-empty only for a CLIP creative, whose render is assembled and SUCCEEDED
     *  synchronously (COND-24 PR1); a STILL render is created RUNNING with no frames yet. */
    public record CreateRenderResult(CreativeRender render, CreativeRenderSpec spec, List<CreativeRenderFrame> frames) {
        public CreateRenderResult(CreativeRender render, CreativeRenderSpec spec) {
            this(render, spec, List.of());
        }
    }

    @Transactional
    public CreateRenderResult requestRender(String projectId, String creativeId, CreateCreativeRenderRequest request,
                                            User caller) {
        requireEditor(projectId, caller);
        Creative creative = findCreative(projectId, creativeId);
        if (Creative.KIND_CLIP.equals(creative.getKind())) {
            return requestClipRender(projectId, creative, request, caller);
        }
        return requestStillRender(projectId, creative, request, caller);
    }

    /**
     * Handles both STILL and MOTION (COND-24 PR2) — the two kinds share the same local-job spec shape
     * (brand-layout copy, sequence, photo) and READY-forced validation; MOTION only adds its own
     * {@code motion}/{@code audio} fields on top, resolved by {@link #resolveMotionForRender}. previewOnly
     * is allowed for both (unlike CLIP, which has no local job to preview).
     */
    private CreateRenderResult requestStillRender(String projectId, Creative creative, CreateCreativeRenderRequest request,
                                                  User caller) {
        BrandKit kit = requireKit(projectId, creative.getBrandKitId());
        CreativePhoto mainPhoto = creative.getPhotoId() != null
                ? photoRepository.findByIdAndProjectId(creative.getPhotoId(), projectId).orElse(null) : null;
        MotionSpecResolution motionRes = Creative.KIND_MOTION.equals(creative.getKind())
                ? resolveMotionForRender(projectId, creative) : null;

        List<CreativeValidationException.Violation> violations = validator.validate(kit, new CreativeValidator.Input(
                creative.getLayout(), creative.getTheme(), toStringList(creative.getPlacements()),
                creative.getHeadline(), creative.getBody(), creative.getCaption(),
                // Force the READY-only checks regardless of the Creative's own state: a render is refused
                // the moment it lacks a photo or headline, not only once someone flips it to READY.
                Creative.STATE_READY, creative.getPhotoId(), creative.getPhotoId() == null || mainPhoto != null,
                mainPhoto != null, mainPhoto != null && mainPhoto.isUploaded(),
                mainPhoto != null && mainPhoto.isBlocked(), creative.getSequenceKind(),
                toValidatorBeats(toSequenceBeats(creative.getSequence())), creative.getCarouselRatio(), Set.of(),
                extractOverrideInts(creative.getLayoutOverrides(), "band"),
                extractOverrideInts(creative.getLayoutOverrides(), "padBottom"),
                creative.getKind(), List.of(), motionRes != null ? motionRes.input() : null));
        if (!violations.isEmpty()) {
            throw new CreativeValidationException(violations);
        }

        List<String> placements = resolvePlacements(kit, creative);

        CreativeRender render = new CreativeRender();
        render.setProjectId(projectId);
        render.setCreativeId(creative.getId());
        render.setCreativeVersion(creative.getVersion());
        render.setState(CreativeRender.STATE_RUNNING);
        render.setPreviewOnly(request != null && Boolean.TRUE.equals(request.getPreviewOnly()));
        render.setRenderer(request != null ? request.getRenderer() : null);
        render.setWorkflowRunId(request != null ? request.getWorkflowRunId() : null);
        render.setRequestedBy(caller.getId());
        render.setRequestedAt(OffsetDateTime.now());
        render = renderRepository.save(render);

        CreativeRenderSpec spec = buildSpec(render, creative, kit, mainPhoto, placements, motionRes);
        return new CreateRenderResult(render, spec);
    }

    /**
     * Resolves a MOTION creative's {@code motion}/{@code audio} media references (background clip, audio
     * track) for a render — the JSON already carries every default {@code CreativeService} applied on
     * write, so this only needs to look the referenced media up, mirroring {@link #resolveClipMediaEntries}.
     */
    private MotionSpecResolution resolveMotionForRender(String projectId, Creative creative) {
        CreativeMotion motion = toMotion(creative.getMotion());
        CreativeAudio audio = toAudio(creative.getAudio());
        CreativeMotionBackground background = motion != null ? motion.getBackground() : null;

        CreativePhoto clipMedia = background != null && background.getClipMediaId() != null
                ? photoRepository.findByIdAndProjectId(background.getClipMediaId(), projectId).orElse(null) : null;
        CreativePhoto trackMedia = audio != null && audio.getTrackId() != null
                ? photoRepository.findByIdAndProjectId(audio.getTrackId(), projectId).orElse(null) : null;

        CreativeValidator.MotionInput input = motion == null ? null : new CreativeValidator.MotionInput(
                motion.getPreset(),
                motion.getDurationSec() != null ? motion.getDurationSec().doubleValue() : null,
                background != null ? background.getSource() : null,
                background != null ? background.getMotion() : null,
                background != null ? background.getClipMediaId() : null,
                background == null || background.getClipMediaId() == null || clipMedia != null,
                clipMedia != null && clipMedia.isVideo(),
                clipMedia != null && clipMedia.isUploaded(),
                clipMedia != null && clipMedia.isBlocked(),
                clipMedia != null && clipMedia.getDurationSeconds() != null ? clipMedia.getDurationSeconds().doubleValue() : null,
                clipMedia != null ? clipMedia.getHasAudio() : null,
                background != null && background.getClipStartSec() != null ? background.getClipStartSec().doubleValue() : null,
                motion.getEndCard(),
                audio != null ? audio.getSource() : null,
                audio != null ? audio.getTrackId() : null,
                audio == null || audio.getTrackId() == null || trackMedia != null,
                trackMedia != null && trackMedia.isAudio(),
                trackMedia != null && trackMedia.isUploaded(),
                trackMedia != null && trackMedia.isBlocked(),
                audio != null && audio.getVolume() != null ? audio.getVolume().doubleValue() : null,
                audio != null && audio.getFadeOutSec() != null ? audio.getFadeOutSec().doubleValue() : null);

        return new MotionSpecResolution(motion, audio, clipMedia, trackMedia, input);
    }

    /** {@code motion}/{@code audio}, plus the media the render spec needs signed URLs for. */
    private record MotionSpecResolution(CreativeMotion motion, CreativeAudio audio, CreativePhoto backgroundClip,
                                        CreativePhoto audioTrack, CreativeValidator.MotionInput input) {
    }

    /**
     * CLIP renders are assembled here, not on the user's machine (COND-24 PR1, contract "Renders for
     * CLIP"): a real video would take real ffmpeg work no local Playwright job does, so the backend just
     * copies the chosen media object(s) straight into the render's own path and records one frame per
     * placement the clip set covers. The render is created — and settles — SUCCEEDED in this one call;
     * there is no RUNNING interval and no frame PUT.
     */
    private CreateRenderResult requestClipRender(String projectId, Creative creative, CreateCreativeRenderRequest request,
                                                 User caller) {
        if (request != null && Boolean.TRUE.equals(request.getPreviewOnly())) {
            throw new UnprocessableEntityException(
                    "CLIP renders are assembled directly from the chosen media; there is no preview-only sheet");
        }
        Map<String, String> clipMedia = toClipMediaMap(creative.getClipMedia());
        BrandKit kit = requireKit(projectId, creative.getBrandKitId());
        List<CreativeValidationException.Violation> violations = validator.validate(kit, new CreativeValidator.Input(
                creative.getLayout(), creative.getTheme(), toStringList(creative.getPlacements()),
                creative.getHeadline(), creative.getBody(), creative.getCaption(),
                // Force the READY-only checks (caption + at least one clip) regardless of the Creative's
                // own state, exactly as the STILL path forces its own READY checks above.
                Creative.STATE_READY, null, true, true, true, false, creative.getSequenceKind(), List.of(),
                creative.getCarouselRatio(), Set.of(), Map.of(), Map.of(),
                Creative.KIND_CLIP, resolveClipMediaEntries(projectId, clipMedia), null));
        if (!violations.isEmpty()) {
            throw new CreativeValidationException(violations);
        }

        Map<String, String> framePlacements = resolveClipFramePlacements(projectId, clipMedia);

        CreativeRender render = new CreativeRender();
        render.setProjectId(projectId);
        render.setCreativeId(creative.getId());
        render.setCreativeVersion(creative.getVersion());
        render.setState(CreativeRender.STATE_SUCCEEDED);
        render.setPreviewOnly(false);
        render.setRenderer(request != null ? request.getRenderer() : null);
        render.setWorkflowRunId(request != null ? request.getWorkflowRunId() : null);
        render.setRequestedBy(caller.getId());
        render.setRequestedAt(OffsetDateTime.now());
        render.setFinishedAt(OffsetDateTime.now());
        render = renderRepository.save(render);

        List<CreativeRenderFrame> frames = new ArrayList<>();
        for (Map.Entry<String, String> entry : framePlacements.entrySet()) {
            String placementKey = entry.getKey();
            CreativePhoto media = photoRepository.findByIdAndProjectId(entry.getValue(), projectId)
                    .orElseThrow(() -> new BusinessException("No media with id " + entry.getValue() + " in this project"));

            String destPath = clipFramePath(projectId, creative.getId(), render.getId(), placementKey, media.getContentType());
            storageService.copy(media.getGcsPath(), destPath);
            String posterDestPath = null;
            if (media.getPosterGcsPath() != null) {
                posterDestPath = clipFramePosterPath(projectId, creative.getId(), render.getId(), placementKey);
                storageService.copy(media.getPosterGcsPath(), posterDestPath);
            }

            CreativeRenderFrame frame = new CreativeRenderFrame();
            frame.setRenderId(render.getId());
            frame.setCreativeId(creative.getId());
            frame.setPlacementKey(placementKey);
            frame.setPlatform(registry.hasPlacement(placementKey) ? registry.placements().get(placementKey).platform() : null);
            frame.setGcsPath(destPath);
            frame.setContentType(media.getContentType());
            frame.setWidth(media.getWidth() != null ? media.getWidth() : 0);
            frame.setHeight(media.getHeight() != null ? media.getHeight() : 0);
            frame.setSizeBytes(media.getSizeBytes());
            frame.setDurationSeconds(media.getDurationSeconds());
            frame.setHasAudio(media.getHasAudio());
            frame.setPosterGcsPath(posterDestPath);
            frame.setWarnings(objectMapper.createArrayNode());
            frameRepository.save(frame);
            frames.add(frame);
        }

        return new CreateRenderResult(render, null, frames);
    }

    private String clipFramePath(String projectId, String creativeId, String renderId, String placementKey, String contentType) {
        return "projects/" + projectId + "/creatives/" + creativeId + "/renders/" + renderId + "/" + placementKey
                + "." + videoExtensionFor(contentType);
    }

    private String clipFramePosterPath(String projectId, String creativeId, String renderId, String placementKey) {
        return "projects/" + projectId + "/creatives/" + creativeId + "/renders/" + renderId + "/" + placementKey + "-poster.jpg";
    }

    private static String videoExtensionFor(String contentType) {
        return switch (contentType == null ? "" : contentType) {
            case "video/quicktime" -> "mov";
            case "video/webm" -> "webm";
            default -> "mp4";
        };
    }

    /**
     * One frame per placement a CLIP creative's {@code clipMedia} covers: an explicit key (other than
     * {@code "default"}) names its own placement directly; {@code "default"}'s media resolves to the
     * registry placement whose aspect ratio is closest to its own (excluding {@code story}, whose aspect
     * is already covered by {@code 9x16} — see {@link #nearestPlacementFor}), and only fills that
     * placement in when no explicit entry already claims it.
     */
    Map<String, String> resolveClipFramePlacements(String projectId, Map<String, String> clipMedia) {
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : clipMedia.entrySet()) {
            if (!"default".equals(entry.getKey()) && registry.hasPlacement(entry.getKey())) {
                resolved.put(entry.getKey(), entry.getValue());
            }
        }
        String defaultMediaId = clipMedia.get("default");
        if (defaultMediaId != null) {
            CreativePhoto defaultMedia = photoRepository.findByIdAndProjectId(defaultMediaId, projectId).orElse(null);
            if (defaultMedia != null && defaultMedia.getWidth() != null && defaultMedia.getHeight() != null
                    && defaultMedia.getHeight() > 0) {
                String nearest = nearestPlacementFor((double) defaultMedia.getWidth() / defaultMedia.getHeight());
                if (nearest != null) {
                    resolved.putIfAbsent(nearest, defaultMediaId);
                }
            }
        }
        return resolved;
    }

    /** The registry placement (excluding {@code story}) whose aspect ratio is nearest {@code videoAspect}. */
    String nearestPlacementFor(double videoAspect) {
        String best = null;
        double bestDiff = Double.MAX_VALUE;
        for (Map.Entry<String, CreativeRegistry.PlacementInfo> entry : registry.placements().entrySet()) {
            if ("story".equals(entry.getKey()) || entry.getValue().height() <= 0) {
                continue;
            }
            double placementAspect = (double) entry.getValue().width() / entry.getValue().height();
            double diff = Math.abs(placementAspect - videoAspect);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = entry.getKey();
            }
        }
        return best;
    }

    /** Mirrors {@code CreativeService#resolveClipMedia} — this service also needs it to run the CLIP
     *  structural rules at render time, and has no dependency on {@code CreativeService} to reuse from. */
    private List<CreativeValidator.ClipMediaEntry> resolveClipMediaEntries(String projectId, Map<String, String> clipMedia) {
        List<CreativeValidator.ClipMediaEntry> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : clipMedia.entrySet()) {
            String mediaId = entry.getValue();
            CreativePhoto media = mediaId != null ? photoRepository.findByIdAndProjectId(mediaId, projectId).orElse(null) : null;
            entries.add(new CreativeValidator.ClipMediaEntry(entry.getKey(), mediaId, media != null,
                    media != null && media.isVideo(), media != null && media.isUploaded(),
                    media != null && media.isBlocked()));
        }
        return entries;
    }

    /** Mirrors {@code CreativeService#toClipMediaMap}. */
    private Map<String, String> toClipMediaMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return objectMapper.convertValue(node, new TypeReference<Map<String, String>>() {
        });
    }

    @Transactional
    public RenderView putFrame(String projectId, String creativeId, String renderId, String placementKey,
                               Integer index, int width, int height, byte[] frameBytes, String contentType,
                               User caller) {
        return putFrame(projectId, creativeId, renderId, placementKey, index, width, height, frameBytes,
                contentType, null, null, caller);
    }

    /** COND-24 PR2: {@code durationSeconds}/{@code hasAudio} are stored on a MOTION (video/mp4) frame only
     *  — ignored (and left null) for an image frame, exactly as the query params are documented. */
    @Transactional
    public RenderView putFrame(String projectId, String creativeId, String renderId, String placementKey,
                               Integer index, int width, int height, byte[] frameBytes, String contentType,
                               BigDecimal durationSeconds, Boolean hasAudio, User caller) {
        requireEditor(projectId, caller);
        CreativeRender render = findRenderInCreative(projectId, creativeId, renderId);
        if (!render.isRunning()) {
            throw new ConflictException("Render " + renderId + " is no longer RUNNING (" + render.getState() + ")");
        }
        if (frameBytes == null || frameBytes.length == 0) {
            throw new BusinessException("Frame body must not be empty");
        }
        String normalizedContentType = AssetUploadPolicy.normalizeContentType(contentType);
        if (!ALLOWED_FRAME_CONTENT_TYPES.contains(normalizedContentType)) {
            throw new UnprocessableEntityException("Content type '" + contentType + "' is not allowed for a render"
                    + " frame. Allowed types: " + ALLOWED_FRAME_CONTENT_TYPES.stream().sorted().toList());
        }
        boolean video = CONTENT_TYPE_MP4.equals(normalizedContentType);
        long maxBytes = video ? MAX_VIDEO_FRAME_BYTES : MAX_FRAME_BYTES;
        if (frameBytes.length > maxBytes) {
            throw new BusinessException("Frame is " + frameBytes.length + " bytes, over the "
                    + (maxBytes / (1024 * 1024)) + " MB ceiling");
        }
        if (width <= 0 || height <= 0) {
            throw new BusinessException("width and height must be positive");
        }
        boolean sheet = CreativeRenderFrame.PLACEMENT_SHEET.equals(placementKey);
        if (!sheet && !registry.hasPlacement(placementKey)) {
            throw new BusinessException("Unknown placement: " + placementKey);
        }
        String platform = sheet ? null : registry.placements().get(placementKey).platform();

        String gcsPath = framePath(projectId, creativeId, renderId, placementKey, index, normalizedContentType);
        storageService.upload(gcsPath, frameBytes, normalizedContentType);

        CreativeRenderFrame frame = findExistingFrame(renderId, placementKey, index).orElseGet(CreativeRenderFrame::new);
        frame.setRenderId(renderId);
        frame.setCreativeId(creativeId);
        frame.setPlacementKey(placementKey);
        frame.setPlatform(platform);
        frame.setSequenceIndex(index);
        frame.setGcsPath(gcsPath);
        frame.setContentType(normalizedContentType);
        frame.setWidth(width);
        frame.setHeight(height);
        frame.setSizeBytes(frameBytes.length);
        frame.setDurationSeconds(video ? durationSeconds : null);
        frame.setHasAudio(video ? hasAudio : null);
        if (frame.getWarnings() == null) {
            frame.setWarnings(objectMapper.createArrayNode());
        }
        frameRepository.save(frame);
        return new RenderView(render, frameRepository.findAllByRenderIdOrdered(renderId));
    }

    /**
     * COND-24 PR2: sets a MOTION frame's poster (the end-card JPEG) — the local job PUTs this once the
     * frame itself is stored, mirroring how a CLIP frame's poster is a copy of its source media's. Refused
     * with {@link BusinessException} when no frame is stored yet for this placement/index (a plain 400: the
     * job called this out of order, not a semantic validation failure).
     */
    @Transactional
    public RenderView putFramePoster(String projectId, String creativeId, String renderId, String placementKey,
                                     Integer index, byte[] posterBytes, String contentType, User caller) {
        requireEditor(projectId, caller);
        CreativeRender render = findRenderInCreative(projectId, creativeId, renderId);
        if (!render.isRunning()) {
            throw new ConflictException("Render " + renderId + " is no longer RUNNING (" + render.getState() + ")");
        }
        if (posterBytes == null || posterBytes.length == 0) {
            throw new BusinessException("Poster body must not be empty");
        }
        String normalizedContentType = AssetUploadPolicy.normalizeContentType(contentType);
        if (!CONTENT_TYPE_JPEG.equals(normalizedContentType)) {
            throw new UnprocessableEntityException("Content type '" + contentType
                    + "' is not allowed for a render frame poster. Allowed types: [" + CONTENT_TYPE_JPEG + "]");
        }
        if (posterBytes.length > MAX_FRAME_BYTES) {
            throw new BusinessException("Poster is " + posterBytes.length + " bytes, over the 20 MB ceiling");
        }
        CreativeRenderFrame frame = findExistingFrame(renderId, placementKey, index)
                .orElseThrow(() -> new BusinessException("No frame stored yet at placement " + placementKey
                        + (index != null ? " index " + index : "") + " to attach a poster to"));

        String posterPath = posterFramePath(projectId, creativeId, renderId, placementKey, index);
        storageService.upload(posterPath, posterBytes, CONTENT_TYPE_JPEG);
        frame.setPosterGcsPath(posterPath);
        frameRepository.save(frame);
        return new RenderView(render, frameRepository.findAllByRenderIdOrdered(renderId));
    }

    private String posterFramePath(String projectId, String creativeId, String renderId, String placementKey, Integer index) {
        String suffix = index != null ? "-" + index : "";
        return "projects/" + projectId + "/creatives/" + creativeId + "/renders/" + renderId + "/" + placementKey
                + suffix + "-poster.jpg";
    }

    @Transactional
    public RenderView completeRender(String projectId, String creativeId, String renderId,
                                     CompleteCreativeRenderRequest request, User caller) {
        requireEditor(projectId, caller);
        CreativeRender render = findRenderInCreative(projectId, creativeId, renderId);
        if (!render.isRunning()) {
            throw new ConflictException("Render " + renderId + " is no longer RUNNING (" + render.getState() + ")");
        }
        render.setState(CreativeRender.STATE_SUCCEEDED);
        render.setFinishedAt(OffsetDateTime.now());
        render = renderRepository.save(render);

        List<CreativeRenderFrame> frames = frameRepository.findAllByRenderIdOrdered(renderId);
        if (request != null && request.getWarnings() != null) {
            applyWarnings(frames, request.getWarnings());
        }
        return new RenderView(render, frames);
    }

    @Transactional
    public RenderView failRender(String projectId, String creativeId, String renderId,
                                 FailCreativeRenderRequest request, User caller) {
        requireEditor(projectId, caller);
        CreativeRender render = findRenderInCreative(projectId, creativeId, renderId);
        if (!render.isRunning()) {
            throw new ConflictException("Render " + renderId + " is no longer RUNNING (" + render.getState() + ")");
        }
        // AC-P0-3.2: a failed render leaves no frame behind for attach to find.
        for (CreativeRenderFrame frame : frameRepository.findAllByRenderId(renderId)) {
            try {
                storageService.delete(frame.getGcsPath());
            } catch (RuntimeException e) {
                log.warn("Could not delete frame object {} for failed render {}: {}",
                        frame.getGcsPath(), renderId, e.toString());
            }
        }
        frameRepository.deleteAllByRenderId(renderId);

        render.setState(CreativeRender.STATE_FAILED);
        render.setError(request.getMessage());
        render.setLog(request.getLog());
        render.setFinishedAt(OffsetDateTime.now());
        render = renderRepository.save(render);
        return new RenderView(render, List.of());
    }

    @Transactional
    public RenderView getRender(String projectId, String creativeId, String renderId, User caller) {
        requireMember(projectId, caller);
        CreativeRender render = findRenderInCreative(projectId, creativeId, renderId);
        applyLazyTimeout(render);
        return new RenderView(render, frameRepository.findAllByRenderIdOrdered(renderId));
    }

    @Transactional
    public List<RenderView> listRenders(String projectId, String creativeId, User caller) {
        requireMember(projectId, caller);
        findCreative(projectId, creativeId);
        List<CreativeRender> renders = renderRepository.findAllByCreativeIdOrderByRequestedAtDesc(
                creativeId, PageRequest.of(0, MAX_RENDERS_LISTED));
        renders.forEach(this::applyLazyTimeout);
        List<RenderView> views = new ArrayList<>();
        for (CreativeRender render : renders) {
            views.add(new RenderView(render, frameRepository.findAllByRenderIdOrdered(render.getId())));
        }
        return views;
    }

    /** The render {@code CreativeResponse.latestRender}/{@code latestRenderId} report — a real DB read, not a view already loaded. */
    @Transactional(readOnly = true)
    public Optional<RenderView> latestSucceededRender(String creativeId) {
        return renderRepository.findFirstByCreativeIdAndStateAndPreviewOnlyFalseOrderByRequestedAtDesc(
                        creativeId, CreativeRender.STATE_SUCCEEDED)
                .map(render -> new RenderView(render, frameRepository.findAllByRenderIdOrdered(render.getId())));
    }

    // ── response assembly ────────────────────────────────────────────────────────────────────────

    public CreativeRenderResponse toResponse(RenderView view) {
        return toResponse(view, null);
    }

    public CreativeRenderResponse toResponse(RenderView view, CreativeRenderSpec spec) {
        CreativeRender render = view.render();
        CreativeRenderResponse response = new CreativeRenderResponse(render.getId(),
                CreativeRenderState.fromValue(render.getState()), render.isPreviewOnly(), render.getCreativeVersion(),
                render.getRequestedAt(), view.frames().stream().map(this::toFrameResponse).toList())
                .renderer(render.getRenderer())
                .workflowRunId(render.getWorkflowRunId())
                .finishedAt(render.getFinishedAt())
                .error(render.getError())
                .spec(spec);
        return response;
    }

    private CreativeRenderFrameResponse toFrameResponse(CreativeRenderFrame frame) {
        return new CreativeRenderFrameResponse(frame.getId(), frame.getPlacementKey(),
                storageService.generateSignedUrl(frame.getGcsPath(), FRAME_URL_EXPIRY_MINUTES),
                frame.getWidth(), frame.getHeight(), frame.getSizeBytes(), frame.getContentType(),
                toStringListOfWarnings(frame.getWarnings()))
                .platform(frame.getPlatform())
                .sequenceIndex(frame.getSequenceIndex())
                .durationSeconds(frame.getDurationSeconds())
                .hasAudio(frame.getHasAudio())
                .posterUrl(frame.getPosterGcsPath() != null
                        ? storageService.generateSignedUrl(frame.getPosterGcsPath(), FRAME_URL_EXPIRY_MINUTES) : null);
    }

    /** The image {@code latestRenderThumbnailUrl} shows: the 4x5 frame, else the first; a video frame's poster. */
    public String thumbnailUrl(RenderView view) {
        if (view == null || view.frames().isEmpty()) {
            return null;
        }
        CreativeRenderFrame frame = view.frames().stream()
                .filter(f -> "4x5".equals(f.getPlacementKey()))
                .findFirst()
                .orElseGet(() -> view.frames().get(0));
        // A thumbnail is an <img>: a video frame shows its poster (null when it has none, so the caller falls back).
        boolean video = frame.getContentType() != null && frame.getContentType().startsWith("video/");
        String path = video ? frame.getPosterGcsPath() : frame.getGcsPath();
        return path != null ? storageService.generateSignedUrl(path, FRAME_URL_EXPIRY_MINUTES) : null;
    }

    // ── spec building ────────────────────────────────────────────────────────────────────────────

    private CreativeRenderSpec buildSpec(CreativeRender render, Creative creative, BrandKit kit,
                                         CreativePhoto mainPhoto, List<String> placements, MotionSpecResolution motionRes) {
        CreativeRenderSpecBrand brand = new CreativeRenderSpecBrand(toStringMap(kit.getTokens()),
                toStringList(kit.getEnabledPlacements()))
                .fontFamily(kit.getFontFamily())
                .fontUrl(kit.getFontUrl())
                .ctaClaim(kit.getCtaClaim())
                .logos(new CreativeRenderSpecLogos()
                        .mark(signedOrNull(kit.getMarkGcsPath()))
                        .wordmarkDark(signedOrNull(kit.getWordmarkDarkGcsPath()))
                        .wordmarkLight(signedOrNull(kit.getWordmarkLightGcsPath()))
                        .badge(signedOrNull(kit.getBadgeGcsPath())));

        List<CreativeRenderSpecBeat> beats = new ArrayList<>();
        for (SequenceBeat beat : toSequenceBeats(creative.getSequence())) {
            CreativePhoto beatPhoto = beat.getPhotoId() != null
                    ? photoRepository.findByIdAndProjectId(beat.getPhotoId(), creative.getProjectId()).orElse(mainPhoto)
                    : mainPhoto;
            beats.add(new CreativeRenderSpecBeat()
                    .headline(beat.getHeadline())
                    .body(beat.getBody())
                    .cta(beat.getCta())
                    .photoUrl(photoUrl(beatPhoto))
                    .focal(toStringMap(beatPhoto != null ? beatPhoto.getFocal() : null)));
        }

        CreativeRenderSpecCreative creativeSpec = new CreativeRenderSpecCreative(creative.getLayout(),
                CreativeTheme.fromValue(creative.getTheme()), toStringList(creative.getPlacements()),
                toTypeOverrides(creative.getTypeOverrides()), toStringMap(mainPhoto != null ? mainPhoto.getFocal() : null))
                .headline(creative.getHeadline())
                .body(creative.getBody())
                .caption(creative.getCaption())
                .focalOverride(creative.getFocalOverride() != null ? toStringMap(creative.getFocalOverride()) : null)
                .sequenceKind(creative.getSequenceKind() != null ? SequenceKind.fromValue(creative.getSequenceKind()) : null)
                .sequence(beats)
                .photoUrl(photoUrl(mainPhoto))
                .lockup(CreativeLockup.fromValue(creative.getLockup()))
                .layoutOverrides(toLayoutOverrides(creative.getLayoutOverrides()))
                .kind(CreativeKind.fromValue(creative.getKind()));

        if (motionRes != null && motionRes.motion() != null) {
            creativeSpec.motion(buildMotionSpec(motionRes))
                    .audio(buildAudioSpec(motionRes))
                    .clipHasAudio(motionRes.backgroundClip() != null ? motionRes.backgroundClip().getHasAudio() : null);
        }

        return new CreativeRenderSpec(render.getId(), render.isPreviewOnly(), creativeSpec, brand, placements);
    }

    static final String DEFAULT_CAROUSEL_RATIO = "4x5";

    /** kit enabled ∪ creative opt-ins ∩ registry — except a sequence Creative, which renders only its own shape. */
    List<String> resolvePlacements(BrandKit kit, Creative creative) {
        if ("story".equals(creative.getSequenceKind())) {
            return List.of("story");
        }
        if ("carousel".equals(creative.getSequenceKind())) {
            // nexus's default: a carousel with no ratio set is a 4:5 Instagram carousel.
            return List.of(creative.getCarouselRatio() != null ? creative.getCarouselRatio() : DEFAULT_CAROUSEL_RATIO);
        }
        Set<String> union = new LinkedHashSet<>(toStringList(kit.getEnabledPlacements()));
        union.addAll(toStringList(creative.getPlacements()));
        union.retainAll(registry.placements().keySet());
        return List.copyOf(union);
    }

    private String photoUrl(CreativePhoto photo) {
        return photo != null && photo.isUploaded() ? storageService.generateSignedUrl(photo.getGcsPath(), SPEC_URL_EXPIRY_MINUTES) : null;
    }

    private String signedOrNull(String gcsPath) {
        return gcsPath != null ? storageService.generateSignedUrl(gcsPath, SPEC_URL_EXPIRY_MINUTES) : null;
    }

    /** Contract: "motion.background.clipUrl (VIDEO media)" — a signed GET, present only when the resolved
     *  background clip has actually finished uploading (mirrors {@link #photoUrl}). */
    private CreativeRenderSpecMotion buildMotionSpec(MotionSpecResolution motionRes) {
        CreativeMotion motion = motionRes.motion();
        CreativeMotionBackground background = motion.getBackground();
        CreativePhoto clip = motionRes.backgroundClip();
        CreativeRenderSpecMotionBackground specBackground = new CreativeRenderSpecMotionBackground()
                .source(background != null ? background.getSource() : null)
                .motion(background != null ? background.getMotion() : null)
                .clipUrl(clip != null && clip.isUploaded() ? signedOrNull(clip.getGcsPath()) : null)
                .clipStartSec(background != null ? background.getClipStartSec() : null);
        return new CreativeRenderSpecMotion()
                .preset(motion.getPreset())
                .durationSec(motion.getDurationSec())
                .background(specBackground)
                .endCard(motion.getEndCard());
    }

    /** Contract: "audio.trackUrl (AUDIO media)" — a signed GET, present only for a track that finished uploading. */
    private CreativeRenderSpecAudio buildAudioSpec(MotionSpecResolution motionRes) {
        CreativeAudio audio = motionRes.audio();
        if (audio == null) {
            return null;
        }
        CreativePhoto track = motionRes.audioTrack();
        return new CreativeRenderSpecAudio()
                .source(audio.getSource())
                .trackUrl(track != null && track.isUploaded() ? signedOrNull(track.getGcsPath()) : null)
                .volume(audio.getVolume())
                .fadeOutSec(audio.getFadeOutSec());
    }

    /** {@code creative.motion} JSON -&gt; the typed DTO, or null when unset. Mirrors {@code CreativeService#toMotion}. */
    private CreativeMotion toMotion(JsonNode node) {
        return node != null && !node.isNull() ? objectMapper.convertValue(node, CreativeMotion.class) : null;
    }

    /** {@code creative.audio} JSON -&gt; the typed DTO, or null when unset. Mirrors {@code CreativeService#toAudio}. */
    private CreativeAudio toAudio(JsonNode node) {
        return node != null && !node.isNull() ? objectMapper.convertValue(node, CreativeAudio.class) : null;
    }

    // ── frame upsert / lazy timeout / warnings ──────────────────────────────────────────────────

    private Optional<CreativeRenderFrame> findExistingFrame(String renderId, String placementKey, Integer index) {
        return frameRepository.findAllByRenderId(renderId).stream()
                .filter(f -> f.getPlacementKey().equals(placementKey) && java.util.Objects.equals(f.getSequenceIndex(), index))
                .findFirst();
    }

    private void applyWarnings(List<CreativeRenderFrame> frames, List<CreativeRenderWarning> warnings) {
        for (CreativeRenderWarning warning : warnings) {
            frames.stream()
                    .filter(f -> f.getPlacementKey().equals(warning.getPlacementKey())
                            && java.util.Objects.equals(f.getSequenceIndex(), warning.getIndex()))
                    .findFirst()
                    .ifPresent(frame -> {
                        com.fasterxml.jackson.databind.node.ArrayNode array = frame.getWarnings() != null
                                && frame.getWarnings().isArray()
                                ? ((com.fasterxml.jackson.databind.node.ArrayNode) frame.getWarnings()).deepCopy()
                                : objectMapper.createArrayNode();
                        array.add(warning.getMessage());
                        frame.setWarnings(array);
                        frameRepository.save(frame);
                    });
        }
    }

    /**
     * Flips a stale {@code RUNNING} row to {@code FAILED} "timed out" — read-triggered, never scheduled.
     * A local job that died mid-render (a closed laptop, a killed process) would otherwise leave the row
     * RUNNING forever; the next person to look at it discovers and records the timeout instead.
     */
    private void applyLazyTimeout(CreativeRender render) {
        if (!render.isRunning()) {
            return;
        }
        OffsetDateTime cutoff = OffsetDateTime.now().minusMinutes(RENDER_TIMEOUT_MINUTES);
        if (render.getRequestedAt() != null && render.getRequestedAt().isBefore(cutoff)) {
            render.setState(CreativeRender.STATE_FAILED);
            render.setError("timed out");
            render.setFinishedAt(OffsetDateTime.now());
            renderRepository.save(render);
        }
    }

    private String framePath(String projectId, String creativeId, String renderId, String placementKey, Integer index,
                             String contentType) {
        String suffix = index != null ? "-" + index : "";
        String extension = switch (contentType == null ? "" : contentType) {
            case CONTENT_TYPE_JPEG -> "jpg";
            case CONTENT_TYPE_MP4 -> "mp4";
            default -> "png";
        };
        return "projects/" + projectId + "/creatives/" + creativeId + "/renders/" + renderId + "/" + placementKey
                + suffix + "." + extension;
    }

    // ── lookups ──────────────────────────────────────────────────────────────────────────────────

    Creative findCreative(String projectId, String creativeId) {
        return creativeRepository.findByIdAndProjectId(creativeId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Creative not found"));
    }

    private BrandKit requireKit(String projectId, String brandKitId) {
        return brandKitRepository.findByIdAndProjectId(brandKitId, projectId)
                .orElseThrow(() -> new BusinessException("No Brand Kit with id " + brandKitId + " in this project"));
    }

    CreativeRender findRenderInCreative(String projectId, String creativeId, String renderId) {
        findCreative(projectId, creativeId);
        return renderRepository.findByIdAndCreativeId(renderId, creativeId)
                .filter(r -> projectId.equals(r.getProjectId()))
                .orElseThrow(() -> new EntityNotFoundException("Render not found"));
    }

    // ── JSON <-> typed helpers (mirrors CreativeService's own private copies) ──────────────────────

    private List<String> toStringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        return objectMapper.convertValue(node, new TypeReference<List<String>>() {
        });
    }

    private Map<String, String> toStringMap(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        if (node != null) {
            node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
        }
        return map;
    }

    private Map<String, List<BigDecimal>> toTypeOverrides(JsonNode node) {
        return node != null ? objectMapper.convertValue(node, new TypeReference<Map<String, List<BigDecimal>>>() {
        }) : Map.of();
    }

    private CreativeLayoutOverrides toLayoutOverrides(JsonNode node) {
        return node != null ? objectMapper.convertValue(node, CreativeLayoutOverrides.class) : null;
    }

    private Map<String, Integer> extractOverrideInts(JsonNode layoutOverrides, String key) {
        if (layoutOverrides == null || !layoutOverrides.hasNonNull(key)) {
            return Map.of();
        }
        return objectMapper.convertValue(layoutOverrides.get(key), new TypeReference<Map<String, Integer>>() {
        });
    }

    private List<SequenceBeat> toSequenceBeats(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        return objectMapper.convertValue(node, new TypeReference<List<SequenceBeat>>() {
        });
    }

    private List<CreativeValidator.SequenceBeat> toValidatorBeats(List<SequenceBeat> beats) {
        List<CreativeValidator.SequenceBeat> result = new ArrayList<>();
        for (SequenceBeat beat : beats) {
            result.add(new CreativeValidator.SequenceBeat(beat.getHeadline(), beat.getBody(), beat.getPhotoId()));
        }
        return result;
    }

    private List<String> toStringListOfWarnings(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> warnings = new ArrayList<>();
        node.forEach(n -> warnings.add(n.asText()));
        return warnings;
    }

    // ── auth ─────────────────────────────────────────────────────────────────────────────────────

    private void requireMember(String projectId, User caller) {
        if (!projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Project not found");
        }
    }

    private void requireEditor(String projectId, User caller) {
        requireMember(projectId, caller);
        if (!projectSecurityService.isAdminOrCreator(projectId, caller.getId())) {
            throw new ForbiddenException("Only ADMIN or CREATOR can manage Creative renders");
        }
    }
}
