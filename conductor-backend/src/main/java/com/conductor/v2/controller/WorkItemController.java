package com.conductor.v2.controller;

import com.conductor.entity.Asset;
import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.generated.v2.api.WorkItemsApi;
import com.conductor.generated.v2.model.WorkItemExternalLink;
import com.conductor.generated.v2.model.AvailableTransition;
import com.conductor.generated.v2.model.AvailableTransitionsResponse;
import com.conductor.generated.v2.model.CreateWorkItemRequest;
import com.conductor.generated.v2.model.PatchWorkItemRequest;
import com.conductor.generated.v2.model.WorkItemPriority;
import com.conductor.generated.v2.model.WorkItemProgress;
import com.conductor.generated.v2.model.WorkItemAssignee;
import com.conductor.generated.v2.model.WorkItemResponse;
import com.conductor.generated.v2.model.ReviewerResponse;
import com.conductor.service.PriorityScale;
import com.conductor.service.ProgressRollup;
import com.conductor.service.ProjectActor;
import com.conductor.service.ReviewerService;
import com.conductor.service.WorkItemCriteriaService;
import com.conductor.service.WorkItemDependencyService;
import com.conductor.service.WorkItemSignalService;
import com.conductor.entity.WorkItemDependency;
import com.conductor.service.WorkItemHierarchyService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.conductor.service.view.ReviewerView;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkItemWorkflowService;
import com.conductor.service.view.AvailableTransitionsView;
import com.conductor.workflow.lifecycle.Statechart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Canonical v2 Work Item resource ({@code /api/v2/projects/{projectId}/work-items}). This is the successor
 * to the legacy v1 {@code issues} core, which has been fully removed (migration completed 2026). All
 * business logic lives in the shared {@link WorkItemService} /
 * {@link WorkItemWorkflowService} — this controller only translates the v2 request/response DTOs and maps
 * the {@link WorkItem} entity (which lets v2 surface the bound {@code workflow} slug, unlike v1).
 *
 * <p>The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig} for controllers under the
 * {@code com.conductor.v2} package, so this class maps at bare paths via the generated interface.
 */
@RestController
public class WorkItemController implements WorkItemsApi {

    private static final Logger log = LoggerFactory.getLogger(WorkItemController.class);
    private static final ObjectMapper FIELDS_MAPPER = new ObjectMapper();

    private final WorkItemService workItemService;
    private final WorkItemWorkflowService workItemWorkflowService;
    private final ReviewerService reviewerService;
    private final WorkItemListEnricher listEnricher;
    private final WorkItemHierarchyService hierarchyService;
    private final WorkItemSignalService signalService;
    private final WorkItemDependencyService dependencyService;
    private final WorkItemCriteriaService criteriaService;

    public WorkItemController(WorkItemService workItemService,
                              WorkItemWorkflowService workItemWorkflowService,
                              ReviewerService reviewerService,
                              WorkItemListEnricher listEnricher,
                              WorkItemHierarchyService hierarchyService,
                              WorkItemSignalService signalService,
                              WorkItemDependencyService dependencyService,
                              WorkItemCriteriaService criteriaService) {
        this.hierarchyService = hierarchyService;
        this.signalService = signalService;
        this.dependencyService = dependencyService;
        this.criteriaService = criteriaService;
        this.workItemService = workItemService;
        this.workItemWorkflowService = workItemWorkflowService;
        this.reviewerService = reviewerService;
        this.listEnricher = listEnricher;
    }

