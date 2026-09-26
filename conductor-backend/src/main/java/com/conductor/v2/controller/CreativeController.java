package com.conductor.v2.controller;

import com.conductor.creative.Creative;
import com.conductor.creative.CreativePhoto;
import com.conductor.creative.CreativePhotoService;
import com.conductor.creative.CreativeRegistry;
import com.conductor.creative.CreativeService;
import com.conductor.entity.User;
import com.conductor.generated.v2.api.CreativesApi;
import com.conductor.generated.v2.model.ConfirmCreativePhotoRequest;
import com.conductor.generated.v2.model.CreateCreativePhotoRequest;
import com.conductor.generated.v2.model.CreateCreativeRequest;
import com.conductor.generated.v2.model.CreateCreativeVariantRequest;
import com.conductor.generated.v2.model.CreativePhotoResponse;
import com.conductor.generated.v2.model.CreativeReadinessItem;
import com.conductor.generated.v2.model.CreativeReadinessResponse;
import com.conductor.generated.v2.model.CreativeRegistryLayout;
import com.conductor.generated.v2.model.CreativeRegistryPlacement;
import com.conductor.generated.v2.model.CreativeRegistryResponse;
import com.conductor.generated.v2.model.CreativeResponse;
import com.conductor.generated.v2.model.CreativeState;
import com.conductor.generated.v2.model.CreativeTheme;
import com.conductor.generated.v2.model.PatchCreativePhotoRequest;
import com.conductor.generated.v2.model.PatchCreativeRequest;
import com.conductor.generated.v2.model.SequenceBeat;
import com.conductor.generated.v2.model.SequenceKind;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

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

    public CreativeController(CreativePhotoService photoService, CreativeService creativeService,
                              CreativeRegistry registry, ObjectMapper objectMapper) {
        this.photoService = photoService;
        this.creativeService = creativeService;
        this.registry = registry;
        this.objectMapper = objectMapper;
    }

    // ── Photos ───────────────────────────────────────────────────────────

    @Override
    public ResponseEntity<List<CreativePhotoResponse>> listCreativePhotos(String projectId, Boolean includeBlocked) {
        List<CreativePhotoResponse> body = photoService.listPhotos(projectId, Boolean.TRUE.equals(includeBlocked), currentUser())
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

    // ── Mapping ──────────────────────────────────────────────────────────

    private CreativePhotoResponse toResponse(CreativePhotoService.PhotoView view) {
        CreativePhoto photo = view.photo();
        CreativePhotoResponse response = new CreativePhotoResponse(photo.getId(), photo.getProjectId(),
                photo.getContentType(), photo.getSizeBytes(), photo.isAiGenerated(), photo.isChecked(), photo.isBlocked(),
                toStringMap(photo.getFocal()),
                CreativePhotoResponse.UploadStatusEnum.fromValue(photo.getUploadStatus()), view.warnings(),
                photo.getCreatedAt())
                .label(photo.getLabel())
                .width(photo.getWidth())
                .height(photo.getHeight())
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
                toTypeOverrides(c.getTypeOverrides()), c.getVersion(), c.getCreatedAt(), c.getUpdatedAt())
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
                .createdBy(c.getCreatedBy());
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

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
