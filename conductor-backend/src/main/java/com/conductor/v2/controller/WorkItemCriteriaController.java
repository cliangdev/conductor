package com.conductor.v2.controller;

import com.conductor.generated.v2.api.WorkItemCriteriaApi;
import com.conductor.generated.v2.model.CreateWorkItemCriterion;
import com.conductor.generated.v2.model.PatchWorkItemCriterionRequest;
import com.conductor.generated.v2.model.PatchWorkItemCriterionResponse;
import com.conductor.generated.v2.model.WorkItemCriterionResponse;
import com.conductor.service.ProjectActor;
import com.conductor.service.ProjectSecurityService;
import com.conductor.service.WorkItemCriteriaService;
import com.conductor.service.WorkItemCriteriaService.CriterionInput;
import com.conductor.service.WorkItemCriteriaService.PatchResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Acceptance criteria ({@code /api/v2/projects/{projectId}/work-items/{workItemId}/criteria}). Logic lives in
 * {@link WorkItemCriteriaService}; reads need project access, writes an editor (machine credentials pass
 * both, so an agent can tick criteria off). Mapping reads only the criterion's own columns, so no
 * transaction is needed here.
 */
@RestController
public class WorkItemCriteriaController implements WorkItemCriteriaApi {

    private final WorkItemCriteriaService criteriaService;
    private final ProjectSecurityService projectSecurityService;

    public WorkItemCriteriaController(WorkItemCriteriaService criteriaService,
                                      ProjectSecurityService projectSecurityService) {
        this.criteriaService = criteriaService;
        this.projectSecurityService = projectSecurityService;
    }

    @Override
    public ResponseEntity<List<WorkItemCriterionResponse>> listWorkItemCriteria(String projectId,
                                                                                String workItemId) {
        projectSecurityService.requireProjectAccess(projectId);
        return ResponseEntity.ok(criteriaService.list(projectId, workItemId).stream()
                .map(DeliveryMappers::criterion).toList());
    }

    @Override
    public ResponseEntity<List<WorkItemCriterionResponse>> replaceWorkItemCriteria(
            String projectId, String workItemId, List<CreateWorkItemCriterion> request) {
        projectSecurityService.requireProjectEditor(projectId);
        List<CriterionInput> inputs = request.stream()
                .map(c -> new CriterionInput(c.getText(), c.getKind() == null ? null : c.getKind().getValue(),
                        c.getExternalRef()))
                .toList();
        return ResponseEntity.ok(criteriaService.replaceAll(projectId, workItemId, inputs).stream()
                .map(DeliveryMappers::criterion).toList());
    }

    @Override
    public ResponseEntity<PatchWorkItemCriterionResponse> patchWorkItemCriterion(
            String projectId, String workItemId, String criterionId, PatchWorkItemCriterionRequest request) {
        ProjectActor actor = projectSecurityService.requireProjectEditor(projectId);
        PatchResult result = criteriaService.patch(projectId, workItemId, criterionId, request.getText(),
                request.getKind() == null ? null : request.getKind().getValue(), request.getMet(),
                request.getEvidence(), actor);
        return ResponseEntity.ok(new PatchWorkItemCriterionResponse(DeliveryMappers.criterion(result.criterion()),
                result.criteriaMet(), result.criteriaTotal()));
    }
}
