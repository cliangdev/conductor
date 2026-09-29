package com.conductor.v2.controller;

import com.conductor.creative.Creative;
import com.conductor.creative.CreativeAttachService;
import com.conductor.creative.CreativeExperiment;
import com.conductor.creative.CreativeExperimentService;
import com.conductor.creative.CreativePerformanceService;
import com.conductor.creative.CreativePhoto;
import com.conductor.creative.CreativePhotoService;
import com.conductor.creative.CreativeRegistry;
import com.conductor.creative.CreativeRenderService;
import com.conductor.creative.CreativeService;
import com.conductor.entity.User;
import com.conductor.generated.v2.api.CreativesApi;
import com.conductor.generated.v2.model.AttachCreativeRequest;
import com.conductor.generated.v2.model.AttachCreativeResponse;
import com.conductor.generated.v2.model.AttachCreativeTargetSkip;
import com.conductor.generated.v2.model.AttachCreativeTargetUpdate;
import com.conductor.generated.v2.model.AttachedCreativeAsset;
import com.conductor.generated.v2.model.CompleteCreativeRenderRequest;
import com.conductor.generated.v2.model.ConfirmCreativePhotoRequest;
import com.conductor.generated.v2.model.CreateCreativeExperimentRequest;
import com.conductor.generated.v2.model.CreateCreativePhotoRequest;
import com.conductor.generated.v2.model.CreateCreativeRenderRequest;
import com.conductor.generated.v2.model.CreateCreativeRequest;
import com.conductor.generated.v2.model.CreateCreativeVariantRequest;
import com.conductor.generated.v2.model.CreativeExperimentMetric;
import com.conductor.generated.v2.model.CreativeExperimentResponse;
import com.conductor.generated.v2.model.CreativeExperimentState;
import com.conductor.generated.v2.model.CreativePerformanceEntry;
import com.conductor.generated.v2.model.CreativePerformancePlatform;
import com.conductor.generated.v2.model.CreativePerformanceResponse;
import com.conductor.generated.v2.model.CreativePhotoResponse;
import com.conductor.generated.v2.model.CreativeAudio;
import com.conductor.generated.v2.model.CreativeLayoutOverrides;
import com.conductor.generated.v2.model.CreativeLockup;
import com.conductor.generated.v2.model.CreativeMotion;
import com.conductor.generated.v2.model.ConfirmCreativePhotoPosterRequest;
import com.conductor.generated.v2.model.CreativeKind;
import com.conductor.generated.v2.model.CreativeReadinessItem;
import com.conductor.generated.v2.model.CreativeReadinessResponse;
import com.conductor.generated.v2.model.CreativeRegistryLayout;
import com.conductor.generated.v2.model.CreativeRegistryPlacement;
import com.conductor.generated.v2.model.CreativeRegistryResponse;
import com.conductor.generated.v2.model.CreativeRenderResponse;
import com.conductor.generated.v2.model.CreativeResponse;
import com.conductor.generated.v2.model.CreativeState;
import com.conductor.generated.v2.model.CreativeTheme;
import com.conductor.generated.v2.model.FailCreativeRenderRequest;
import com.conductor.generated.v2.model.MediaKind;
import com.conductor.generated.v2.model.MintCreativePhotoPosterResponse;
import com.conductor.generated.v2.model.PatchCreativePhotoRequest;
import com.conductor.generated.v2.model.PatchCreativeRequest;
import com.conductor.generated.v2.model.SequenceBeat;
import com.conductor.generated.v2.model.SequenceKind;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Creative library — photos with provenance, and the creatives themselves (COND-24 T2). Thin by
 * design: {@link CreativePhotoService}/{@link CreativeService} own the membership/role gates, reference
 * resolution and {@code CreativeValidator} enforcement; this class maps to the generated v2 DTOs. The
 * {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig}.
 */
@RestController
public class CreativeController implements CreativesApi {

    private final CreativePhotoService photoService;
    private final CreativeService creativeService;
    private final CreativeRegistry registry;
    private final ObjectMapper objectMapper;
    private final CreativeRenderService renderService;
    private final CreativeAttachService attachService;
    private final CreativePerformanceService performanceService;
    private final CreativeExperimentService experimentService;

