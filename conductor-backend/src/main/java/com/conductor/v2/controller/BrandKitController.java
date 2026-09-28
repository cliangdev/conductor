package com.conductor.v2.controller;

import com.conductor.creative.BrandKit;
import com.conductor.creative.BrandKitService;
import com.conductor.entity.User;
import com.conductor.generated.v2.api.BrandKitsApi;
import com.conductor.generated.v2.model.BrandImageSlot;
import com.conductor.generated.v2.model.BrandKitResponse;
import com.conductor.generated.v2.model.ConfirmUploadRequest;
import com.conductor.generated.v2.model.CopyRule;
import com.conductor.generated.v2.model.CopyRuleField;
import com.conductor.generated.v2.model.CreateBrandKitRequest;
import com.conductor.generated.v2.model.MintUploadRequest;
import com.conductor.generated.v2.model.PatchBrandKitRequest;
import com.conductor.generated.v2.model.UploadTicketResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-project Brand Kits (COND-24 T2). Thin by design: {@link BrandKitService} owns the membership/role
 * gates, the lazy default-kit creation, the copy-rule regex validation and the signed image URLs; this
 * class only maps between it and the generated v2 DTOs. The {@code /api/v2} prefix is applied
 * structurally by {@code ApiPathConfig}.
 */
@RestController
public class BrandKitController implements BrandKitsApi {

    private final BrandKitService brandKitService;
    private final ObjectMapper objectMapper;

    public BrandKitController(BrandKitService brandKitService, ObjectMapper objectMapper) {
        this.brandKitService = brandKitService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResponseEntity<List<BrandKitResponse>> listBrandKits(String projectId) {
        List<BrandKitResponse> body = brandKitService.listKits(projectId, currentUser()).stream()
                .map(this::toResponse).toList();
        return ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<BrandKitResponse> createBrandKit(String projectId, CreateBrandKitRequest request) {
        BrandKit kit = brandKitService.createKit(projectId, request, currentUser());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(kit));
    }

    @Override
    public ResponseEntity<BrandKitResponse> getBrandKit(String projectId, String kitId) {
        return ResponseEntity.ok(toResponse(brandKitService.getKit(projectId, kitId, currentUser())));
    }

    @Override
    public ResponseEntity<BrandKitResponse> patchBrandKit(String projectId, String kitId, PatchBrandKitRequest request) {
        return ResponseEntity.ok(toResponse(brandKitService.patchKit(projectId, kitId, request, currentUser())));
    }

    @Override
    public ResponseEntity<Void> deleteBrandKit(String projectId, String kitId) {
        brandKitService.deleteKit(projectId, kitId, currentUser());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<UploadTicketResponse> mintBrandKitImageUpload(String projectId, String kitId, BrandImageSlot slot,
                                                                        MintUploadRequest request) {
        BrandKitService.ImageUploadTicket ticket = brandKitService.mintImageUpload(projectId, kitId, slot.getValue(),
                request.getContentType(), request.getSizeBytes(), currentUser());
        return ResponseEntity.ok(new UploadTicketResponse(ticket.uploadUrl(), ticket.gcsPath()));
    }

    @Override
    public ResponseEntity<BrandKitResponse> confirmBrandKitImage(String projectId, String kitId, BrandImageSlot slot,
                                                                 ConfirmUploadRequest request) {
        BrandKit kit = brandKitService.confirmImage(projectId, kitId, slot.getValue(), request.getGcsPath(), currentUser());
        return ResponseEntity.ok(toResponse(kit));
    }

    @Override
    public ResponseEntity<BrandKitResponse> deleteBrandKitImage(String projectId, String kitId, BrandImageSlot slot) {
        BrandKit kit = brandKitService.deleteImage(projectId, kitId, slot.getValue(), currentUser());
        return ResponseEntity.ok(toResponse(kit));
    }

    private BrandKitResponse toResponse(BrandKit kit) {
        List<CopyRule> copyRules = new ArrayList<>();
        if (kit.getCopyRules() != null) {
            for (JsonNode ruleNode : kit.getCopyRules()) {
                CopyRule rule = new CopyRule(
                        textOf(ruleNode, "id"), textOf(ruleNode, "pattern"), textOf(ruleNode, "message"),
                        fieldsOf(ruleNode));
                rule.setFlags(textOf(ruleNode, "flags"));
                rule.setExceptPattern(textOf(ruleNode, "exceptPattern"));
                copyRules.add(rule);
            }
        }
        return new BrandKitResponse(kit.getId(), kit.getProjectId(), kit.getSlug(), kit.getName(), kit.isDefault(),
                toStringMap(kit.getTokens()), kit.isAccentPhraseRequired(), copyRules, toStringList(kit.getApprovedLines()),
                toStringList(kit.getEnabledPlacements()), kit.getKnowledgePagePath(),
                kit.getCreatedAt(), kit.getUpdatedAt())
                .fontFamily(kit.getFontFamily())
                .fontUrl(kit.getFontUrl())
                .markUrl(brandKitService.resolveImageUrl(kit.getMarkGcsPath()))
                .wordmarkDarkUrl(brandKitService.resolveImageUrl(kit.getWordmarkDarkGcsPath()))
                .wordmarkLightUrl(brandKitService.resolveImageUrl(kit.getWordmarkLightGcsPath()))
                .badgeUrl(brandKitService.resolveImageUrl(kit.getBadgeGcsPath()))
                .ctaClaim(kit.getCtaClaim());
    }

    private List<CopyRuleField> fieldsOf(JsonNode ruleNode) {
        List<CopyRuleField> fields = new ArrayList<>();
        JsonNode fieldsNode = ruleNode.get("fields");
        if (fieldsNode != null && fieldsNode.isArray()) {
            fieldsNode.forEach(f -> fields.add(CopyRuleField.fromValue(f.asText())));
        }
        return fields;
    }

    private static String textOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText() : null;
    }

    private Map<String, String> toStringMap(JsonNode node) {
        Map<String, String> map = new LinkedHashMap<>();
        if (node != null) {
            node.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
        }
        return map;
    }

    private List<String> toStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null) {
            node.forEach(n -> list.add(n.asText()));
        }
        return list;
    }

    private User currentUser() {
        return (User) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
