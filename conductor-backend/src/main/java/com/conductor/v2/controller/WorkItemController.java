package com.conductor.v2.controller;

import com.conductor.entity.User;
import com.conductor.entity.WorkItem;
import com.conductor.exception.BusinessException;
import com.conductor.generated.v2.api.WorkItemsApi;
import com.conductor.generated.v2.model.AvailableTransition;
import com.conductor.generated.v2.model.AvailableTransitionsResponse;
import com.conductor.generated.v2.model.CreateWorkItemRequest;
import com.conductor.generated.v2.model.PatchWorkItemRequest;
import com.conductor.generated.v2.model.WorkItemAssignee;
import com.conductor.generated.v2.model.WorkItemResponse;
import com.conductor.service.WorkItemService;
import com.conductor.service.WorkItemWorkflowService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Canonical v2 Work Item resource ({@code /api/v2/projects/{projectId}/work-items}). This is the successor
 * to the legacy v1 {@code issues} core (see {@code docs/legacy-v1-deprecation.md}); it is additive and does
 * not change v1 behavior. All business logic lives in the shared {@link WorkItemService} /
 * {@link WorkItemWorkflowService} — this controller only translates the v2 request/response DTOs and maps
 * the {@link WorkItem} entity (which lets v2 surface the bound {@code workflow} slug, unlike v1).
 *
 * <p>The {@code /api/v2} prefix is applied structurally by {@code ApiPathConfig} for controllers under the
 * {@code com.conductor.v2} package, so this class maps at bare paths via the generated interface.
 */
@RestController
public class WorkItemController implements WorkItemsApi {

    private static final Logger log = LoggerFactory.getLogger(WorkItemController.class);

    private final WorkItemService workItemService;
    private final WorkItemWorkflowService workItemWorkflowService;

    public WorkItemController(WorkItemService workItemService,
                              WorkItemWorkflowService workItemWorkflowService) {
        this.workItemService = workItemService;
        this.workItemWorkflowService = workItemWorkflowService;
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
        if (request.getWorkflow() == null || request.getWorkflow().isBlank()) {
            throw new BusinessException(
                    "workflow is required — name the lifecycle Workflow slug (e.g. ENGINEERING). "
                            + "Discover options via GET /api/v1/projects/{projectId}/workflows?lifecycle=true");
        }
        WorkItem created = workItemService.createWorkItem(
                projectId, request.getType(), request.getTitle(), request.getDescription(),
                request.getWorkflow(), caller);
        return ResponseEntity.status(201).body(toResponse(created, 0L));
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<List<WorkItemResponse>> listWorkItems(String projectId, String type, String status,
                                                                String workflow) {
        User caller = currentUser();
        List<WorkItem> items = workItemService.listWorkItemEntities(projectId, type, status, workflow, caller);
        Map<String, Long> counts = workItemService.unresolvedCommentCounts(
                items.stream().map(WorkItem::getId).toList());
        List<WorkItemResponse> responses = items.stream()
                .map(item -> toResponse(item, counts.getOrDefault(item.getId(), 0L)))
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<WorkItemResponse> getWorkItem(String projectId, String workItemId) {
        User caller = currentUser();
        WorkItem item = workItemService.getWorkItemEntity(projectId, workItemId, caller);
        return ResponseEntity.ok(toResponse(item, workItemService.unresolvedCommentCount(item.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public ResponseEntity<WorkItemResponse> getWorkItemByDisplayId(String projectId, String displayId) {
        User caller = currentUser();
        WorkItem item = workItemService.resolveByDisplayId(projectId, displayId, caller);
        return ResponseEntity.ok(toResponse(item, workItemService.unresolvedCommentCount(item.getId())));
    }

    @Override
    @Transactional
    public ResponseEntity<WorkItemResponse> patchWorkItem(String projectId, String workItemId,
                                                          PatchWorkItemRequest request) {
        User caller = currentUser();
        WorkItem item = workItemService.patchWorkItem(
                projectId, workItemId, request.getTitle(), request.getDescription(),
                request.getStatus(), request.getAssigneeId(), caller);
        return ResponseEntity.ok(toResponse(item, workItemService.unresolvedCommentCount(item.getId())));
    }

    @Override
    public ResponseEntity<Void> deleteWorkItem(String projectId, String workItemId) {
        workItemService.deleteIssue(projectId, workItemId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<AvailableTransitionsResponse> getWorkItemAvailableTransitions(String projectId,
                                                                                        String workItemId) {
        User caller = currentUser();
        com.conductor.generated.model.AvailableTransitionsResponse v1 =
                workItemWorkflowService.availableTransitions(projectId, workItemId, caller);
        return ResponseEntity.ok(toV2Transitions(v1));
    }

    /** Map a {@link WorkItem} entity to the v2 response, surfacing the bound Workflow slug (absent in v1). */
    private static WorkItemResponse toResponse(WorkItem item, long unresolvedCommentCount) {
        String displayId = item.getProject().getKey() + "-" + item.getSequenceNumber();
        WorkItemAssignee assignee = null;
        if (item.getAssignee() != null) {
            User a = item.getAssignee();
            assignee = new WorkItemAssignee(a.getId(), a.getName()).avatarUrl(a.getAvatarUrl());
        }
        return new WorkItemResponse(
                item.getId(),
                item.getProject().getId(),
                item.getType(),
                item.getTitle(),
                item.getCurrentStatus(),
                item.getCreatedBy().getId(),
                item.getCreatedAt(),
                item.getUpdatedAt(),
                item.getSequenceNumber(),
                displayId)
                .description(item.getDescription())
                .workflow(item.getWorkflow())
                .unresolvedCommentCount((int) unresolvedCommentCount)
                .assignee(assignee);
    }

    /** Translate the v1 doer-projection transitions DTO into its v2 model copy (identical shape). */
    private static AvailableTransitionsResponse toV2Transitions(
            com.conductor.generated.model.AvailableTransitionsResponse v1) {
        List<AvailableTransition> transitions = v1.getTransitions().stream()
                .map(t -> {
                    AvailableTransition at = new AvailableTransition(t.getToStatus(), t.getLabel());
                    at.setRequiresReview(t.getRequiresReview());
                    return at;
                })
                .toList();
        AvailableTransitionsResponse response =
                new AvailableTransitionsResponse(v1.getWorkflow(), v1.getCurrentStatus(), transitions);
        response.setNoun(v1.getNoun());
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