    public CreativeController(CreativePhotoService photoService, CreativeService creativeService,
                              CreativeRegistry registry, ObjectMapper objectMapper,
                              CreativeRenderService renderService, CreativeAttachService attachService,
                              CreativePerformanceService performanceService, CreativeExperimentService experimentService) {
        this.photoService = photoService;
        this.creativeService = creativeService;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.renderService = renderService;
        this.attachService = attachService;
        this.performanceService = performanceService;
        this.experimentService = experimentService;
    }

    // ── Photos ───────────────────────────────────────────────────────────

    @Override
    public ResponseEntity<List<CreativePhotoResponse>> listCreativePhotos(String projectId, Boolean includeBlocked,
                                                                          List<MediaKind> mediaKind) {
        List<CreativePhotoResponse> body = photoService.listPhotos(projectId, Boolean.TRUE.equals(includeBlocked),
                        CreativePhotoService.mediaKindValues(mediaKind), currentUser())
                .stream().map(this::toResponse).toList();
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<CreativePhotoResponse> createCreativePhoto(String projectId, CreateCreativePhotoRequest request) {
        CreativePhotoService.PhotoView view = photoService.createPhoto(projectId, request, currentUser());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(view));
    }

    @Override
    public ResponseEntity<CreativePhotoResponse> getCreativePhoto(String projectId, String photoId) {
        return ResponseEntity.ok(toResponse(photoService.getPhoto(projectId, photoId, currentUser())));
    }

    @Override
    public ResponseEntity<CreativePhotoResponse> patchCreativePhoto(String projectId, String photoId,
                                                                    PatchCreativePhotoRequest request) {
        return ResponseEntity.ok(toResponse(photoService.patchPhoto(projectId, photoId, request, currentUser())));
    }

    @Override
    public ResponseEntity<CreativePhotoResponse> confirmCreativePhoto(String projectId, String photoId,
                                                                      ConfirmCreativePhotoRequest request) {
        Long sizeBytes = request != null ? request.getSizeBytes() : null;
        return ResponseEntity.ok(toResponse(photoService.confirmPhoto(projectId, photoId, sizeBytes, currentUser())));
    }

    @Override
    public ResponseEntity<Void> deleteCreativePhoto(String projectId, String photoId) {
        photoService.deletePhoto(projectId, photoId, currentUser());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<MintCreativePhotoPosterResponse> mintCreativePhotoPoster(String projectId, String photoId) {
        CreativePhotoService.PosterUploadTicket ticket = photoService.mintPoster(projectId, photoId, currentUser());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new MintCreativePhotoPosterResponse(ticket.uploadUrl(), ticket.gcsPath()));
    }

    @Override
    public ResponseEntity<CreativePhotoResponse> confirmCreativePhotoPoster(String projectId, String photoId,
                                                                            ConfirmCreativePhotoPosterRequest request) {
        return ResponseEntity.ok(toResponse(photoService.confirmPoster(projectId, photoId,
                request.getGcsPath(), currentUser())));
    }

    // ── Registry ─────────────────────────────────────────────────────────

    @Override
    public ResponseEntity<CreativeRegistryResponse> getCreativeRegistry(String projectId) {
        Map<String, CreativeRegistryLayout> layouts = new LinkedHashMap<>();
        registry.layouts().forEach((key, info) -> layouts.put(key, new CreativeRegistryLayout(info.themes())));
        List<CreativeRegistryPlacement> placements = registry.placements().values().stream()
                .map(p -> new CreativeRegistryPlacement(p.key(), p.label(), p.width(), p.height(), p.isDefault())
                        .platform(p.platform()))
                .toList();
        return ResponseEntity.ok(new CreativeRegistryResponse(layouts, placements));
    }

    // ── Creatives ────────────────────────────────────────────────────────

    @Override
    public ResponseEntity<List<CreativeResponse>> listCreatives(String projectId, CreativeState state, String brandKitId) {
        List<CreativeResponse> body = creativeService.listCreatives(projectId, state != null ? state.getValue() : null,
                        brandKitId, currentUser())
                .stream().map(this::toResponse).toList();
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<CreativeResponse> createCreative(String projectId, CreateCreativeRequest request) {
        CreativeService.CreativeView view = creativeService.createCreative(projectId, request, currentUser());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(view));
    }

    @Override
    public ResponseEntity<CreativeResponse> getCreative(String projectId, String creativeId) {
        return ResponseEntity.ok(toResponse(creativeService.getCreative(projectId, creativeId, currentUser())));
    }

