package com.conductor.creative;

import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.exception.UnprocessableEntityException;
import com.conductor.generated.v2.model.CompleteCreativeRenderRequest;
import com.conductor.generated.v2.model.CreateCreativeRenderRequest;
import com.conductor.generated.v2.model.CreativeRenderFrameResponse;
import com.conductor.generated.v2.model.CreativeRenderResponse;
import com.conductor.generated.v2.model.CreativeRenderSpec;
import com.conductor.generated.v2.model.CreativeRenderSpecBeat;
import com.conductor.generated.v2.model.CreativeLayoutOverrides;
import com.conductor.generated.v2.model.CreativeLockup;
import com.conductor.generated.v2.model.CreativeRenderSpecBrand;
import com.conductor.generated.v2.model.CreativeRenderSpecCreative;
import com.conductor.generated.v2.model.CreativeRenderSpecLogos;
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
    /** A placement frame is JPEG (Instagram feed images and TikTok photo posts both refuse PNG); the
     * `sheet` contact sheet of a preview-only render stays PNG. See {@code docs/creatives.md}. */
    static final String CONTENT_TYPE_JPEG = "image/jpeg";
    static final String CONTENT_TYPE_PNG = "image/png";
    private static final Set<String> ALLOWED_FRAME_CONTENT_TYPES = Set.of(CONTENT_TYPE_JPEG, CONTENT_TYPE_PNG);
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

    public record CreateRenderResult(CreativeRender render, CreativeRenderSpec spec) {
    }

    @Transactional
    public CreateRenderResult requestRender(String projectId, String creativeId, CreateCreativeRenderRequest request,
                                            User caller) {
        requireEditor(projectId, caller);
        Creative creative = findCreative(projectId, creativeId);
        BrandKit kit = requireKit(projectId, creative.getBrandKitId());
        CreativePhoto mainPhoto = creative.getPhotoId() != null
                ? photoRepository.findByIdAndProjectId(creative.getPhotoId(), projectId).orElse(null) : null;

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
                extractOverrideInts(creative.getLayoutOverrides(), "padBottom")));
        if (!violations.isEmpty()) {
            throw new CreativeValidationException(violations);
        }

        List<String> placements = resolvePlacements(kit, creative);

        CreativeRender render = new CreativeRender();
        render.setProjectId(projectId);
        render.setCreativeId(creativeId);
        render.setCreativeVersion(creative.getVersion());
        render.setState(CreativeRender.STATE_RUNNING);
        render.setPreviewOnly(request != null && Boolean.TRUE.equals(request.getPreviewOnly()));
        render.setRenderer(request != null ? request.getRenderer() : null);
        render.setWorkflowRunId(request != null ? request.getWorkflowRunId() : null);
        render.setRequestedBy(caller.getId());
        render.setRequestedAt(OffsetDateTime.now());
        render = renderRepository.save(render);

        CreativeRenderSpec spec = buildSpec(render, creative, kit, mainPhoto, placements);
        return new CreateRenderResult(render, spec);
    }

    @Transactional
    public RenderView putFrame(String projectId, String creativeId, String renderId, String placementKey,
                               Integer index, int width, int height, byte[] frameBytes, String contentType,
                               User caller) {
        requireEditor(projectId, caller);
        CreativeRender render = findRenderInCreative(projectId, creativeId, renderId);
        if (!render.isRunning()) {
            throw new ConflictException("Render " + renderId + " is no longer RUNNING (" + render.getState() + ")");
        }
        if (frameBytes == null || frameBytes.length == 0) {
            throw new BusinessException("Frame body must not be empty");
        }
        if (frameBytes.length > MAX_FRAME_BYTES) {
            throw new BusinessException("Frame is " + frameBytes.length + " bytes, over the 20 MB ceiling");
        }
        if (width <= 0 || height <= 0) {
            throw new BusinessException("width and height must be positive");
        }
        String normalizedContentType = AssetUploadPolicy.normalizeContentType(contentType);
        if (!ALLOWED_FRAME_CONTENT_TYPES.contains(normalizedContentType)) {
            throw new UnprocessableEntityException("Content type '" + contentType + "' is not allowed for a render"
                    + " frame. Allowed types: " + ALLOWED_FRAME_CONTENT_TYPES.stream().sorted().toList());
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
        if (frame.getWarnings() == null) {
            frame.setWarnings(objectMapper.createArrayNode());
        }
        frameRepository.save(frame);
        return new RenderView(render, frameRepository.findAllByRenderIdOrdered(renderId));
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
                frame.getWidth(), frame.getHeight(), frame.getSizeBytes(), toStringListOfWarnings(frame.getWarnings()))
                .platform(frame.getPlatform())
                .sequenceIndex(frame.getSequenceIndex());
    }

    /** The frame {@code latestRenderThumbnailUrl} shows: the 4x5 frame, else the first frame. */
    public String thumbnailUrl(RenderView view) {
        if (view == null || view.frames().isEmpty()) {
            return null;
        }
        CreativeRenderFrame frame = view.frames().stream()
                .filter(f -> "4x5".equals(f.getPlacementKey()))
                .findFirst()
                .orElseGet(() -> view.frames().get(0));
        return storageService.generateSignedUrl(frame.getGcsPath(), FRAME_URL_EXPIRY_MINUTES);
    }

    // ── spec building ────────────────────────────────────────────────────────────────────────────

    private CreativeRenderSpec buildSpec(CreativeRender render, Creative creative, BrandKit kit,
                                         CreativePhoto mainPhoto, List<String> placements) {
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
                .layoutOverrides(toLayoutOverrides(creative.getLayoutOverrides()));

        return new CreativeRenderSpec(render.getId(), render.isPreviewOnly(), creativeSpec, brand, placements);
    }

    /** kit enabled ∪ creative opt-ins ∩ registry — except a sequence Creative, which renders only its own shape. */
    List<String> resolvePlacements(BrandKit kit, Creative creative) {
        if ("story".equals(creative.getSequenceKind())) {
            return List.of("story");
        }
        if ("carousel".equals(creative.getSequenceKind())) {
            return creative.getCarouselRatio() != null ? List.of(creative.getCarouselRatio()) : List.of();
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
        String extension = CONTENT_TYPE_JPEG.equals(contentType) ? "jpg" : "png";
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
