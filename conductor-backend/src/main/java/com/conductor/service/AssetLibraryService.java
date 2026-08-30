package com.conductor.service;

import com.conductor.entity.WorkflowDefinition;
import com.conductor.entity.User;
import com.conductor.exception.BusinessException;
import com.conductor.repository.AssetRepository;
import com.conductor.repository.WorkflowDefinitionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Area asset library (COND-23 T7.1): every UPLOADED {@code kind=file} Asset produced anywhere under one
 * Area, newest first, with a short-lived signed preview URL per row.
 *
 * <p><b>Why Area, never a Workflow slug or noun.</b> An Area is a nav grouping that can hold several
 * lifecycle Workflows — Marketing holds Posts today and gains Content and SEO next (COND-19). This service
 * therefore resolves the Area to <em>whatever</em> Workflows currently declare it (see
 * {@link #resolveAreaWorkflowSlugs}) and queries across that set. Registering another Workflow in the Area is
 * a data change; its media shows up here with no change to this class. Nothing in this file names a
 * Workflow, a noun or an asset type.
 *
 * <p><b>Access.</b> Project membership is the only gate, checked once up front via
 * {@link ProjectSecurityService}; a non-member gets the same 404 the rest of the Work Item surface returns,
 * so the library never confirms a project exists to someone outside it.
 *
 * <p><b>Cost.</b> Two queries total, independent of how many Assets come back: one to resolve the Area's
 * Workflow slugs, one flat projection ({@link AssetRepository.AreaAssetRow}) that joins Asset → Work Item →
 * Project so the display id, title, status and Workflow arrive with the row rather than as a lazy load per
 * asset. Preview URLs are then minted in-process — {@code Storage#signUrl} is a local HMAC over the object
 * path, not a call to the storage backend — so a page of rows costs no extra round trips. Deliberately
 * <em>not</em> routed through {@code AssetService#resolvePreviewUrl}, which re-checks membership and reloads
 * the Work Item and Asset on every call: correct for a single asset, an N+1 here.
 */
@Service
public class AssetLibraryService {

    /** Matches {@code AssetService}'s preview lifetime: long enough to render a grid, short enough to leak badly. */
    public static final int PREVIEW_URL_EXPIRY_MINUTES = 15;

    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 50;

    /** The media families the library can be narrowed to, each a {@code contentType} prefix. */
    private static final Set<String> MEDIA_TYPES = Set.of("image", "video");

    private final AssetRepository assetRepository;
    private final WorkflowDefinitionRepository workflowDefinitionRepository;
    private final ProjectSecurityService projectSecurityService;
    private final StorageService storageService;

    public AssetLibraryService(AssetRepository assetRepository,
                               WorkflowDefinitionRepository workflowDefinitionRepository,
                               ProjectSecurityService projectSecurityService,
                               StorageService storageService) {
        this.assetRepository = assetRepository;
        this.workflowDefinitionRepository = workflowDefinitionRepository;
        this.projectSecurityService = projectSecurityService;
        this.storageService = storageService;
    }

    /**
     * The filters a caller can narrow the library by. Every field is optional; a null one is ignored.
     *
     * @param mediaType {@code image} or {@code video}, matched against the stored content type's prefix
     * @param workflow  an owning Workflow slug — intersected with the Area's slugs, never able to widen past it
     * @param status    the owning Work Item's current Workflow-defined status
     */
    public record LibraryQuery(String mediaType, String workflow, String status,
                               OffsetDateTime uploadedAfter, OffsetDateTime uploadedBefore) {

        public static LibraryQuery unfiltered() {
            return new LibraryQuery(null, null, null, null, null);
        }
    }

    /** One library row: the stored file plus the Work Item that produced it. */
    public record LibraryAsset(String assetId, String previewUrl, String contentType, Long sizeBytes,
                               OffsetDateTime uploadedAt, String workItemId, String workItemDisplayId,
                               String workItemTitle, String workItemStatus, String workflow) {
    }

    @Transactional(readOnly = true)
    public List<LibraryAsset> listAreaAssets(String projectId, String area, LibraryQuery query,
                                             Integer page, Integer size, User caller) {
        if (!projectSecurityService.isProjectMember(projectId, caller.getId())) {
            throw new EntityNotFoundException("Project not found");
        }

        Set<String> slugs = scopeToRequestedWorkflow(resolveAreaWorkflowSlugs(projectId, area), query.workflow());
        if (slugs.isEmpty()) {
            return List.of();
        }

        List<AssetRepository.AreaAssetRow> rows = assetRepository.findUploadedFileAssetsByWorkflowSlugs(
                projectId,
                slugs,
                WorkItemWorkflowService.DEFAULT_WORKFLOW,
                AssetService.KIND_FILE,
                AssetService.UPLOAD_STATUS_UPLOADED,
                contentTypePrefix(query.mediaType()),
                blankToNull(query.status()),
                query.uploadedAfter(),
                query.uploadedBefore(),
                PageRequest.of(normalizePage(page), normalizeSize(size)));

        return rows.stream().map(this::toLibraryAsset).toList();
    }

    /**
     * Every lifecycle Workflow slug in the project whose {@code area} matches, compared case-insensitively
     * because the Area travels through the UI as a lowercased route segment. The slug is the statechart's
     * {@code definition.id} — the same identity a Work Item's {@code workflow} column stores — falling back to
     * the header name only when a definition somehow carries no id. YAML automations are skipped: no Work Item
     * ever binds to one, so they own no Assets.
     */
    private Set<String> resolveAreaWorkflowSlugs(String projectId, String area) {
        String wanted = area == null ? null : area.trim();
        if (wanted == null || wanted.isEmpty()) {
            return Set.of();
        }
        Set<String> slugs = new LinkedHashSet<>();
        for (WorkflowDefinition definition : workflowDefinitionRepository.findByProjectId(projectId)) {
            if (!definition.isLifecycle() || !wanted.equalsIgnoreCase(definition.getArea())) {
                continue;
            }
            String slug = statechartSlug(definition);
            if (slug != null) {
                slugs.add(slug);
            }
        }
        return slugs;
    }

    /**
     * Applies the optional {@code workflow} filter by intersection, so the filter can only ever narrow the
     * Area's set. A slug from another Area matches nothing rather than reaching outside the requested Area.
     */
    private Set<String> scopeToRequestedWorkflow(Set<String> areaSlugs, String requestedWorkflow) {
        String requested = blankToNull(requestedWorkflow);
        if (requested == null) {
            return areaSlugs;
        }
        return areaSlugs.contains(requested) ? Set.of(requested) : Set.of();
    }

    private static String statechartSlug(WorkflowDefinition definition) {
        JsonNode id = definition.getDefinition().get("id");
        return id != null && id.isTextual() && !id.asText().isBlank() ? id.asText() : definition.getName();
    }

    /** {@code image} → {@code image/%}. An unrecognized family is a client error, not a silently empty list. */
    private static String contentTypePrefix(String mediaType) {
        String normalized = blankToNull(mediaType);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!MEDIA_TYPES.contains(normalized)) {
            throw new BusinessException("Unsupported mediaType '" + mediaType + "'; expected one of " + MEDIA_TYPES);
        }
        return normalized + "/%";
    }

    private LibraryAsset toLibraryAsset(AssetRepository.AreaAssetRow row) {
        return new LibraryAsset(
                row.getAssetId(),
                storageService.generateSignedUrl(row.getGcsPath(), PREVIEW_URL_EXPIRY_MINUTES),
                row.getContentType(),
                row.getSizeBytes(),
                row.getUploadedAt(),
                row.getWorkItemId(),
                row.getProjectKey() + "-" + row.getSequenceNumber(),
                row.getWorkItemTitle(),
                row.getWorkItemStatus(),
                row.getWorkflow());
    }

    private static int normalizePage(Integer page) {
        if (page == null) {
            return 0;
        }
        if (page < 0) {
            throw new BusinessException("page must not be negative");
        }
        return page;
    }

    private static int normalizeSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (size < 1) {
            throw new BusinessException("size must be at least 1");
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
