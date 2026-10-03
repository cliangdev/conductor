package com.conductor.creative;

import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.exception.ConflictException;
import com.conductor.exception.ForbiddenException;
import com.conductor.generated.v2.model.CopyRule;
import com.conductor.generated.v2.model.CreateBrandKitRequest;
import com.conductor.generated.v2.model.PatchBrandKitRequest;
import com.conductor.repository.ProjectRepository;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.PatternSyntaxException;

/**
 * Per-project Brand Kits (COND-24 T2). Every workspace has at least one kit, slug {@code default},
 * created lazily and race-safely the first time {@link #listKits} or {@link #resolveDefault} runs — see
 * {@code V139__brand_kit.sql}. Nothing here is Rexipe-specific: {@link #DEFAULT_TOKENS} is a neutral
 * palette, never a brand constant (CLAUDE.md, {@code docs/cli-assets.md}).
 *
 * <p>Reads are open to any project member; writes (create/patch/delete/image slots) require ADMIN or
 * CREATOR, checked the same way {@code WorkflowSecretsService} does.
 */
@Service
public class BrandKitService {

    public static final String DEFAULT_SLUG = "default";
    private static final String DEFAULT_KIT_NAME = "Default";
    private static final int IMAGE_URL_EXPIRY_MINUTES = 15;
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private static final Map<String, String> ALLOWED_IMAGE_TYPES = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp",
            "image/svg+xml", "svg");

    /** A neutral, brand-free starting palette — swapped by every real workspace. */
    private static final Map<String, String> DEFAULT_TOKENS = Map.of(
            "accent", "#3B82F6",
            "accent2", "#2563EB",
            "darkBg", "#18181B",
            "darkInk", "#FFFFFF",
            "lightBg", "#F4F4F5",
            "lightCard", "#FFFFFF",
            "ink", "#18181B",
            "ink2", "#71717A");

    /** {@link #DEFAULT_TOKENS} as a {@link JsonNode}, built exactly once (a plain, unconfigured
     * {@link ObjectMapper} is enough for a {@code Map<String, String>} — no need for the injected,
     * request-scoped-config one) and reused everywhere a kit's tokens get compared or seeded against the
     * default, instead of re-serializing the same constant map on every call. */
    private static final JsonNode DEFAULT_TOKENS_NODE = new ObjectMapper().valueToTree(DEFAULT_TOKENS);

    private static final List<String> DEFAULT_PLACEMENTS = List.of("9x16", "4x5", "1x1");

    private final BrandKitRepository brandKitRepository;
    private final CreativeRepository creativeRepository;
    private final ProjectRepository projectRepository;
    private final ProjectSecurityService projectSecurityService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;
    private final String backendBaseUrl;

    public BrandKitService(BrandKitRepository brandKitRepository,
                           CreativeRepository creativeRepository,
                           ProjectRepository projectRepository,
                           ProjectSecurityService projectSecurityService,
                           StorageService storageService,
                           ObjectMapper objectMapper,
                           @Value("${conductor.backend.url:http://localhost:8080}") String backendBaseUrl) {
        this.brandKitRepository = brandKitRepository;
        this.creativeRepository = creativeRepository;
        this.projectRepository = projectRepository;
        this.projectSecurityService = projectSecurityService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
        this.backendBaseUrl = backendBaseUrl;
    }

    @Transactional
    public List<BrandKit> listKits(String projectId, User caller) {
        requireMember(projectId, caller);
        ensureDefaultKit(projectId);
        return brandKitRepository.findAllByProjectIdOrderByCreatedAtAsc(projectId);
    }

    /** Resolves the project's default kit, lazily creating it (race-safe) if none exists yet. */
    @Transactional
    public BrandKit resolveDefault(String projectId) {
        return ensureDefaultKit(projectId);
    }

    /**
     * Race-safe by serialising on the {@code projects} row (the same lock
     * {@code WorkItemService#createWorkItem} takes for its {@code sequenceNumber}), not by catching a
     * unique-constraint violation: Postgres aborts the whole transaction on a constraint violation, so a
     * catch-and-read-back in the same {@code @Transactional} method would itself fail with "current
     * transaction is aborted". Locking first means two concurrent first callers never both attempt the
     * insert.
     */
    @Transactional
    BrandKit ensureDefaultKit(String projectId) {
        Optional<BrandKit> existing = brandKitRepository.findByProjectIdAndIsDefaultTrue(projectId);
        if (existing.isPresent()) {
            return existing.get();
        }
        projectRepository.lockForSequence(projectId);
        return brandKitRepository.findByProjectIdAndIsDefaultTrue(projectId)
                .orElseGet(() -> createDefaultKit(projectId));
    }

    private BrandKit createDefaultKit(String projectId) {
        return brandKitRepository.save(newDefaultKit(projectId));
    }

    private BrandKit newDefaultKit(String projectId) {
        BrandKit kit = new BrandKit();
        kit.setProjectId(projectId);
        kit.setSlug(DEFAULT_SLUG);
        kit.setName(DEFAULT_KIT_NAME);
        kit.setDefault(true);
        kit.setTokens(DEFAULT_TOKENS_NODE);
        kit.setCopyRules(objectMapper.createArrayNode());
        kit.setApprovedLines(objectMapper.createArrayNode());
        kit.setEnabledPlacements(objectMapper.valueToTree(DEFAULT_PLACEMENTS));
        kit.setKnowledgePagePath("marketing/brand.md");
        return kit;
    }

    /**
     * The project's default kit for a read that must persist nothing (a draft spec): the stored one, or —
     * when the workspace has never had a kit — an unsaved stand-in with the same neutral defaults
     * {@link #resolveDefault} would seed. Never writes, never takes the {@code projects} row lock.
     */
    @Transactional(readOnly = true)
    public BrandKit peekDefault(String projectId) {
        return brandKitRepository.findByProjectIdAndIsDefaultTrue(projectId)
                .orElseGet(() -> newDefaultKit(projectId));
    }

    @Transactional(readOnly = true)
    public BrandKit getKit(String projectId, String kitId, User caller) {
        requireMember(projectId, caller);
        return findKit(projectId, kitId);
    }

    @Transactional
    public BrandKit createKit(String projectId, CreateBrandKitRequest request, User caller) {
        requireEditor(projectId, caller);
        if (request.getSlug() == null || request.getSlug().isBlank()) {
            throw new BusinessException("slug is required");
        }
        if (brandKitRepository.existsByProjectIdAndSlug(projectId, request.getSlug())) {
            throw new ConflictException("A Brand Kit with slug '" + request.getSlug() + "' already exists in this project");
        }
        List<BrandKitValidationException.FieldError> errors = new ArrayList<>();
        JsonNode copyRules = validateAndConvertCopyRules(request.getCopyRules(), errors);
        if (!errors.isEmpty()) {
            throw new BrandKitValidationException(errors);
        }

        BrandKit kit = new BrandKit();
        kit.setProjectId(projectId);
        kit.setSlug(request.getSlug());
        kit.setName(request.getName());
        kit.setDefault(false);
        kit.setTokens(request.getTokens() != null ? objectMapper.valueToTree(request.getTokens()) : DEFAULT_TOKENS_NODE);
        kit.setFontFamily(request.getFontFamily());
        kit.setFontUrl(request.getFontUrl());
        kit.setCtaClaim(request.getCtaClaim());
        kit.setAccentPhraseRequired(Boolean.TRUE.equals(request.getAccentPhraseRequired()));
        kit.setCopyRules(copyRules);
        kit.setApprovedLines(request.getApprovedLines() != null ? objectMapper.valueToTree(request.getApprovedLines()) : objectMapper.createArrayNode());
        kit.setEnabledPlacements(request.getEnabledPlacements() != null && !request.getEnabledPlacements().isEmpty()
                ? objectMapper.valueToTree(request.getEnabledPlacements()) : objectMapper.valueToTree(DEFAULT_PLACEMENTS));
        kit.setKnowledgePagePath(request.getKnowledgePagePath() != null ? request.getKnowledgePagePath() : "marketing/brand.md");
        return brandKitRepository.save(kit);
    }

    @Transactional
    public BrandKit patchKit(String projectId, String kitId, PatchBrandKitRequest request, User caller) {
        requireEditor(projectId, caller);
        BrandKit kit = findKit(projectId, kitId);

        List<BrandKitValidationException.FieldError> errors = new ArrayList<>();
        JsonNode copyRules = request.getCopyRules() != null ? validateAndConvertCopyRules(request.getCopyRules(), errors) : null;
        if (request.getApprovedLines() != null && request.getAddApprovedLines() != null) {
            errors.add(new BrandKitValidationException.FieldError("addApprovedLines",
                    "cannot be sent together with approvedLines"));
        }
        if (!errors.isEmpty()) {
            throw new BrandKitValidationException(errors);
        }

        if (request.getName() != null) {
            kit.setName(request.getName());
        }
        if (request.getTokens() != null) {
            kit.setTokens(mergeTokens(kit.getTokens(), request.getTokens()));
        }
        if (request.getFontFamily() != null) {
            kit.setFontFamily(request.getFontFamily());
        }
        if (request.getFontUrl() != null) {
            kit.setFontUrl(request.getFontUrl());
        }
        if (request.getCtaClaim() != null) {
            kit.setCtaClaim(request.getCtaClaim());
        }
        if (request.getAccentPhraseRequired() != null) {
            kit.setAccentPhraseRequired(request.getAccentPhraseRequired());
        }
        if (copyRules != null) {
            kit.setCopyRules(copyRules);
        }
        if (request.getApprovedLines() != null) {
            kit.setApprovedLines(objectMapper.valueToTree(request.getApprovedLines()));
        } else if (request.getAddApprovedLines() != null && !request.getAddApprovedLines().isEmpty()) {
            kit.setApprovedLines(appendApprovedLines(kit.getApprovedLines(), request.getAddApprovedLines()));
        }
        if (request.getEnabledPlacements() != null) {
            kit.setEnabledPlacements(objectMapper.valueToTree(request.getEnabledPlacements()));
        }
        if (request.getKnowledgePagePath() != null) {
            kit.setKnowledgePagePath(request.getKnowledgePagePath());
        }
        kit = brandKitRepository.save(kit);

        if (Boolean.TRUE.equals(request.getIsDefault()) && !kit.isDefault()) {
            switchDefault(projectId, kit);
        }
        return kit;
    }

    /** Unsets the project's current default (if any), then flushes before setting the new one. */
    private void switchDefault(String projectId, BrandKit newDefault) {
        brandKitRepository.findByProjectIdAndIsDefaultTrue(projectId).ifPresent(current -> {
            if (!current.getId().equals(newDefault.getId())) {
                current.setDefault(false);
                brandKitRepository.saveAndFlush(current);
            }
        });
        newDefault.setDefault(true);
        brandKitRepository.saveAndFlush(newDefault);
    }

    @Transactional
    public void deleteKit(String projectId, String kitId, User caller) {
        requireEditor(projectId, caller);
        BrandKit kit = findKit(projectId, kitId);
        if (kit.isDefault()) {
            throw new ConflictException("Cannot delete the project's default Brand Kit — set another kit as default first");
        }
        if (creativeRepository.existsByProjectIdAndBrandKitId(projectId, kitId)) {
            throw new ConflictException("Cannot delete a Brand Kit referenced by an existing Creative");
        }
        brandKitRepository.delete(kit);
    }

    public record ImageUploadTicket(String uploadUrl, String gcsPath) {
    }

    @Transactional(readOnly = true)
    public ImageUploadTicket mintImageUpload(String projectId, String kitId, String slot, String contentType,
                                             Long sizeBytes, User caller) {
        requireEditor(projectId, caller);
        BrandKit kit = findKit(projectId, kitId);
        requireValidSlot(slot);
        String extension = ALLOWED_IMAGE_TYPES.get(normalizeContentType(contentType));
        if (extension == null) {
            throw new BusinessException("Content type '" + contentType + "' is not allowed for a Brand Kit image."
                    + " Allowed types: " + ALLOWED_IMAGE_TYPES.keySet().stream().sorted().toList());
        }
        if (sizeBytes == null || sizeBytes <= 0 || sizeBytes > MAX_IMAGE_BYTES) {
            throw new BusinessException("Brand Kit image size must be a positive number of bytes up to " + MAX_IMAGE_BYTES);
        }
        String gcsPath = imagePrefix(projectId, kit.getId(), slot) + "-" + UUID.randomUUID() + "." + extension;
        String signedUploadUrl = storageService.generateSignedUploadUrl(gcsPath, normalizeContentType(contentType), 60);
        String uploadUrl = signedUploadUrl != null ? signedUploadUrl : passthroughUploadUrl(projectId, kitId, slot, gcsPath);
        return new ImageUploadTicket(uploadUrl, gcsPath);
    }

    /**
     * Local-profile fallback, mirroring {@code AssetService#passthroughContentUrl}:
     * {@code StorageService} can't mint a signed upload URL on the {@code local} profile (see
     * {@link com.conductor.service.LocalStorageService}), so the client is handed this URL instead of a
     * signed bucket URL. Served by {@code com.conductor.internal.CreativeContentController}, which is
     * itself {@code @Profile("local")} and therefore doesn't exist in production, where
     * {@code GcpStorageService#generateSignedUploadUrl} never returns null and nothing calls this.
     *
     * <p>Unlike a file Asset, mint doesn't persist a pending row for a brand image slot — there is
     * nothing to look up server-side at content-upload time — so {@code gcsPath} (which the client
     * already has from this same mint response) rides along as a query parameter, exactly as the client
     * is separately required to echo it back to {@link #confirmImage}.
     */
    private String passthroughUploadUrl(String projectId, String kitId, String slot, String gcsPath) {
        String encodedPath = java.net.URLEncoder.encode(gcsPath, StandardCharsets.UTF_8);
        return backendBaseUrl + "/internal/v1/projects/" + projectId + "/brand-kits/" + kitId
                + "/images/" + slot + "/content?gcsPath=" + encodedPath;
    }

    /** Scope check for {@code CreativeContentController}: does this kit belong to this project? */
    @Transactional(readOnly = true)
    public boolean belongsToProject(String kitId, String projectId) {
        return brandKitRepository.findByIdAndProjectId(kitId, projectId).isPresent();
    }

    /**
     * Local-profile passthrough write: stores the raw bytes at {@code gcsPath}, which must match the
     * path minted for this kit/slot (same prefix check as {@link #confirmImage}). No caller/membership
     * check — the caller-facing scope check is {@link #belongsToProject}, done by the controller before
     * this is reached, the same split {@code AssetService#uploadContentPassthrough} uses.
     */
    public void uploadImageContentPassthrough(String projectId, String kitId, String slot, String gcsPath, byte[] content) {
        requireValidSlot(slot);
        if (gcsPath == null || !gcsPath.startsWith(imagePrefix(projectId, kitId, slot) + "-")) {
            throw new BusinessException("gcsPath does not match a minted upload for this Brand Kit image slot");
        }
        storageService.upload(gcsPath, content, contentTypeForPath(gcsPath));
    }

    private static String contentTypeForPath(String gcsPath) {
        String extension = gcsPath.substring(gcsPath.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (extension) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            default -> "application/octet-stream";
        };
    }

    @Transactional
    public BrandKit confirmImage(String projectId, String kitId, String slot, String gcsPath, User caller) {
        requireEditor(projectId, caller);
        BrandKit kit = findKit(projectId, kitId);
        requireValidSlot(slot);
        if (gcsPath == null || !gcsPath.startsWith(imagePrefix(projectId, kit.getId(), slot) + "-")) {
            throw new BusinessException("Confirmed path does not match a minted upload for this Brand Kit image slot");
        }
        kit.setGcsPathForSlot(slot, gcsPath);
        return brandKitRepository.save(kit);
    }

    @Transactional
    public BrandKit deleteImage(String projectId, String kitId, String slot, User caller) {
        requireEditor(projectId, caller);
        BrandKit kit = findKit(projectId, kitId);
        requireValidSlot(slot);
        kit.setGcsPathForSlot(slot, null);
        return brandKitRepository.save(kit);
    }

    /** Short-lived signed GET for a set image slot, or null when the slot is unset. */
    public String resolveImageUrl(String gcsPath) {
        return gcsPath != null ? storageService.generateSignedUrl(gcsPath, IMAGE_URL_EXPIRY_MINUTES) : null;
    }

    /**
     * True when {@code kit} carries any customisation beyond the neutral starting point (see
     * {@code BrandKitResponse.configured} in {@code openapi-v2.yaml}). Computed fresh on every read from
     * the kit's current data — never from {@code createdAt}/{@code updatedAt} — so an internal write that
     * doesn't touch any of these fields (e.g. switching which kit is default) never changes it.
     */
    public boolean isConfigured(BrandKit kit) {
        return tokensDifferFromDefault(kit.getTokens())
                || hasText(kit.getFontFamily())
                || kit.getMarkGcsPath() != null
                || kit.getWordmarkDarkGcsPath() != null
                || kit.getWordmarkLightGcsPath() != null
                || kit.getBadgeGcsPath() != null
                || hasText(kit.getCtaClaim())
                || (kit.getCopyRules() != null && !kit.getCopyRules().isEmpty())
                || (kit.getApprovedLines() != null && !kit.getApprovedLines().isEmpty())
                || kit.isAccentPhraseRequired()
                || !DEFAULT_KIT_NAME.equals(kit.getName());
    }

    /**
     * True unless {@code tokens} has exactly {@link #DEFAULT_TOKENS}'s keys, each with a value that's
     * the same hex colour once trimmed and lower-cased — a plain {@link JsonNode#equals} would treat
     * {@code #3B82F6} and {@code #3b82f6} as a customisation, which they aren't.
     */
    private boolean tokensDifferFromDefault(JsonNode tokens) {
        if (tokens == null || !tokens.isObject() || tokens.size() != DEFAULT_TOKENS.size()) {
            return true;
        }
        for (Map.Entry<String, String> entry : DEFAULT_TOKENS.entrySet()) {
            JsonNode value = tokens.get(entry.getKey());
            if (value == null || !value.isTextual() || !normalizeHex(value.asText()).equals(normalizeHex(entry.getValue()))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeHex(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String imagePrefix(String projectId, String kitId, String slot) {
        return "projects/" + projectId + "/brand/" + kitId + "/" + slot;
    }

    private static final List<String> IMAGE_SLOTS = List.of("mark", "wordmark_dark", "wordmark_light", "badge");

    private void requireValidSlot(String slot) {
        if (slot == null || !IMAGE_SLOTS.contains(slot)) {
            throw new BusinessException("Unknown Brand Kit image slot: " + slot);
        }
    }

    private static String normalizeContentType(String contentType) {
        return contentType == null ? null : contentType.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Merges a PATCH's {@code tokens} onto the kit's current tokens: keys given override, keys omitted
     * keep their current value, and a key sent with a JSON {@code null} value is removed. The web form
     * always sends the full map with no nulls, so a full replace and a merge are indistinguishable from
     * its perspective. {@code current} not being a JSON object (missing, a JSON {@code null}, or —
     * defensively — anything else malformed) starts the merge from an empty object rather than throwing.
     */
    private JsonNode mergeTokens(JsonNode current, Map<String, String> updates) {
        ObjectNode merged = objectMapper.createObjectNode();
        if (current != null && current.isObject()) {
            merged.setAll((ObjectNode) current);
        }
        updates.forEach((key, value) -> {
            if (value == null) {
                merged.remove(key);
            } else {
                merged.put(key, value);
            }
        });
        return merged;
    }

    /** Appends {@code toAdd} to {@code current}, deduping against the combined list while keeping order. */
    private JsonNode appendApprovedLines(JsonNode current, List<String> toAdd) {
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        if (current != null) {
            current.forEach(n -> lines.add(n.asText()));
        }
        lines.addAll(toAdd);
        ArrayNode array = objectMapper.createArrayNode();
        lines.forEach(array::add);
        return array;
    }

    /** Validates every copy rule's {@code pattern}/{@code exceptPattern} compiles, converting to JSON as it goes. */
    private JsonNode validateAndConvertCopyRules(List<CopyRule> rules, List<BrandKitValidationException.FieldError> errors) {
        ArrayNode array = objectMapper.createArrayNode();
        if (rules == null) {
            return array;
        }
        for (int i = 0; i < rules.size(); i++) {
            CopyRule rule = rules.get(i);
            try {
                CreativeValidator.compilePattern(rule.getPattern(), rule.getFlags());
            } catch (PatternSyntaxException e) {
                errors.add(new BrandKitValidationException.FieldError("copyRules[" + i + "].pattern",
                        "Rule '" + rule.getId() + "': pattern does not compile as a regex: " + e.getMessage()));
            }
            if (rule.getExceptPattern() != null) {
                try {
                    CreativeValidator.compilePattern(rule.getExceptPattern(), null);
                } catch (PatternSyntaxException e) {
                    errors.add(new BrandKitValidationException.FieldError("copyRules[" + i + "].exceptPattern",
                            "Rule '" + rule.getId() + "': exceptPattern does not compile as a regex: " + e.getMessage()));
                }
            }
            array.add(objectMapper.valueToTree(rule));
        }
        return array;
    }

    private BrandKit findKit(String projectId, String kitId) {
        return brandKitRepository.findByIdAndProjectId(kitId, projectId)
                .orElseThrow(() -> new EntityNotFoundException("Brand Kit not found"));
    }

    private void requireMember(String projectId, User caller) {
        if (!projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Project not found");
        }
    }

    private void requireEditor(String projectId, User caller) {
        requireMember(projectId, caller);
        if (!projectSecurityService.isAdminOrCreator(projectId, caller.getId())) {
            throw new ForbiddenException("Only ADMIN or CREATOR can manage Brand Kits");
        }
    }
}