    @Override
    public ResponseEntity<CreativeResponse> patchCreative(String projectId, String creativeId, PatchCreativeRequest request) {
        return ResponseEntity.ok(toResponse(creativeService.patchCreative(projectId, creativeId, request, currentUser())));
    }

    @Override
    public ResponseEntity<Void> deleteCreative(String projectId, String creativeId) {
        creativeService.deleteCreative(projectId, creativeId, currentUser());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<CreativeResponse> createCreativeVariant(String projectId, String creativeId,
                                                                   CreateCreativeVariantRequest request) {
        CreativeService.CreativeView view = creativeService.createVariant(projectId, creativeId, request, currentUser());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(view));
    }

    @Override
    public ResponseEntity<CreativeReadinessResponse> getCreativeReadiness(String projectId, String creativeId) {
        CreativeService.Readiness readiness = creativeService.readiness(projectId, creativeId, currentUser());
        List<CreativeReadinessItem> items = readiness.items().stream()
                .map(i -> new CreativeReadinessItem(i.key(), i.ok(), i.blocking(), i.message()))
                .toList();
        return ResponseEntity.ok(new CreativeReadinessResponse(readiness.ready(), items));
    }

    // ── Renders (COND-24 T3) ─────────────────────────────────────────────

    @Override
    public ResponseEntity<CreativeRenderResponse> createCreativeRender(String projectId, String creativeId,
                                                                       CreateCreativeRenderRequest request) {
        CreativeRenderService.CreateRenderResult result =
                renderService.requestRender(projectId, creativeId, request, currentUser());
        CreativeRenderResponse response = renderService.toResponse(
                new CreativeRenderService.RenderView(result.render(), result.frames()), result.spec());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Override
    public ResponseEntity<List<CreativeRenderResponse>> listCreativeRenders(String projectId, String creativeId) {
        List<CreativeRenderResponse> body = renderService.listRenders(projectId, creativeId, currentUser()).stream()
                .map(renderService::toResponse)
                .toList();
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<CreativeRenderResponse> getCreativeRender(String projectId, String creativeId, String renderId) {
        return ResponseEntity.ok(renderService.toResponse(
                renderService.getRender(projectId, creativeId, renderId, currentUser())));
    }

    @Override
    public ResponseEntity<Void> putCreativeRenderFrame(String projectId, String creativeId, String renderId,
                                                       String placementKey, Integer width, Integer height,
                                                       Resource body, Integer index, BigDecimal durationSeconds,
                                                       Boolean hasAudio) {
        renderService.putFrame(projectId, creativeId, renderId, placementKey, index, width, height,
                readAllBytes(body), currentRequestContentType(), durationSeconds, hasAudio, currentUser());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> putCreativeRenderFramePoster(String projectId, String creativeId, String renderId,
                                                             String placementKey, Resource body, Integer index) {
        renderService.putFramePoster(projectId, creativeId, renderId, placementKey, index,
                readAllBytes(body), currentRequestContentType(), currentUser());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<CreativeRenderResponse> completeCreativeRender(String projectId, String creativeId,
                                                                         String renderId,
                                                                         CompleteCreativeRenderRequest request) {
        return ResponseEntity.ok(renderService.toResponse(
                renderService.completeRender(projectId, creativeId, renderId, request, currentUser())));
    }

    @Override
    public ResponseEntity<CreativeRenderResponse> failCreativeRender(String projectId, String creativeId,
                                                                     String renderId, FailCreativeRenderRequest request) {
        return ResponseEntity.ok(renderService.toResponse(
                renderService.failRender(projectId, creativeId, renderId, request, currentUser())));
    }

    // ── Attach (COND-24 T3) ──────────────────────────────────────────────

    @Override
    public ResponseEntity<AttachCreativeResponse> attachCreative(String projectId, String creativeId,
                                                                  AttachCreativeRequest request) {
        CreativeAttachService.AttachResult result = attachService.attach(projectId, creativeId,
                request.getRenderId(), request.getWorkItemId(), currentUser());
        List<AttachedCreativeAsset> assets = result.assets().stream()
                .map(a -> new AttachedCreativeAsset(a.assetId(), a.frameId(), a.placementKey())
                        .sequenceIndex(a.sequenceIndex()))
                .toList();
        List<AttachCreativeTargetUpdate> updated = result.targetsUpdated().stream()
                .map(u -> new AttachCreativeTargetUpdate(u.targetId(), u.platform(), u.assetIds()))
                .toList();
        List<AttachCreativeTargetSkip> skipped = result.targetsSkipped().stream()
                .map(s -> new AttachCreativeTargetSkip(s.targetId(), s.platform(), s.reason()))
                .toList();
        return ResponseEntity.ok(new AttachCreativeResponse(assets, updated, skipped));
    }

    // ── Performance + experiments (COND-24 T5) ──────────────────────────

    @Override
    public ResponseEntity<CreativePerformanceResponse> getCreativePerformance(String projectId, String creativeId) {
        CreativePerformanceService.FamilyPerformance perf =
                performanceService.familyPerformance(projectId, creativeId, currentUser());
        return ResponseEntity.ok(new CreativePerformanceResponse(perf.creativeId(),
                perf.family().stream().map(this::toResponse).toList()));
    }

    @Override
    public ResponseEntity<List<CreativeExperimentResponse>> listCreativeExperiments(String projectId, String creativeId,
                                                                                     CreativeExperimentState state) {
        List<CreativeExperimentResponse> body = experimentService
                .list(projectId, creativeId, state != null ? state.getValue() : null, currentUser())
                .stream().map(this::toResponse).toList();
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<CreativeExperimentResponse> createCreativeExperiment(String projectId,
                                                                                CreateCreativeExperimentRequest request) {
        CreativeExperiment experiment = experimentService.create(projectId, request.getCreativeId(),
                request.getMetric() != null ? request.getMetric().getValue() : null, request.getWindowHours(), currentUser());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(experiment));
    }

    @Override
    public ResponseEntity<CreativeExperimentResponse> getCreativeExperiment(String projectId, String experimentId) {
        return ResponseEntity.ok(toResponse(experimentService.get(projectId, experimentId, currentUser())));
    }

    @Override
    public ResponseEntity<CreativeExperimentResponse> decideCreativeExperiment(String projectId, String experimentId) {
        return ResponseEntity.ok(toResponse(experimentService.decide(projectId, experimentId, currentUser())));
    }

    @Override
    public ResponseEntity<CreativeExperimentResponse> confirmCreativeExperimentWinner(String projectId, String experimentId) {
        return ResponseEntity.ok(toResponse(experimentService.confirmWinner(projectId, experimentId, currentUser())));
    }

    private CreativePerformanceEntry toResponse(CreativePerformanceService.VariantPerformance p) {
        return new CreativePerformanceEntry(p.creativeId(), p.label(), p.posts(), p.views(),
                p.byPlatform().stream()
                        .map(b -> new CreativePerformancePlatform(b.platform(), b.posts(), b.views()).engagementRate(b.engagementRate()))
                        .toList())
                .headline(p.headline())
                .engagementRate(p.engagementRate())
                .avgViewPct(p.avgViewPct())
                .views72h(p.views72h());
    }

    private CreativeExperimentResponse toResponse(CreativeExperiment e) {
        return new CreativeExperimentResponse(e.getId(), e.getProjectId(), e.getParentCreativeId(),
                CreativeExperimentMetric.fromValue(e.getMetric()), e.getWindowHours(),
                CreativeExperimentState.fromValue(e.getState()), e.getCreatedAt())
                .winnerCreativeId(e.getWinnerCreativeId())
                .decidedAt(e.getDecidedAt())
                .summary(toSummaryObject(e.getSummary()))
                .winnerLineConfirmedAt(e.getWinnerLineConfirmedAt())
                .winnerLineConfirmedBy(e.getWinnerLineConfirmedBy())
                .createdBy(e.getCreatedBy());
    }

    private Map<String, Object> toSummaryObject(JsonNode summary) {
        if (summary == null) {
            return null;
        }
        return objectMapper.convertValue(summary, new TypeReference<Map<String, Object>>() { });
    }

    private byte[] readAllBytes(Resource resource) {
        try (java.io.InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to read render frame upload body", e);
        }
    }

    /**
     * The raw {@code Content-Type} header of the current request. {@link CreativesApi}'s generated
     * interface has no parameter for it (openapi-generator does not thread a `consumes` media type
     * through to the method signature even with two content types declared) — this is the standard
     * Spring MVC escape hatch for reaching the request from inside a call already bound by that fixed
     * signature.
     */
    private String currentRequestContentType() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
        return attrs.getRequest().getContentType();
    }

    // ── Mapping ──────────────────────────────────────────────────────────

    private CreativePhotoResponse toResponse(CreativePhotoService.PhotoView view) {
        CreativePhoto photo = view.photo();
        CreativePhotoResponse response = new CreativePhotoResponse(photo.getId(), photo.getProjectId(),
                photo.getContentType(), photo.getSizeBytes(), MediaKind.fromValue(photo.getMediaKind()),
                photo.isAiGenerated(), photo.isChecked(), photo.isBlocked(),
                toStringMap(photo.getFocal()),
                CreativePhotoResponse.UploadStatusEnum.fromValue(photo.getUploadStatus()),
                view.warnings(), photo.getCreatedAt())
                .label(photo.getLabel())
                .width(photo.getWidth())
                .height(photo.getHeight())
                .durationSeconds(photo.getDurationSeconds())
                .hasAudio(photo.getHasAudio())
                .posterUrl(view.posterUrl())
                .source(photo.getSource())
                .licence(photo.getLicence())
                .blockedReason(photo.getBlockedReason())
                .url(view.url())
                .uploadUrl(view.uploadUrl())
                .createdBy(photo.getCreatedBy());
        return response;
    }

    private CreativeResponse toResponse(CreativeService.CreativeView view) {
        Creative c = view.creative();
        CreativeResponse response = new CreativeResponse(c.getId(), c.getProjectId(), c.getBrandKitId(), c.getNumber(),
                c.getVariantLetter(), c.displayId(), CreativeState.fromValue(c.getState()), c.getLayout(),
                CreativeTheme.fromValue(c.getTheme()), toStringList(c.getPlacements()), toSequenceBeats(c.getSequence()),
                toTypeOverrides(c.getTypeOverrides()), CreativeLockup.fromValue(c.getLockup()),
                CreativeKind.fromValue(c.getKind()), c.getVersion(),
                c.getCreatedAt(), c.getUpdatedAt())
                .parentCreativeId(c.getParentCreativeId())
                .name(c.getName())
                .photoId(c.getPhotoId())
                .photoUrl(view.photoUrl())
                .focalOverride(c.getFocalOverride() != null ? toStringMap(c.getFocalOverride()) : null)
                .headline(c.getHeadline())
                .body(c.getBody())
                .caption(c.getCaption())
                .altText(c.getAltText())
                .sequenceKind(c.getSequenceKind() != null ? SequenceKind.fromValue(c.getSequenceKind()) : null)
                .carouselRatio(c.getCarouselRatio())
                .layoutOverrides(toLayoutOverrides(c.getLayoutOverrides()))
                .clipMedia(c.getClipMedia() != null ? toStringMap(c.getClipMedia()) : null)
                .motion(toMotion(c.getMotion()))
                .audio(toAudio(c.getAudio()))
                .createdBy(c.getCreatedBy())
                .activeExperimentId(view.activeExperimentId());
        if (view.latestRenderSummary() != null) {
            response.latestRenderId(view.latestRenderSummary().render().getId())
                    .latestRenderThumbnailUrl(renderService.thumbnailUrl(view.latestRenderSummary()));
        }
        if (view.latestRenderFull() != null) {
            response.latestRender(renderService.toResponse(view.latestRenderFull()));
        }
        return response;
    }

    private Map<String, String> toStringMap(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        if (node != null) {
            node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
        }
        return map;
    }

    private List<String> toStringList(JsonNode node) {
        return node != null ? objectMapper.convertValue(node, new TypeReference<List<String>>() {
        }) : List.of();
    }

    private List<SequenceBeat> toSequenceBeats(JsonNode node) {
        return node != null ? objectMapper.convertValue(node, new TypeReference<List<SequenceBeat>>() {
        }) : List.of();
    }

    private Map<String, List<BigDecimal>> toTypeOverrides(JsonNode node) {
        return node != null ? objectMapper.convertValue(node, new TypeReference<Map<String, List<BigDecimal>>>() {
        }) : Map.of();
    }

    private CreativeLayoutOverrides toLayoutOverrides(JsonNode node) {
        return node != null ? objectMapper.convertValue(node, CreativeLayoutOverrides.class) : null;
    }

    private CreativeMotion toMotion(JsonNode node) {
        return node != null ? objectMapper.convertValue(node, CreativeMotion.class) : null;
    }

    private CreativeAudio toAudio(JsonNode node) {
        return node != null ? objectMapper.convertValue(node, CreativeAudio.class) : null;
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
