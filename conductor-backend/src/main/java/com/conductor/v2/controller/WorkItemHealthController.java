package com.conductor.v2.controller;

import com.conductor.entity.WorkItemHealthUpdate;
import com.conductor.generated.v2.api.WorkItemHealthApi;
import com.conductor.generated.v2.model.ConfirmWorkItemHealthUpdateRequest;
import com.conductor.generated.v2.model.CreateWorkItemHealthUpdateRequest;
import com.conductor.generated.v2.model.WorkItemHealthUpdateResponse;
import com.conductor.service.ProjectActor;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.WorkItemHealthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Health updates ({@code /api/v2/projects/{projectId}/work-items/{workItemId}/health-updates}). Reads need
 * project access. Writing needs an editor; a draft may come from a machine credential, but a confirmed
 * update (and confirming a draft) needs a person, which {@link WorkItemHealthService} enforces with a 403.
 */
@RestController
public class WorkItemHealthController implements WorkItemHealthApi {

    private final WorkItemHealthService healthService;
    private final ProjectSecurityService projectSecurityService;

    public WorkItemHealthController(WorkItemHealthService healthService,
                                    ProjectSecurityService projectSecurityService) {
        this.healthService = healthService;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<List<WorkItemHealthUpdateResponse>> listWorkItemHealthUpdates(String projectId,
                                                                                         String workItemId) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(toResponses(healthService.history(projectId, workItemId)));
    }

    @Override
    public ResponseEntity<WorkItemHealthUpdateResponse> createWorkItemHealthUpdate(
            String projectId, String workItemId, CreateWorkItemHealthUpdateRequest request) {
        ProjectActor actor = projectSecurityService.requireProjectEditor(projectId);
        String health = request.getHealth().getValue();
        WorkItemHealthUpdate update = Boolean.TRUE.equals(request.getDraft())
                ? healthService.draft(projectId, workItemId, health, request.getNote(), actor.displayName())
                : healthService.record(projectId, workItemId, health, request.getNote(), actor.user());
        return ResponseEntity.status(201).body(toResponses(List.of(update)).get(0));
    }

    @Override
    public ResponseEntity<WorkItemHealthUpdateResponse> confirmWorkItemHealthUpdate(
            String projectId, String workItemId, String updateId, ConfirmWorkItemHealthUpdateRequest request) {
        ProjectActor actor = projectSecurityService.requireProjectEditor(projectId);
        WorkItemHealthUpdate update = healthService.confirm(projectId, workItemId, updateId, actor.user(),
                request == null || request.getHealth() == null ? null : request.getHealth().getValue(),
                request == null ? null : request.getNote());
        return ResponseEntity.ok(toResponses(List.of(update)).get(0));
    }

    private List<WorkItemHealthUpdateResponse> toResponses(List<WorkItemHealthUpdate> updates) {
        var names = healthService.confirmerNames(updates);
        return updates.stream().map(u -> DeliveryMappers.healthUpdate(u, names)).toList();
    }
}