    // open-in-view is disabled, so the entity→DTO mapping (which touches lazy User/Project associations)
    // must run inside the transaction — otherwise toResponse throws LazyInitializationException.
    @Override
    @Transactional
    public ResponseEntity<WorkItemResponse> createWorkItem(String projectId, CreateWorkItemRequest request) {
        User caller = currentUser();
        // Canonical surface: never silently default the Workflow. Callers must name the lifecycle Workflow that
        // governs the item (discover via GET .../workflows?lifecycle=true). The legacy v1 /issues create keeps
        // its ENGINEERING default for back-compat.
        // A sub-item is the exception: with a parent and no Workflow it lands on TASK (type TASK).
        boolean subItem = request.getParentWorkItemId() != null && !request.getParentWorkItemId().isBlank();
        boolean noWorkflow = request.getWorkflow() == null || request.getWorkflow().isBlank();
        if (noWorkflow && !subItem) {
            throw new BusinessException(
                    "workflow is required — name the lifecycle Workflow slug (e.g. ENGINEERING). "
                            + "Discover options via GET /api/v1/projects/{projectId}/workflows?lifecycle=true");
        }
        if ((request.getType() == null || request.getType().isBlank()) && !(subItem && noWorkflow)) {
            throw new BusinessException("type is required — a Workflow-defined type such as PRD");
        }
        // Blockers are checked before the item exists: a bad id is a 400 naming it, not the 404 that adding
        // it as a link to a not-yet-found item would raise.
        dependencyService.requireBlockersInProject(projectId, request.getBlockedBy());
        WorkItemService.DeliveryInput delivery = new WorkItemService.DeliveryInput(
                request.getParentWorkItemId(),
                request.getPriority() == null ? null : PriorityScale.parse(request.getPriority().getValue()),
                request.getMilestoneId(), request.getAppetite());
        WorkItem created = workItemService.createWorkItem(
                projectId, request.getType(), request.getTitle(), request.getDescription(),
                request.getWorkflow(), request.getTags(), request.getFields(), request.getDueOn(), delivery,
                ProjectActor.of(caller));
        // Criteria and blockers are part of creating the item: a rejected one (a foreign id, a cap) rolls the
        // whole create back, since this method is one transaction.
        if (request.getCriteria() != null && !request.getCriteria().isEmpty()) {
            criteriaService.replaceAll(projectId, created.getId(), request.getCriteria().stream()
                    .map(c -> new WorkItemCriteriaService.CriterionInput(c.getText(),
                            c.getKind() == null ? null : c.getKind().getValue(), c.getExternalRef()))
                    .toList());
        }
        if (request.getBlockedBy() != null) {
            for (String blockerId : request.getBlockedBy()) {
                // "blockedBy X" reads as the row "X BLOCKS new item".
                dependencyService.add(projectId, blockerId, WorkItemDependency.KIND_BLOCKS, created.getId(),
                        ProjectActor.of(caller));
            }
        }
        WorkItemSignalService.Signals signals =
                signalService.signalsFor(projectId, List.of(created)).get(created.getId());
        return ResponseEntity.status(201).body(withSignals(withChildren(toResponse(created, 0L), 0, null)
                .statusLabel(statusLabel(projectId, created)), signals));
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<List<WorkItemResponse>> listWorkItems(String projectId, String type, String status,
                                                                String workflow, String tag,
                                                                LocalDate dueBefore, String creativeId,
                                                                String mediaId, Boolean enrich,
                                                                String parentId, String rootId,
                                                                String milestoneId, WorkItemPriority priority,
                                                                String assigneeId, Boolean withProgress,
                                                                String sort, Boolean ready, Boolean blocked,
                                                                Boolean stale) {
        User caller = currentUser();
        List<WorkItem> items = workItemService.listWorkItemEntities(projectId,
                new WorkItemService.ListFilter(type, status, workflow, tag, dueBefore, parentId, rootId,
                        milestoneId, priority == null ? null : PriorityScale.parse(priority.getValue()),
                        assigneeId, sort),
                caller);
        // "Posts using this Creative / Media": narrow the already project-scoped, access-checked list.
        Set<String> using = listEnricher.idsUsing(projectId, creativeId, mediaId);
        if (using != null) {
            items = items.stream().filter(i -> using.contains(i.getId())).toList();
        }
        // Derived signals for the whole page in a few batched reads; ready / blocked / stale narrow the list
        // here, in Java, because they are judged in each item's own Workflow (like dueBefore).
        Map<String, WorkItemSignalService.Signals> signals = signalService.signalsFor(projectId, items);
        if (ready != null || blocked != null || stale != null) {
            items = items.stream()
                    .filter(i -> ready == null || signals.get(i.getId()) != null
                            && signals.get(i.getId()).ready() == ready)
                    .filter(i -> blocked == null || signals.get(i.getId()) != null
                            && signals.get(i.getId()).blocked() == blocked)
                    .filter(i -> stale == null || signals.get(i.getId()) != null
                            && signals.get(i.getId()).stale() == stale)
                    .toList();
        }
        List<String> ids = items.stream().map(WorkItem::getId).toList();
        Map<String, Long> counts = workItemService.unresolvedCommentCounts(ids);
        // Child counts for the whole page in one grouped query; progress only on request, as it reads each
        // parent's subtree.
        Map<String, Integer> childCounts = hierarchyService.childCounts(ids);
        Map<String, ProgressRollup.Progress> progress = Boolean.TRUE.equals(withProgress)
                ? hierarchyService.progressFor(projectId, childCounts.keySet()) : Map.of();
        // One query for the whole page, like the comment counts beside it — the list is exactly where an
        // N+1 would grow with the backlog.
        Map<String, List<Asset>> links = workItemService.externalLinks(ids);
        // Reviewers for the whole page in one batched read (plus one user batch), so the list view never
        // fans out a reviewers call per row.
        Map<String, List<ReviewerView>> reviewers = reviewerService.reviewersByWorkItem(ids);
        // Thumbnail, publish-target states and Creative for the whole page, again in batched reads.
        boolean withExtras = Boolean.TRUE.equals(enrich);
        Map<String, WorkItemListEnricher.Enrichment> enrichment =
                withExtras ? listEnricher.enrich(projectId, items) : Map.of();
        List<WorkItemResponse> responses = items.stream()
                .map(item -> {
                    WorkItemListEnricher.Enrichment extra =
                            enrichment.getOrDefault(item.getId(), WorkItemListEnricher.Enrichment.EMPTY);
                    return withChildren(toResponse(item, counts.getOrDefault(item.getId(), 0L),
                            links.getOrDefault(item.getId(), List.of())),
                            childCounts.getOrDefault(item.getId(), 0), progress.get(item.getId()))
                            .statusLabel(statusLabel(projectId, item))
                            .signals(signals.containsKey(item.getId())
                                    ? DeliveryMappers.signals(signals.get(item.getId())) : null)
                            .agentActivity(signals.containsKey(item.getId())
                                    ? DeliveryMappers.agentActivity(signals.get(item.getId()).agentActivity())
                                    : null)
                            .thumbnailUrl(withExtras ? extra.thumbnailUrl() : null)
                            .publishTargets(withExtras ? extra.publishTargets() : null)
                            .creative(withExtras ? extra.creative() : null)
                            .enrichmentFailed(withExtras && extra.failed() ? Boolean.TRUE : null)
                            .reviewers(reviewers.getOrDefault(item.getId(), List.of()).stream()
                                    .map(v -> new ReviewerResponse(v.userId())
                                            .name(v.name())
                                            .email(v.email())
                                            .avatarUrl(v.avatarUrl())
                                            .reviewVerdict(v.reviewVerdict()))
                                    .toList());
                })
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<WorkItemResponse> getWorkItem(String projectId, String workItemId) {
        User caller = currentUser();
        WorkItem item = workItemService.getWorkItemEntity(projectId, workItemId, caller);
        return ResponseEntity.ok(singleResponse(projectId, item));
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<WorkItemResponse> getWorkItemByDisplayId(String projectId, String displayId) {
        User caller = currentUser();
        WorkItem item = workItemService.resolveByDisplayId(projectId, displayId, caller);
        return ResponseEntity.ok(singleResponse(projectId, item));
    }

    @Override
    @Transactional
    public ResponseEntity<WorkItemResponse> patchWorkItem(String projectId, String workItemId,
                                                          PatchWorkItemRequest request) {
        User caller = currentUser();
        WorkItem item = workItemService.patchWorkItem(
                projectId, workItemId, request.getTitle(), request.getDescription(),
                request.getStatus(), request.getAssigneeId(), request.getScheduledFor(),
                request.getScheduleTimezone(), request.getTags(), request.getPublishOnApproval(),
                request.getFields(), request.getDueOn(), request.getParentWorkItemId(), request.getPriority(),
                request.getMilestoneId(), request.getAppetite(), caller);
        // Resolved here too: a client that refreshes its row from a patch response would otherwise see an
        // item's links vanish on an unrelated edit.
        return ResponseEntity.ok(singleResponse(projectId, item));
    }

    @Override
    public ResponseEntity<Void> deleteWorkItem(String projectId, String workItemId, Boolean confirmLive,
                                               Boolean cascade) {
        workItemService.deleteWorkItem(projectId, workItemId, currentUser(), Boolean.TRUE.equals(confirmLive),
                Boolean.TRUE.equals(cascade));
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<AvailableTransitionsResponse> getWorkItemAvailableTransitions(String projectId,
                                                                                        String workItemId) {
        User caller = currentUser();
        AvailableTransitionsView view =
                workItemWorkflowService.availableTransitions(projectId, workItemId, caller);
        return ResponseEntity.ok(toV2Transitions(view));
    }

    /**
     * The full single-item response: comment count, links, status label, and the item's child count and
     * progress. Shared by the two reads and the patch so a client refreshing from any of them sees the same.
     */
    private WorkItemResponse singleResponse(String projectId, WorkItem item) {
        int childCount = hierarchyService.childCounts(List.of(item.getId())).getOrDefault(item.getId(), 0);
        ProgressRollup.Progress progress = childCount > 0 ? hierarchyService.progress(projectId, item.getId()) : null;
        WorkItemSignalService.Signals signals =
                signalService.signalsFor(projectId, List.of(item)).get(item.getId());
        return withSignals(withChildren(
                toResponse(item, workItemService.unresolvedCommentCount(item.getId()), linksFor(item)),
                childCount, progress).statusLabel(statusLabel(projectId, item)), signals);
    }

    /** Adds the derived signals and the latest open agent session to a response. */
    private static WorkItemResponse withSignals(WorkItemResponse response, WorkItemSignalService.Signals signals) {
        if (signals != null) {
            response.setSignals(DeliveryMappers.signals(signals));
            response.setAgentActivity(DeliveryMappers.agentActivity(signals.agentActivity()));
        }
        return response;
    }

    /** Adds the child count and, for an item with children, its progress to a response. */
    static WorkItemResponse withChildren(WorkItemResponse response, int childCount,
                                         ProgressRollup.Progress progress) {
        response.setChildCount(childCount);
        if (progress != null) {
            response.setProgress(new WorkItemProgress(progress.total(), progress.done(), progress.percent(),
                    progress.criteriaTotal(), progress.criteriaMet()));
        }
        return response;
    }

    /** This one item's external links, as the single-item reads need them. */
    private List<Asset> linksFor(WorkItem item) {
        return workItemService.externalLinks(List.of(item.getId()))
                .getOrDefault(item.getId(), List.of());
    }

    /**
     * The human word for {@code status}: the item's own resolved Workflow statechart's display label for
     * its current status, or a humanized form of the raw id when the statechart sets none. A Workflow that
     * cannot be resolved (deleted since, say) never breaks the read — it falls back the same way.
     */
    private String statusLabel(String projectId, WorkItem item) {
        try {
            Statechart statechart = workItemWorkflowService.resolveFor(projectId, item);
            return statechart.status(item.getCurrentStatus())
                    .map(status -> status.displayLabel())
                    .orElseGet(() -> humanizeStatusId(item.getCurrentStatus()));
        } catch (RuntimeException e) {
            return humanizeStatusId(item.getCurrentStatus());
        }
    }

    /** "READY_FOR_DEV" -> "Ready For Dev": the fallback for a status id with no declared label. */
    private static String humanizeStatusId(String statusId) {
        if (statusId == null || statusId.isBlank()) {
            return statusId;
        }
        String[] words = statusId.toLowerCase(Locale.ROOT).split("_");
        StringBuilder label = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (label.length() > 0) {
                label.append(' ');
            }
            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return label.toString();
    }

    /** Map a {@link WorkItem} entity to the v2 response, with no external links resolved. */
    static WorkItemResponse toResponse(WorkItem item, long unresolvedCommentCount) {
        return toResponse(item, unresolvedCommentCount, List.of());
    }

    /** Map a {@link WorkItem} entity to the v2 response, surfacing the bound Workflow slug (absent in v1).
     *  Package-private (not {@code private}) so {@code WorkItemControllerTest} can unit test the
     *  null-createdBy/createdByLabel fallback without a Spring context. */
    static WorkItemResponse toResponse(WorkItem item, long unresolvedCommentCount,
                                       List<Asset> externalLinks) {
        String displayId = item.getProject().getKey() + "-" + item.getSequenceNumber();
        WorkItemAssignee assignee = null;
        if (item.getAssignee() != null) {
            User a = item.getAssignee();
            assignee = new WorkItemAssignee(a.getId(), a.getName()).avatarUrl(a.getAvatarUrl());
        }
        // createdBy is null for a machine-authored item (e.g. via coordinator:create_work_item) --
        // createdByLabel carries its identity instead. See the V125 migration / WorkItem#createdByLabel.
        return new WorkItemResponse(
                item.getId(),
                item.getProject().getId(),
                item.getType(),
                item.getTitle(),
                item.getCurrentStatus(),
                item.getCreatedBy() != null ? item.getCreatedBy().getId() : null,
                item.getCreatedAt(),
                item.getUpdatedAt(),
                item.getSequenceNumber(),
                displayId)
                .description(item.getDescription())
                .workflow(item.getWorkflow())
                .unresolvedCommentCount((int) unresolvedCommentCount)
                .assignee(assignee)
                .createdByLabel(item.getCreatedByLabel())
                .scheduledFor(item.getScheduledFor())
                .scheduleTimezone(item.getScheduleTimezone())
                .publishOnApproval(item.isPublishOnApproval())
                // The generated model defaults this to an empty list; it is a list-only extra, so leave it
                // absent unless the list was asked to enrich.
                .publishTargets(null)
                .tags(List.copyOf(item.getTags()))
                .fields(fieldsOf(item))
                .dueOn(item.getDueOn())
                .parentWorkItemId(item.getParentWorkItemId())
                .priority(item.getPriority() == null ? null
                        : WorkItemPriority.fromValue(PriorityScale.label(item.getPriority())))
                .milestoneId(item.getMilestone() == null ? null : item.getMilestone().getId())
                .milestoneName(item.getMilestone() == null ? null : item.getMilestone().getName())
                .appetite(item.getAppetite())
                .appetiteDays(item.getAppetiteDays())
                .startedAt(item.getStartedAt())
                .completedAt(item.getCompletedAt())
                .externalLinks(externalLinks.stream()
                        .map(asset -> new WorkItemExternalLink(asset.getRef(), asset.getType())
                                .label(asset.getLabel()))
                        .toList());
    }

    /** The stored field values as a plain map; null when the item has none. */
    private static Map<String, Object> fieldsOf(WorkItem item) {
        return item.getFields() == null || item.getFields().isEmpty() ? null
                : FIELDS_MAPPER.convertValue(item.getFields(), new TypeReference<Map<String, Object>>() { });
    }

    /** Map the doer-projection transitions view into its v2 response DTO (identical shape). */
    private static AvailableTransitionsResponse toV2Transitions(AvailableTransitionsView view) {
        List<AvailableTransition> transitions = view.transitions().stream()
                .map(t -> {
                    AvailableTransition at = new AvailableTransition(t.toStatus(), t.label());
                    at.setRequiresReview(t.requiresReview());
                    return at;
                })
                .toList();
        AvailableTransitionsResponse response =
                new AvailableTransitionsResponse(view.workflow(), view.currentStatus(), transitions);
        response.setNoun(view.noun());
        return response;
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object principal = auth != null ? auth.getPrincipal() : null;
        if (!(principal instanceof User)) {
            log.warn("currentUser() expected User principal but got {} (auth type={})",
                    principal == null ? "null" : principal.getClass().getName(),
                    auth == null ? "null" : auth.getClass().getSimpleName());
            throw new ClassCastException("Expected User principal but got: " +
                    (principal == null ? "null" : principal.getClass().getName()));
        }
        return (User) principal;
    }
}
